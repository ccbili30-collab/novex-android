package novex.model

import com.sun.net.httpserver.HttpServer
import novex.conversation.ModelCapacity
import novex.conversation.TokenMeasurement
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

/**
 * 绞杀适配器（app 侧 NovexTransportProvider）所需的模块面：
 * 自定义请求头、assistant 历史 reasoning_content 回放、用户音频块、附加顶层参数。
 */
class ChatCompletionAdapterSurfaceTest {
    private fun measure(@Suppress("UNUSED_PARAMETER") value:String)=TokenMeasurement(0,"适配器直通，本层不计量",true)
    private fun server(block:(HttpServer,ModelEndpoint)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try { block(server,ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/chat/completions"),"token")) }
        finally { server.stop(0);executor.shutdownNow() }
    }

    @Test fun `附加请求头随连接送达且不覆盖鉴权`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try {
            var userAgent="";var authorization="";var custom=""
            server.createContext("/chat/completions") { exchange ->
                userAgent=exchange.requestHeaders.getFirst("User-Agent") ?: ""
                authorization=exchange.requestHeaders.getFirst("Authorization") ?: ""
                custom=exchange.requestHeaders.getFirst("X-Custom") ?: ""
                exchange.sendResponseHeaders(200,0)
                exchange.responseBody.use { it.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8)) }
            }
            val branded=ModelEndpoint(
                URI("http://127.0.0.1:${server.address.port}/chat/completions"),"token",
                mapOf("User-Agent" to "Novex/1.0 (Android 15; Test)","X-Custom" to "yes"))
            val request=TextRequest("m",listOf(WireMessage("user","问")),16)
            assertEquals(StreamResult.Completed,ChatCompletionCall(branded).stream(StreamRequest(request),ModelCapacity(128_000),128_000,::measure){})
            assertEquals("Novex/1.0 (Android 15; Test)",userAgent)
            assertEquals("Bearer token",authorization)
            assertEquals("yes",custom)
        } finally { server.stop(0);executor.shutdownNow() }
    }

    @Test fun `请求头含换行或冒号被拒绝`() {
        val base=URI("https://example.com/v1/chat/completions")
        assertThrows(IllegalArgumentException::class.java) { ModelEndpoint(base,"t",mapOf("Bad-Header" to "a\r\nb")) }
        assertThrows(IllegalArgumentException::class.java) { ModelEndpoint(base,"t",mapOf("Bad:Name" to "v")) }
        assertThrows(IllegalArgumentException::class.java) { ModelEndpoint(base,"t",mapOf(" " to "v")) }
    }

    @Test fun `assistant 历史 reasoning_content 回放含空串`() {
        val assistant=WireMessage("assistant","先做",reasoningContent="")
        val encoded=assistant.encode()
        assertTrue(encoded.has("reasoning_content"))
        assertEquals("",encoded.getString("reasoning_content"))
        assertNull(WireMessage("user","问").encode().opt("reasoning_content"))
        assertThrows(IllegalArgumentException::class.java) { WireMessage("user","问",reasoningContent="不应出现") }
    }

    @Test fun `用户消息音频块与图片并列编码`() {
        val message=WireMessage("user","听这段",images=listOf(WireImage("image/png","aGk=")),audios=listOf(WireAudio("wav","YXVkaW8=")))
        val content=message.encode().getJSONArray("content")
        assertEquals(3,content.length())
        assertEquals("text",content.getJSONObject(0).getString("type"))
        assertEquals("image_url",content.getJSONObject(1).getString("type"))
        val audio=content.getJSONObject(2)
        assertEquals("input_audio",audio.getString("type"))
        assertEquals("wav",audio.getJSONObject("input_audio").getString("format"))
        assertEquals("YXVkaW8=",audio.getJSONObject("input_audio").getString("data"))
        assertThrows(IllegalArgumentException::class.java) { WireMessage("assistant","说",audios=listOf(WireAudio("wav","YXVkaW8="))) }
    }

    @Test fun `附加顶层参数合入请求体且流式编码保留`()=server { server,endpoint ->
        var received=""
        server.createContext("/chat/completions") { exchange ->
            received=exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { it.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8)) }
        }
        val extras=JSONObject().put("reasoning_effort","high").put("thinking",JSONObject().put("type","enabled"))
        val request=TextRequest("m",listOf(WireMessage("user","问")),16,extraParameters=extras)
        assertEquals(StreamResult.Completed,ChatCompletionCall(endpoint).stream(StreamRequest(request),ModelCapacity(128_000),128_000,::measure){})
        val encoded=JSONObject(received)
        assertEquals("high",encoded.getString("reasoning_effort"))
        assertEquals("enabled",encoded.getJSONObject("thinking").getString("type"))
        assertEquals(16,encoded.getInt("max_tokens"))
        assertTrue(encoded.getBoolean("stream"))
    }

    @Test fun `附加参数触碰契约键即拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextRequest("m",listOf(WireMessage("user","问")),16,extraParameters=JSONObject().put("max_tokens",999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextRequest("m",listOf(WireMessage("user","问")),16,extraParameters=JSONObject().put("stream",false))
        }
    }

    @Test fun `无附加参数的请求体与既有形状一致`() {
        val plain=TextRequest("m",listOf(WireMessage("user","问")),16).encode()
        assertFalse(JSONObject(plain).has("reasoning_effort"))
        assertEquals("问",JSONObject(plain).getJSONArray("messages").getJSONObject(0).getString("content"))
    }
}
