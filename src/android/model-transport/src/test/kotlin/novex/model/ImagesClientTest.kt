package novex.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.Executors

/**
 * ImagesClient 契约：请求形状（体键/头/路径）、b64_json 自动探测重试、b64/url 两种
 * 条目解析、代理误路由 404 语义、multipart 编辑形状与错误素材。
 */
class ImagesClientTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val pngB64 = Base64.getEncoder().encodeToString(png)

    private class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String>,   // 键已小写
        val body: ByteArray,
    )

    private class Server : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Recorded>()
        val responses = ArrayDeque<Pair<Int, String>>()   // 按序消费；空 → 200 空体
        var downloadHandler: ((HttpExchange) -> Unit)? = null
        val base: URI get() = URI("http://127.0.0.1:${server.address.port}/v1")

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                requests += Recorded(exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.joinToString(",") },
                    exchange.requestBody.readBytes())
                if (exchange.requestURI.path.endsWith("/download")) {
                    downloadHandler?.invoke(exchange)
                    return@createContext
                }
                val (status, body) = responses.removeFirstOrNull() ?: Pair(200, "")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
            server.start()
        }

        override fun close() { server.stop(0) }
    }

    private fun client(server: Server, token: String? = "sk-key") =
        ImagesClient(server.base, token, "Minis/test (Android 13; Pixel)")

    @Test fun `生成式请求形状与 b64 条目解析`() {
        Server().use { server ->
            server.responses += Pair(200, """{"data":[{"b64_json":"$pngB64","revised_prompt":"rev-1"}]}""")
            val result = client(server).generate("gpt-image-2", "一只猫", 2, "1024x1024", "high")
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/images/generations", request.path)
            assertEquals("Bearer sk-key", request.headers["authorization"])
            assertEquals("Minis/test (Android 13; Pixel)", request.headers["user-agent"])
            val body = JSONObject(String(request.body))
            assertEquals("gpt-image-2", body.getString("model"))
            assertEquals("一只猫", body.getString("prompt"))
            assertEquals(2, body.getInt("n"))
            assertEquals("1024x1024", body.getString("size"))
            assertEquals("high", body.getString("quality"))
            assertEquals("b64_json", body.getString("response_format"))
            val success = result as ImagesResult.Success
            assertArrayEquals(png, success.images.single().data)
            assertEquals("image/png", success.images.single().mimeType)   // 魔数兜底
            assertEquals("rev-1", success.revisedPromptText)
        }
    }

    @Test fun `400 提及 response_format 时去掉该键重试一次`() {
        Server().use { server ->
            server.responses += Pair(400, """{"error":{"message":"response_format is not supported"}}""")
            server.responses += Pair(200, """{"data":[{"b64_json":"$pngB64"}]}""")
            val result = client(server).generate("grok-2-image", "一只猫", 1, null, null)
            assertTrue(result is ImagesResult.Success)
            assertEquals(2, server.requests.size)
            val first = JSONObject(String(server.requests[0].body))
            val second = JSONObject(String(server.requests[1].body))
            assertTrue(first.has("response_format"))
            assertFalse(second.has("response_format"))
        }
    }

    @Test fun `mime_type 提示优先于魔数兜底`() {
        Server().use { server ->
            server.responses += Pair(200, """{"data":[{"b64_json":"$pngB64","mime_type":"image/webp"}]}""")
            val result = client(server).generate("grok-2-image", "一只猫", 1, null, null)
            assertEquals("image/webp", (result as ImagesResult.Success).images.single().mimeType)
        }
    }

    @Test fun `url 条目无鉴权下载且 mime 取响应头`() {
        Server().use { server ->
            server.downloadHandler = { exchange ->
                exchange.responseHeaders.set("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            val url = "http://127.0.0.1:${server.server.address.port}/download"
            server.responses += Pair(200, """{"data":[{"url":"$url"}]}""")
            val result = client(server).generate("dall-e-3", "一只猫", 1, null, null)
            val success = result as ImagesResult.Success
            assertArrayEquals(png, success.images.single().data)
            assertEquals("image/png", success.images.single().mimeType)
            val download = server.requests[1]
            assertEquals("GET", download.method)
            assertNull("下载不得携带鉴权头", download.headers["authorization"])
        }
    }

    @Test fun `错误体按 OpenAI 形态抽取 message 与 request_id`() {
        Server().use { server ->
            // 用 500 抽错误体（桌面 JVM 的 HttpURLConnection 在 401 上会吞掉错误体——
            // Android 实现无此行为；抽取逻辑与状态码无关，鉴权分类由调用方按状态判定）。
            server.responses += Pair(500, """{"error":{"message":"upstream exploded","request_id":"req-7"}}""")
            val result = client(server).generate("gpt-image-2", "一只猫", 1, null, null)
            val failure = result as ImagesResult.HttpError
            assertEquals(500, failure.status)
            assertEquals("HTTP 500: upstream exploded; request_id=req-7", failure.message)
        }
    }

    @Test fun `非 2xx 一律按状态进 HttpError 不本地分类`() {
        Server().use { server ->
            server.responses += Pair(401, "whatever")
            val result = client(server).generate("gpt-image-2", "一只猫", 1, null, null)
            assertTrue(result is ImagesResult.HttpError)
            assertEquals(401, (result as ImagesResult.HttpError).status)
        }
    }

    @Test fun `代理误路由到 chat completions 时给出可判别的 404`() {
        Server().use { server ->
            server.responses += Pair(200, """{"choices":[{"message":{"content":"hi"}}]}""")
            val result = client(server).generate("gpt-image-2", "一只猫", 1, null, null)
            val failure = result as ImagesResult.HttpError
            assertEquals(404, failure.status)
            assertTrue(failure.message.contains("not supported"))
            assertTrue(failure.message.contains("chat completions"))
        }
    }

    @Test fun `编辑接口 multipart 形状与首图字段名`() {
        Server().use { server ->
            server.responses += Pair(200, """{"data":[{"b64_json":"$pngB64"}]}""")
            val result = client(server).edit("gpt-image-2", "改图", listOf(ImageInput("image/png", png)), 1, null, null)
            assertTrue(result is ImagesResult.Success)
            val request = server.requests.single()
            assertEquals("/v1/images/edits", request.path)
            val contentType = request.headers["content-type"]!!
            assertTrue(contentType.startsWith("multipart/form-data; boundary=minis-"))
            val body = String(request.body)
            assertTrue(body.contains("Content-Disposition: form-data; name=\"model\"\r\n\r\ngpt-image-2"))
            assertTrue(body.contains("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n改图"))
            assertTrue(body.contains("Content-Disposition: form-data; name=\"n\"\r\n\r\n1"))
            assertTrue(body.contains("Content-Disposition: form-data; name=\"response_format\"\r\n\r\nb64_json"))
            assertTrue(body.contains("Content-Disposition: form-data; name=\"image\"; filename=\"image0.png\""))
            assertTrue(body.contains("Content-Type: image/png"))
            assertFalse("第二张起才用 image[]", body.contains("name=\"image[]\""))
        }
    }

    @Test fun `空图列表在开连接前拒绝`() {
        Server().use { server ->
            val result = client(server).edit("gpt-image-2", "改图", emptyList(), 1, null, null)
            assertTrue(result is ImagesResult.InvalidResponse)
            assertTrue(server.requests.isEmpty())
        }
    }
}
