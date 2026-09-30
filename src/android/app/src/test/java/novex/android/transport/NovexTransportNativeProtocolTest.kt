package novex.android.transport

import novex.android.data.model.AgentContentPart
import novex.android.data.model.LLMError
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMModel
import novex.android.data.model.LLMStreamChunk
import novex.android.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import novex.model.AnthropicMessagesRequest
import novex.model.CompletionStreamRequest
import novex.model.GeminiGenerateContentRequest
import novex.model.StreamChunk
import novex.model.StreamResult
import novex.model.WireProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * P3.1c 原生协议三件套：anthropic/gemini 的请求装配、端点/鉴权路由、
 * 流式时序桥（四条时序契约同款强度）与错误分类。
 */
class NovexTransportNativeProtocolTest {

    private class ScriptedCall(
        private val chunks: List<StreamChunk>,
        private val outcome: StreamResult,
        val received: ConcurrentLinkedQueue<CompletionStreamRequest> = ConcurrentLinkedQueue(),
    ) : NovexTransportProvider.TransportCall {
        override fun cancel() {}
        override fun stream(request: CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
            received.add(request)
            chunks.forEach(onChunk)
            return outcome
        }
    }

    private fun anthropicProvider(
        model: LLMModel = LLMModel.claudeSonnet46,
        call: NovexTransportProvider.TransportCall,
        basePath: String = "https://api.anthropic.com",
        isOAuth: Boolean = false,
    ) = NovexTransportProvider(
        apiKey = "sk-ant-key",
        model = model,
        basePath = basePath,
        instanceId = "inst-anthropic",
        protocol = WireProtocol.ANTHROPIC_MESSAGES,
        isAnthropicOAuth = isOAuth,
        callOpener = { call },
    )

    private fun geminiProvider(
        model: LLMModel = LLMModel.gemini25Flash,
        call: NovexTransportProvider.TransportCall,
    ) = NovexTransportProvider(
        apiKey = "goog-key",
        model = model,
        basePath = "https://generativelanguage.googleapis.com/v1beta",
        instanceId = "inst-gemini",
        protocol = WireProtocol.GEMINI_GENERATE_CONTENT,
        callOpener = { call },
    )

    private fun stream(
        provider: NovexTransportProvider,
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        messages: List<LLMMessage> = listOf(LLMMessage(LLMMessage.Role.USER, "你好")),
    ): List<LLMStreamChunk> = runBlocking {
        (provider as LLMProvider).streamMessage(
            messages = messages,
            systemPrompt = null,
            maxTokens = 512,
            thinkingLevel = thinkingLevel,
        ).toList()
    }

    // ---------------------------------------------------------------------
    // 适配器自身契约
    // ---------------------------------------------------------------------

    @Test
    fun `名字 文本块语义 默认上限按协议对齐被替换实现`() {
        val noop = ScriptedCall(emptyList(), StreamResult.Completed)
        assertEquals("Anthropic", (anthropicProvider(call = noop) as LLMProvider).name)
        assertEquals("Google", (geminiProvider(call = noop) as LLMProvider).name)
        assertEquals(64_000, (anthropicProvider(call = noop) as LLMProvider).defaultMaxOutputTokens)
        assertEquals(16_384, (geminiProvider(call = noop) as LLMProvider).defaultMaxOutputTokens)
        // anthropic/gemini 是有序输出块（content blocks / parts），不是单一整块。
        assertFalse(anthropicProvider(call = noop).streamTextIsMonolithic)
        assertFalse(geminiProvider(call = noop).streamTextIsMonolithic)
        // 无 Images API：imageDelegate 返回 null（调用方回落「不支持生图」）。
        assertNull(anthropicProvider(call = noop).imageDelegate)
        assertNull(geminiProvider(call = noop).imageDelegate)
    }

    // ---------------------------------------------------------------------
    // anthropic：请求装配与端点路由
    // ---------------------------------------------------------------------

    @Test
    fun `anthropic 请求把 system 提到顶层块并把思考形态落参`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("end_turn")), StreamResult.Completed)
        val request = anthropicProvider(call = call).buildWireRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "问")), "系统提示", 777,
            emptyList(), emptyList(), ThinkingLevel.HIGH,
        )
        assertTrue(request is AnthropicMessagesRequest)
        val body = JSONObject(request.encode())
        assertEquals("claude-sonnet-4-6", body.getString("model"))
        assertEquals(777, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("系统提示", body.getJSONArray("system").getJSONObject(0).getString("text"))
        // 4.6 机型 + HIGH → adaptive + effort，不带 temperature。
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getJSONObject("output_config").getString("effort"))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("tools"))
    }

    @Test
    fun `anthropic 旧机型思考走 budget 且强制 temperature 一`() {
        val legacy = LLMModel.claudeHaiku45
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val body = JSONObject(
            anthropicProvider(model = legacy, call = call).buildWireRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "问")), null, 8192,
                emptyList(), emptyList(), ThinkingLevel.MEDIUM,
            ).encode(),
        )
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(8191, body.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(1.0, body.getDouble("temperature"), 0.0)
    }

    @Test
    fun `anthropic 工具定义与代理历史映射 tool_use 与 tool_result`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("查天气"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("call-1", "get_weather", JSONObject().put("city", "上海")))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("call-1", "get_weather", "晴"))),
        )
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val body = JSONObject(anthropicProvider(call = call).buildWireRequest(
            history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF).encode())
        val messages = body.getJSONArray("messages")
        val use = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", use.getString("type"))
        assertEquals("call-1", use.getString("id"))
        assertEquals("上海", use.getJSONObject("input").getString("city"))
        val result = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("call-1", result.getString("tool_use_id"))
        assertEquals("晴", result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `anthropic 端点按官方与自定分线鉴权且 beta 旗标随思考形态`() {
        val thinking = listOf(StreamChunk.Done("end_turn"))
        val official = anthropicProvider(call = ScriptedCall(thinking, StreamResult.Completed))
        val officialRequest = official.buildWireRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "问")), null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH)
        // 官方 API key 线：x-api-key 头 + anthropic-version；无 Bearer。
        val officialHeaders = official.outboundHeaders()
        assertEquals("sk-ant-key", officialHeaders["x-api-key"])
        assertEquals("2023-06-01", officialHeaders["anthropic-version"])
        assertNull(official.bearerTokenOrNull())
        // 4.6 机型 + HIGH → adaptive：effort 旗标。
        assertEquals(listOf("effort-2025-11-24"), official.anthropicBetaFlags(officialRequest as AnthropicMessagesRequest))

        // 自定中继：Bearer 令牌；adaptive 机型 OFF（disabled）按上游口径挂 interleaved 旗标。
        val relay = anthropicProvider(call = ScriptedCall(thinking, StreamResult.Completed),
            basePath = "https://relay.example.com/v1")
        val relayRequest = relay.buildWireRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "问")), null, 512, emptyList(), emptyList(), ThinkingLevel.OFF)
        assertEquals("sk-ant-key", relay.bearerTokenOrNull())
        assertNull(relay.outboundHeaders()["x-api-key"])
        assertEquals(listOf("interleaved-thinking-2025-05-14"),
            relay.anthropicBetaFlags(relayRequest as AnthropicMessagesRequest))
    }

    @Test
    fun `anthropic 增强缓存开关进 ttl 且 OAuth 走 Bearer 与前缀块`() {
        val done = listOf(StreamChunk.Done("end_turn"))
        val withCache = anthropicProvider(call = ScriptedCall(done, StreamResult.Completed))
        withCache.enhancedCache = true
        val request = withCache.buildWireRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "问")), "系统", 256, emptyList(), emptyList(), ThinkingLevel.OFF)
        assertEquals("1h", JSONObject(request.encode()).getJSONArray("system").getJSONObject(0)
            .getJSONObject("cache_control").getString("ttl"))
        assertEquals(listOf("interleaved-thinking-2025-05-14", "extended-cache-ttl-2025-04-11"),
            withCache.anthropicBetaFlags(request as AnthropicMessagesRequest))

        // [P3.1d 净眼挂账 e] OAuth 前缀可注入：公共镜像未配置定制属性时不再
        // assumeTrue 跳过，注入已知前缀跑满断言块（生产前缀仍取 BuildConfig 常量）。
        val oauth = anthropicProvider(call = ScriptedCall(done, StreamResult.Completed), isOAuth = true)
        oauth.oauthSystemPrefixOverride = "Claude Code is Anthropic's official CLI for coding assistance（测试注入前缀）"
        val oauthRequest = oauth.buildWireRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "问")), "尾部提示", 256, emptyList(), emptyList(), ThinkingLevel.OFF)
        val oauthBody = JSONObject(oauthRequest.encode())
        val system = oauthBody.getJSONArray("system")
        assertEquals(2, system.length())
        assertFalse(system.getJSONObject(0).has("cache_control"))
        assertTrue(system.getJSONObject(0).getString("text").contains("Claude Code"))
        assertEquals("尾部提示", system.getJSONObject(1).getString("text"))
        assertEquals("sk-ant-key", oauth.bearerTokenOrNull())
        val headers = oauth.outboundHeaders()
        assertTrue(headers.getValue("User-Agent").startsWith("claude-cli/"))
        assertEquals("js", headers.getValue("X-Stainless-Lang"))
        assertTrue(oauth.anthropicBetaFlags(oauthRequest as AnthropicMessagesRequest).contains("oauth-2025-04-20"))
    }

    @Test
    fun `anthropic URL 剥重复 v1 且模型 id 进 gemini 式路径`() {
        val done = listOf(StreamChunk.Done("end_turn"))
        val relay = anthropicProvider(call = ScriptedCall(done, StreamResult.Completed), basePath = "https://relay.example.com")
        assertEquals("https://relay.example.com/v1/messages",
            relay.completionUrl().toString())
        val withV1 = anthropicProvider(call = ScriptedCall(done, StreamResult.Completed), basePath = "https://relay.example.com/v1")
        assertEquals("https://relay.example.com/v1/messages", withV1.completionUrl().toString())
        val gemini = geminiProvider(call = ScriptedCall(done, StreamResult.Completed))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            gemini.completionUrl().toString())
    }

    // ---------------------------------------------------------------------
    // gemini：请求装配
    // ---------------------------------------------------------------------

    @Test
    fun `gemini 请求映射 contents systemInstruction 与思考配置`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("end_turn")), StreamResult.Completed)
        val request = geminiProvider(call = call).buildWireRequest(
            listOf(
                LLMMessage(LLMMessage.Role.USER, "问"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "答"),
                LLMMessage(LLMMessage.Role.USER, "再问"),
            ), "系统提示", 333, emptyList(), emptyList(), ThinkingLevel.MEDIUM,
        )
        assertTrue(request is GeminiGenerateContentRequest)
        val body = JSONObject(request.encode())
        val config = body.getJSONObject("generationConfig")
        assertEquals(333, config.getInt("maxOutputTokens"))
        // 2.5 flash + MEDIUM → thinkingBudget 4096 + includeThoughts（golden 快照行为）。
        assertEquals(4096, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertTrue(config.getJSONObject("thinkingConfig").getBoolean("includeThoughts"))
        assertEquals("系统提示", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
    }

    @Test
    fun `gemini 三系模型回放 thoughtSignature 且端点用 x-goog-api-key`() {
        val gemini3 = LLMModel("gemini-3-pro-preview", "Gemini 3 Pro", "Google", supportsReasoning = true)
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("gemini_1", "probe", JSONObject(), thoughtSignature = "sig-9"))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("gemini_1", "probe", "结果"))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("继续"))),
        )
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val provider = NovexTransportProvider(
            apiKey = "goog-key", model = gemini3,
            basePath = "https://generativelanguage.googleapis.com/v1beta",
            protocol = WireProtocol.GEMINI_GENERATE_CONTENT, callOpener = { call })
        val request = provider.buildWireRequest(history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF)
        val body = JSONObject(request.encode())
        val callPart = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("sig-9", callPart.getString("thoughtSignature"))
        assertEquals("probe", callPart.getJSONObject("functionCall").getString("name"))
        // 鉴权走 x-goog-api-key 头（等价 ?key=，密钥不进 URL）。
        assertEquals("goog-key", provider.outboundHeaders()["x-goog-api-key"])
        assertNull(provider.bearerTokenOrNull())
    }

    // ---------------------------------------------------------------------
    // 流式时序桥（四条时序契约同款强度）
    // ---------------------------------------------------------------------

    @Test
    fun `anthropic 块族按上游时序转发且思考与签名齐备`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.Usage(25, 1),
                StreamChunk.ThinkingDelta("想"),
                StreamChunk.TextDelta("答"),
                StreamChunk.ToolCallDelta(1, "toolu_1", "get_weather", ""),
                StreamChunk.ToolCallDelta(1, null, null, "{\"city\":"),
                StreamChunk.ToolCallDelta(1, null, null, "\"上海\"}"),
                StreamChunk.Usage(null, 9),
                StreamChunk.Done("tool_use"),
            ),
            StreamResult.Completed,
        )
        val chunks = stream(anthropicProvider(call = call))
        assertEquals(11, chunks.size)
        assertEquals(LLMStreamChunk.Started, chunks[0])
        // 用量块先于文本（message_start 用量先到）。
        assertEquals(LLMStreamChunk.Usage(novex.android.data.model.LLMUsage(25, 1, latestContextTokens = 25)), chunks[1])
        assertEquals(LLMStreamChunk.ThinkingDelta("想"), chunks[2])
        assertEquals(LLMStreamChunk.Text("答"), chunks[3])
        assertEquals(LLMStreamChunk.ToolUseStart("toolu_1", "get_weather"), chunks[4])
        assertEquals(LLMStreamChunk.ToolInputDelta("toolu_1", "{\"city\":"), chunks[5])
        assertEquals(LLMStreamChunk.ToolInputDelta("toolu_1", "{\"city\":\"上海\"}"), chunks[6])
        assertEquals(LLMStreamChunk.Usage(novex.android.data.model.LLMUsage(0, 9, latestContextTokens = 0)), chunks[7])
        assertEquals(LLMStreamChunk.ReasoningContent("想"), chunks[8])
        assertEquals(LLMStreamChunk.Finished("tool_use"), chunks[9])
        val complete = chunks[10] as LLMStreamChunk.ToolCallComplete
        assertEquals("toolu_1", complete.id)
        assertEquals("上海", complete.args.getString("city"))
    }

    @Test
    fun `gemini 签名透传到 ToolCallComplete 且媒体块解码透传`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("答"),
                StreamChunk.ToolCallDelta(0, "gemini_1", "probe", "{}", "sig-7"),
                StreamChunk.MediaAttachment("image/png", "aGk="),
                StreamChunk.Done("end_turn"),
            ),
            StreamResult.Completed,
        )
        val chunks = stream(geminiProvider(call = call))
        assertEquals(7, chunks.size)
        assertEquals(LLMStreamChunk.Started, chunks[0])
        assertEquals(LLMStreamChunk.Text("答"), chunks[1])
        assertEquals(LLMStreamChunk.ToolUseStart("gemini_1", "probe"), chunks[2])
        assertEquals(LLMStreamChunk.ToolInputDelta("gemini_1", "{}"), chunks[3])
        val media = chunks[4] as LLMStreamChunk.MediaAttachment
        assertEquals("image/png", media.attachment.mimeType)
        assertEquals("hi", String(media.attachment.data, Charsets.US_ASCII))
        assertEquals(LLMStreamChunk.Finished("end_turn"), chunks[5])
        val complete = chunks[6] as LLMStreamChunk.ToolCallComplete
        assertEquals("sig-7", complete.thoughtSignature)
    }

    @Test
    fun `LAN 明文中继预检失败为确定性供应商错误且不进瞬态重试`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("end_turn")), StreamResult.Completed)
        val lanAnthropic = NovexTransportProvider(
            apiKey = "k", model = LLMModel.claudeSonnet46,
            basePath = "http://192.168.1.10:8080",
            protocol = WireProtocol.ANTHROPIC_MESSAGES, callOpener = { call })
        val error = runCatching { stream(lanAnthropic) }.exceptionOrNull()
        // 确定性 ProviderError（中文可读、指明仅支持 https/本机回环），不是瞬态网络错误。
        assertTrue("应为供应商错误，实际 $error", error is LLMError.ProviderError)
        assertTrue(error!!.message!!.contains("仅支持 https"))
        assertTrue(error.message!!.contains("127.0.0.1"))
        // 预检在建请求/开调用之前拦截：传输面从未被触及。
        assertTrue(call.received.isEmpty())

        val lanGemini = NovexTransportProvider(
            apiKey = "k", model = LLMModel.gemini25Flash,
            basePath = "http://192.168.1.10:8080/v1beta",
            protocol = WireProtocol.GEMINI_GENERATE_CONTENT, callOpener = { call })
        val geminiError = runCatching { stream(lanGemini) }.exceptionOrNull()
        assertTrue(geminiError is LLMError.ProviderError)
        assertTrue(geminiError!!.message!!.contains("仅支持 https"))
    }

    @Test
    fun `anthropic 流中 overloaded 错误归瞬态进重试链`() {
        val provider = anthropicProvider(call = ScriptedCall(emptyList(), StreamResult.Completed))
        assertTrue(provider.errorOf(StreamChunk.Failure("Overloaded", code = "overloaded_error")) is LLMError.TransientError)
        assertTrue(provider.errorOf(StreamChunk.Failure("限流", code = "rate_limit_error")) is LLMError.RateLimited)
        assertTrue(provider.errorOf(StreamChunk.Failure("无效密钥", code = "authentication_error")) is LLMError.InvalidApiKey)
    }

    @Test
    fun `anthropic 工具结果内嵌图片补发进 tool_result 内容块`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("截图"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("call-1", "take_screenshot", JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult(
                    "call-1", "take_screenshot", "已截图",
                    imageData = byteArrayOf(1, 2, 3), imageMimeType = "image/png"))),
        )
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val body = JSONObject(anthropicProvider(call = call).buildWireRequest(
            history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF).encode())
        // messages: [0]=user 文本、[1]=assistant tool_use、[2]=user tool_result。
        val toolResult = body.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        val content = toolResult.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("已截图", content.getJSONObject(0).getString("text"))
        // 图片块在文本之后（对齐被替换 AnthropicProvider 的语义），字节走 ImageBudget 兜底后 base64。
        val image = content.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/png", image.getJSONObject("source").getString("media_type"))
        assertTrue(image.getJSONObject("source").getString("data").isNotEmpty())
    }

    @Test
    fun `孤儿 tool_result 过一条 user 即失效不再跨轮配对`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("跑工具"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                AgentContentPart.ToolUse("call-1", "long_task", JSONObject()))),
            // 第一条 user：正常配对。
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("call-1", "long_task", "中间结果"))),
            // 中间又过了一条 user（纯文本）：配对窗口过期。
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("插一句话"))),
            // 迟到的旧批结果：必须按孤儿丢弃（被替换实现同款「过一条 user 即失效」）。
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("call-1", "long_task", "迟到结果"))),
        )
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val body = JSONObject(anthropicProvider(call = call).buildWireRequest(
            history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF).encode())
        val messages = body.getJSONArray("messages")
        var toolResults = 0
        for (index in 0 until messages.length()) {
            for (part in messages.getJSONObject(index).getJSONArray("content")) {
                val block = part as org.json.JSONObject
                if (block.optString("type") == "tool_result") toolResults++
            }
        }
        assertEquals("只有第一条 user 的结果配对成功，迟到结果被丢弃", 1, toolResults)
    }

    @Test
    fun `anthropic 用量块携带缓存计量映射进 LLMUsage`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.Usage(25, 1, 1024, 2048),
                StreamChunk.TextDelta("答"),
                StreamChunk.Done("end_turn"),
            ),
            StreamResult.Completed,
        )
        val chunks = stream(anthropicProvider(call = call))
        val usage = chunks.filterIsInstance<LLMStreamChunk.Usage>().single().usage
        assertEquals(25, usage.inputTokens)
        assertEquals(1, usage.outputTokens)
        assertEquals(1024, usage.cacheCreationInputTokens)
        assertEquals(2048, usage.cacheReadInputTokens)
        assertEquals(25, usage.latestContextTokens)
    }

    @Test
    fun `anthropic 与 gemini 静默空完成按瞬态失败抛错`() {
        val silentAnthropic = anthropicProvider(call = ScriptedCall(emptyList(), StreamResult.Completed))
        val error = runCatching { stream(silentAnthropic) }.exceptionOrNull()
        assertTrue("应为瞬态错误，实际 $error", error is LLMError.TransientError)
        // 有收尾原因的空响应不拦（与上游判据一致）。
        val finishedEmpty = anthropicProvider(call = ScriptedCall(listOf(StreamChunk.Done("end_turn")), StreamResult.Completed))
        assertTrue(runCatching { stream(finishedEmpty) }.isSuccess)
    }

    @Test
    fun `断流冲出工具调用不发 Finished 且 anthropic 线被空完成守卫拦截`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("半截"),
                StreamChunk.ToolCallDelta(1, "toolu_9", "long_task", "{\"step\":"),
            ),
            StreamResult.NetworkFailure,
        )
        val chunks = stream(anthropicProvider(call = call))
        // 断流：Started + Text + ToolUseStart + ToolInputDelta + ToolCallComplete，无 Finished。
        assertEquals(5, chunks.size)
        assertEquals(LLMStreamChunk.Started, chunks[0])
        assertEquals(LLMStreamChunk.Text("半截"), chunks[1])
        assertEquals(LLMStreamChunk.ToolUseStart("toolu_9", "long_task"), chunks[2])
        assertEquals(LLMStreamChunk.ToolInputDelta("toolu_9", "{\"step\":"), chunks[3])
        val complete = chunks[4] as LLMStreamChunk.ToolCallComplete
        assertEquals(0, complete.args.length())
        // 上游同款：有内容的截断流不触发空完成守卫，静默截断交由调用方裁决。
    }

    @Test
    fun `非流式入口拼回整体响应含媒体附件`() = runBlocking {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("你"),
                StreamChunk.TextDelta("好"),
                StreamChunk.MediaAttachment("image/png", "aGk="),
                StreamChunk.Usage(12, 2),
                StreamChunk.Done("end_turn"),
            ),
            StreamResult.Completed,
        )
        val response = (geminiProvider(call = call) as LLMProvider).sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "画一张")), null, 64,
        )
        assertEquals("你好", response.text)
        assertEquals("end_turn", response.stopReason)
        assertEquals(12, response.usage!!.inputTokens)
        assertEquals(1, response.mediaAttachments.size)
        assertEquals("image/png", response.mediaAttachments.single().mimeType)
    }

    // ---------------------------------------------------------------------
    // 真实线路端到端（MockWebServer，经 wireEndpoint → ChatCompletionCall 全链）
    // ---------------------------------------------------------------------

    @Test fun `anthropic 中继线上真实送达鉴权头与请求体并解码回块`() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}\n\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n"))
            server.start()
            val provider = NovexTransportProvider(
                apiKey = "relay-key", model = LLMModel.claudeSonnet46,
                basePath = server.url("/").toString().trimEnd('/'),
                protocol = WireProtocol.ANTHROPIC_MESSAGES)
            val chunks = runBlocking {
                (provider as LLMProvider).streamMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER, "问")), null, 64).toList()
            }
            val recorded = server.takeRequest()
            // 本机 http 中继 → Bearer；协议版本头随行。
            assertEquals("Bearer relay-key", recorded.getHeader("Authorization"))
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("claude-sonnet-4-6", body.getString("model"))
            assertEquals(64, body.getInt("max_tokens"))
            assertEquals(listOf<LLMStreamChunk>(
                LLMStreamChunk.Started, LLMStreamChunk.Text("好"),
                LLMStreamChunk.Usage(novex.android.data.model.LLMUsage(0, 2)),
                LLMStreamChunk.Finished("end_turn")), chunks)
        }
    }

    @Test fun `gemini 线上密钥走 x-goog-api-key 头且 URL 带 alt sse`() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"答\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":4,\"candidatesTokenCount\":1}}\n\n"))
            server.start()
            val provider = NovexTransportProvider(
                apiKey = "goog-key", model = LLMModel.gemini25Flash,
                basePath = server.url("/v1beta").toString().trimEnd('/'),
                protocol = WireProtocol.GEMINI_GENERATE_CONTENT)
            val chunks = runBlocking {
                (provider as LLMProvider).streamMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER, "问")), null, 64).toList()
            }
            val recorded = server.takeRequest()
            assertEquals("goog-key", recorded.getHeader("x-goog-api-key"))
            assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse", recorded.path)
            assertEquals(listOf<LLMStreamChunk>(
                LLMStreamChunk.Started, LLMStreamChunk.Text("答"),
                LLMStreamChunk.Usage(novex.android.data.model.LLMUsage(4, 1, latestContextTokens = 4)),
                LLMStreamChunk.Finished("end_turn")), chunks)
        }
    }
}
