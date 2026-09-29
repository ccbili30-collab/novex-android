package novex.model

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

/**
 * ModelsCatalog 契约：三方言 URL 构造与基址爬升、响应解析（过滤/盖章/raw 透出）、
 * GET 执行的头形状与错误分类、端点契约（查询串需显式放行）。
 */
class ModelsCatalogTest {

    // -----------------------------------------------------------------
    // 纯函数面
    // -----------------------------------------------------------------

    @Test fun `anthropic 模型端点按基址形态构造且不强注 v1`() {
        assertEquals("https://api.anthropic.com/v1/models?limit=512",
            ModelsCatalog.anthropicModelsUrl(null))
        // 自定基址已规范化（appendV1Suffix 开）：只拼 /models。
        assertEquals("https://relay.example.com/v1/models?limit=512",
            ModelsCatalog.anthropicModelsUrl("https://relay.example.com/v1"))
        // 基址不带 /v1（开关关）：首层不强制补——交给用户开关决定。
        assertEquals("https://relay.example.com/models?limit=512",
            ModelsCatalog.anthropicModelsUrl("https://relay.example.com"))
        // 父路径发现层：主机根 /v1/models 约定。
        assertEquals("https://relay.example.com/v1/models?limit=512",
            ModelsCatalog.anthropicModelsUrl("https://relay.example.com", forceV1Discovery = true))
    }

    @Test fun `anthropic 候选基址爬升三层且剥查询串`() {
        assertEquals(
            listOf("https://api.deepseek.com/anthropic", "https://api.deepseek.com"),
            ModelsCatalog.anthropicCandidateBases("https://api.deepseek.com/anthropic"))
        assertEquals(
            listOf("https://host.io/a/b/c", "https://host.io/a/b", "https://host.io/a", "https://host.io"),
            ModelsCatalog.anthropicCandidateBases("https://host.io/a/b/c"))
        // 主机根（无路径可剥）只保留原值。
        assertEquals(listOf<String?>("https://host.io"), ModelsCatalog.anthropicCandidateBases("https://host.io"))
        assertNull(ModelsCatalog.parentPath("https://host.io"))
        assertEquals("https://host.io", ModelsCatalog.parentPath("https://host.io/a?x=1"))
    }

    @Test fun `anthropic 解析盖章思考机型且坏体返回 null`() {
        val body = """{"data":[{"id":"claude-opus-4-8","display_name":"Opus 4.8"},{"id":"claude-3-5-sonnet"}]}"""
        val models = ModelsCatalog.parseAnthropic(body)!!
        assertEquals(listOf("claude-opus-4-8", "claude-3-5-sonnet"), models.map { it.id })
        assertEquals("Opus 4.8", models[0].displayName)
        assertEquals(true, models[0].supportsReasoning)
        assertNull("3.5 不支持扩展思考，不盖章", models[1].supportsReasoning)
        assertNull(ModelsCatalog.parseAnthropic("not json"))
        assertEquals(emptyList<ModelsCatalog.CatalogModel>(), ModelsCatalog.parseAnthropic("""{"data":[]}"""))
    }

    @Test fun `gemini 解析按 generateContent 过滤且剥 models 前缀`() {
        val body = """{"models":[
            {"name":"models/gemini-2.5-flash","displayName":"Flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}]}"""
        val models = ModelsCatalog.parseGemini(body)!!
        assertEquals(listOf("gemini-2.5-flash", "embedding-001"), models.map { it.id })
        assertEquals(listOf(true, false), models.map { it.chatCapable })
        assertEquals("Flash", models[0].displayName)
        assertEquals("embedding-001", models[1].displayName)   // 缺 displayName 回退 id
    }

    @Test fun `openai 解析官方端点过滤非聊天系而自定端点全收`() {
        val body = """{"data":[
            {"id":"gpt-5.5","name":"GPT-5.5","architecture":{"input_modalities":["text","image"]}},
            {"id":"whisper-1","name":"Whisper"},
            {"id":"ft:gpt-4o:mine:abc","name":"fine-tune"},
            {"id":"deepseek-v4","context_length":128000}]}"""
        val official = ModelsCatalog.parseOpenAi(body, officialEndpoint = true)!!
        assertEquals(listOf("gpt-5.5"), official.map { it.id })
        assertEquals(listOf("text", "image"), official[0].raw!!.getJSONObject("architecture").optJSONArray("input_modalities").let { list -> (0 until list.length()).map(list::getString) })
        val custom = ModelsCatalog.parseOpenAi(body, officialEndpoint = false)!!
        assertEquals(listOf("gpt-5.5", "whisper-1", "ft:gpt-4o:mine:abc", "deepseek-v4"), custom.map { it.id })
        assertEquals(128000, custom[3].raw!!.getInt("context_length"))
        assertNull(ModelsCatalog.parseOpenAi("broken", officialEndpoint = false))
    }

    @Test fun `openai 端点构造不重复 v1`() {
        assertEquals("https://api.openai.com/v1/models", ModelsCatalog.openAiModelsUrl(null))
        assertEquals("https://api.deepseek.com/v1/models", ModelsCatalog.openAiModelsUrl("https://api.deepseek.com/v1"))
        assertEquals("https://host.io/models", ModelsCatalog.openAiModelsUrl("https://host.io"))
    }

    // -----------------------------------------------------------------
    // GET 执行
    // -----------------------------------------------------------------

    private class Served(val status: Int, val body: String) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var recordedHeaders: Map<String, String> = emptyMap()
        var recordedPath: String = ""
        init {
            server.executor = Executors.newSingleThreadExecutor()
            server.createContext("/") { exchange ->
                recordedPath = exchange.requestURI.path + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
                recordedHeaders = exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.joinToString(",") }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
            server.start()
        }
        fun close() { server.stop(0) }
        fun use(block: (Served) -> Unit) { try { block(this) } finally { close() } }
    }

    @Test fun `get 按方言头形状送达且成功读体`() {
        Served(200, """{"data":[{"id":"gpt-5.5"}]}""").use { served ->
            val result = ModelsCatalog.get(
                URI("http://127.0.0.1:${served.server.address.port}/v1/models"),
                mapOf("Authorization" to "Bearer sk-key", "User-Agent" to "Minis/test"))
            assertEquals(ModelsCatalog.FetchResult.Success("""{"data":[{"id":"gpt-5.5"}]}"""), result)
            assertEquals("/v1/models", served.recordedPath)
            assertEquals("Bearer sk-key", served.recordedHeaders["authorization"])
            assertEquals("Minis/test", served.recordedHeaders["user-agent"])
        }
    }

    @Test fun `get 的非 200 与网络失败分类`() {
        Served(401, """{"error":"bad key"}""").use { served ->
            val result = ModelsCatalog.get(URI("http://127.0.0.1:${served.server.address.port}/models"), emptyMap())
            assertEquals(ModelsCatalog.FetchResult.HttpError(401, """{"error":"bad key"}"""), result)
        }
        // 连不上的端口 → NetworkFailure（无内部重试）。
        val dead = ModelsCatalog.get(URI("http://127.0.0.1:9/models"), emptyMap(), timeoutMillis = 500)
        assertTrue(dead is ModelsCatalog.FetchResult.NetworkFailure)
    }

    @Test fun `查询串端点须显式放行`() {
        Served(200, "{}").use { served ->
            val url = URI("http://127.0.0.1:${served.server.address.port}/v1/models?limit=512")
            // 令牌不进 URL 的契约：带查询串的端点默认拒绝构造。
            assertThrows(IllegalArgumentException::class.java) { ModelsCatalog.get(url, emptyMap()) }
            assertEquals(ModelsCatalog.FetchResult.Success("{}"),
                ModelsCatalog.get(url, mapOf("x-api-key" to "sk-key"), permitQueryParams = true))
            assertEquals("sk-key", served.recordedHeaders["x-api-key"])
            assertEquals("/v1/models?limit=512", served.recordedPath)
        }
    }
}
