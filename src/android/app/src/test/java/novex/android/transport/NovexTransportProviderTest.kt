package novex.android.transport

import novex.android.data.model.AgentContentPart
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.LLMError
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMModel
import novex.android.data.model.LLMStreamChunk
import novex.android.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ImageDegradationLearning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import novex.model.CompletionStreamRequest
import novex.model.StreamChunk
import novex.model.StreamResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P3.1b 适配器三件套：请求映射、流式时序桥、错误分类。
 * 传输层用可注入桩（TransportCall）钉时序，不打真实网络。
 */
class NovexTransportProviderTest {

    // ---------------------------------------------------------------------
    // 测试桩
    // ---------------------------------------------------------------------

    private class ScriptedCall(
        private val chunks: List<StreamChunk>,
        private val outcome: StreamResult,
    ) : NovexTransportProvider.TransportCall {
        val cancelled = AtomicBoolean(false)
        override fun cancel() = cancelled.set(true)
        override fun stream(request: CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
            chunks.forEach(onChunk)
            return outcome
        }
    }

    private class BlockingCall(private val entered: CountDownLatch) : NovexTransportProvider.TransportCall {
        val cancelled = AtomicBoolean(false)
        override fun cancel() = cancelled.set(true)
        override fun stream(request: CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
            entered.countDown()
            // 模拟阻塞中的阻塞读：cancel 到来前不返回。
            var waited = 0
            while (!cancelled.get() && waited < 10_000) {
                Thread.sleep(20); waited += 20
            }
            return StreamResult.Cancelled
        }
    }

    private fun provider(
        model: LLMModel = LLMModel("test-model", "Test Model", "OpenAI"),
        call: NovexTransportProvider.TransportCall,
        basePath: String = "https://relay.example.com/v1",
    ) = NovexTransportProvider(
        apiKey = "relay-key",
        model = model,
        basePath = basePath,
        instanceId = "instance-1",
        callOpener = { call },
    )

    private fun stream(
        provider: NovexTransportProvider,
        messages: List<LLMMessage> = listOf(LLMMessage(LLMMessage.Role.USER, "你好")),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): List<LLMStreamChunk> = runBlocking {
        (provider as LLMProvider).streamMessage(
            messages = messages,
            systemPrompt = null,
            maxTokens = 512,
            thinkingLevel = thinkingLevel,
        ).toList()
    }

    // ---------------------------------------------------------------------
    // 请求映射
    // ---------------------------------------------------------------------

    @Test
    fun `纯文本消息映射出 system-user 序列与基本请求形状`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("stop")), StreamResult.Completed)
        val provider = provider(call = call)
        val request = provider.buildStreamRequest(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "保留\n\"原文\"")),
            systemPrompt = "系统提示",
            maxTokens = 777,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )
        val body = JSONObject(request.encode())
        assertEquals("test-model", body.getString("model"))
        // [T-provider-onboarding] token 上限键白名单制：中转（非白名单）主机走
        // wire() 默认 max_tokens——智谱/DeepSeek 等只认这个键。
        assertEquals(777, body.getInt("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).get("role"))
        assertEquals("系统提示", messages.getJSONObject(0).get("content"))
        assertEquals("user", messages.getJSONObject(1).get("role"))
        assertEquals("保留\n\"原文\"", messages.getJSONObject(1).get("content"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test
    fun `工具定义映射为 OpenAI function 形状`() {
        val tool = AgentToolDefinition(
            name = "save_card",
            description = "保存卡片",
            parameters = mapOf(
                "title" to novex.android.data.model.AgentToolParam(
                    type = "string", description = "标题",
                ),
            ),
            required = listOf("title"),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "建卡")), null, 128, emptyList(), listOf(tool), ThinkingLevel.OFF,
            ).encode(),
        )
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("save_card", function.getString("name"))
        assertEquals("保存卡片", function.getString("description"))
        assertEquals("object", function.getJSONObject("parameters").getString("type"))
        assertTrue(function.getJSONObject("parameters").getJSONObject("properties").has("title"))
    }

    @Test
    fun `代理循环工具历史映射为 tool_calls 与 tool 结果配对`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("查天气"))),
            LLMMessage(
                LLMMessage.Role.ASSISTANT,
                "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("call-1", "get_weather", JSONObject().put("city", "上海")),
                ),
            ),
            LLMMessage(
                LLMMessage.Role.USER,
                "",
                contentParts = listOf(AgentContentPart.ToolResult("call-1", "get_weather", "晴 28°C")),
            ),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF).encode(),
        )
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        val toolCall = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call-1", toolCall.getString("id"))
        assertEquals("get_weather", toolCall.getJSONObject("function").getString("name"))
        assertEquals(JSONObject().put("city", "上海").toString(), toolCall.getJSONObject("function").getString("arguments"))
        val toolResult = messages.getJSONObject(2)
        assertEquals("tool", toolResult.getString("role"))
        assertEquals("call-1", toolResult.getString("tool_call_id"))
        assertEquals("晴 28°C", toolResult.getString("content"))
    }

    @Test
    fun `跨消息重复工具调用 id 被改名以通过网关查重`() {
        val repeatedUse = AgentContentPart.ToolUse("call-dup", "probe", JSONObject())
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(repeatedUse)),
            LLMMessage(LLMMessage.Role.USER, content = "", contentParts = listOf(AgentContentPart.ToolResult("call-dup", "probe", "a"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(repeatedUse)),
            LLMMessage(LLMMessage.Role.USER, content = "", contentParts = listOf(AgentContentPart.ToolResult("call-dup", "probe", "b"))),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(history, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF).encode(),
        )
        val ids = mutableListOf<String>()
        val messages = body.getJSONArray("messages")
        for (i in 0 until messages.length()) {
            messages.getJSONObject(i).optJSONArray("tool_calls")?.let { calls ->
                ids += calls.getJSONObject(0).getString("id")
            }
        }
        assertEquals(2, ids.size)
        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun `思考等级经规则解析器落入请求体`() {
        val reasoningModel = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true)
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val request = provider(model = reasoningModel, call = call).buildStreamRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "想清楚再答")), null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
        )
        assertEquals("high", JSONObject(request.encode()).getString("reasoning_effort"))
    }

    @Test
    fun `必带思考模型回放 assistant reasoning_content 且空档补空串`() {
        val alwaysReasons = LLMModel("deepseek-v4", "DeepSeek V4", "OpenAI", supportsReasoning = true)
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(model = alwaysReasons, call = call).buildStreamRequest(
                listOf(
                    LLMMessage(LLMMessage.Role.ASSISTANT, "上一轮", reasoningContent = "上一轮的思考"),
                    LLMMessage(LLMMessage.Role.ASSISTANT, "无思考记录的一轮"),
                    LLMMessage(LLMMessage.Role.USER, "继续"),
                ),
                null, 128, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        val first = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("上一轮的思考", first.getString("reasoning_content"))
        val second = body.getJSONArray("messages").getJSONObject(1)
        assertEquals("", second.getString("reasoning_content"))
    }

    @Test
    fun `普通模型关思考时不回放 reasoning_content`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "上一轮", reasoningContent = "不应回放")),
                null, 128, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertNull(body.getJSONArray("messages").getJSONObject(0).opt("reasoning_content"))
    }

    @Test
    fun `视觉模型把最后一条 user 的图片部件编码为 data URL`() {
        val visionModel = LLMModel("vision-model", "Vision", "OpenAI", inputModalities = listOf("text", "image"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(model = visionModel, call = call).buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "看图")),
                null, 128,
                imageParts = listOf(LLMMessage.ImagePart(png, "image/png")),
                tools = emptyList(), thinkingLevel = ThinkingLevel.OFF,
            ).encode(),
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val imageUrl = content.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue(imageUrl.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `无视觉输入的模型乐观发送像素 仅学习降级后才占位顶替`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val request = listOf(LLMMessage(LLMMessage.Role.USER, "看图"))
        val parts = listOf(LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png"))

        // 未学习降级：即使模型未声明视觉输入也真实发送像素（乐观发送策略）。
        val optimistic = JSONObject(
            provider(call = call).buildStreamRequest(request, null, 128, parts, emptyList(), ThinkingLevel.OFF).encode(),
        )
        assertTrue(optimistic.getJSONArray("messages").getJSONObject(0).get("content") is org.json.JSONArray)

        // 已学习降级（端点明确拒绝过图片）：占位文本顶替像素。
        ImageDegradationLearning.imageDegradedModels.add("test-model")
        try {
            val degraded = JSONObject(
                provider(call = call).buildStreamRequest(request, null, 128, parts, emptyList(), ThinkingLevel.OFF).encode(),
            )
            val message = degraded.getJSONArray("messages").getJSONObject(0)
            assertTrue(message.get("content") is String)
            assertTrue(message.getString("content").contains("不支持图片输入"))
        } finally {
            ImageDegradationLearning.imageDegradedModels.remove("test-model")
        }
    }

    @Test
    fun `OpenRouter 端点不带 stream_options`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val request = provider(call = call, basePath = "https://openrouter.ai/api/v1").buildStreamRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
        )
        assertFalse(JSONObject(request.encode()).has("stream_options"))
    }

    @Test
    fun `token 上限键按主机白名单与模型族选择`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "问"))

        // OpenRouter：默认键 max_tokens（网关收 max_completion_tokens 会拒）。
        val router = JSONObject(
            provider(call = call, basePath = "https://openrouter.ai/api/v1").buildStreamRequest(
                messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals(64, router.getInt("max_tokens"))
        assertFalse(router.has("max_completion_tokens"))

        // 官方 OpenAI：白名单成员，o 系/gpt-5 收 max_tokens 会 400 → 发
        // max_completion_tokens，不带 max_tokens。
        val official = JSONObject(
            provider(call = call, basePath = "https://api.openai.com/v1").buildStreamRequest(
                messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals(64, official.getInt("max_completion_tokens"))
        assertFalse(official.has("max_tokens"))

        // 中转 + 普通机型：一律 wire() 默认 max_tokens——OpenAI 专用的
        // max_completion_tokens 发给智谱/DeepSeek 等只认 max_tokens 的服务
        // 会整单 400（[T-provider-onboarding] 根因①）。
        val relay = JSONObject(
            provider(call = call, basePath = "https://relay.example.com/v1").buildStreamRequest(
                messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals(64, relay.getInt("max_tokens"))
        assertFalse(relay.has("max_completion_tokens"))

        // 智谱官方直连：同上——max_tokens，绝无 max_completion_tokens。
        val zhipu = JSONObject(
            provider(call = call, basePath = "https://open.bigmodel.cn/api/paas/v4").buildStreamRequest(
                messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals(64, zhipu.getInt("max_tokens"))
        assertFalse(zhipu.has("max_completion_tokens"))
    }

    /**
     * [PR#83 净眼] 模型族信号：中转前端转投 OpenAI 的 o 系 / gpt-5 机型对
     * max_tokens 同样整单 400（PR#63 事故，1df2dbae）——白名单外补模型族
     * 判定（复用思考座次表的 o-star 与 gpt-5-star glob 口径），无论 host
     * 一律发 max_completion_tokens；glm/deepseek 等不命中，根因①修复面不变。
     */
    @Test
    fun `中转上的 OpenAI 原生机型按模型族发 max_completion_tokens`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val messages = listOf(LLMMessage(LLMMessage.Role.USER, "问"))

        for (openAiNative in listOf("o3", "o4-mini", "gpt-5.5")) {
            val body = JSONObject(
                provider(model = LLMModel(openAiNative, openAiNative, "OpenAI"), call = call,
                    basePath = "https://relay.example.com/v1").buildStreamRequest(
                    messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
                ).encode(),
            )
            assertEquals("$openAiNative 应发 max_completion_tokens", 64, body.getInt("max_completion_tokens"))
            assertFalse("$openAiNative 不带 max_tokens", body.has("max_tokens"))
        }

        // 同 host 的 glm 机型不命中模型族——max_tokens，防误伤智谱根因①。
        val glm = JSONObject(
            provider(model = LLMModel("glm-5.3", "GLM-5.3", "智谱"), call = call,
                basePath = "https://relay.example.com/v1").buildStreamRequest(
                messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals(64, glm.getInt("max_tokens"))
        assertFalse(glm.has("max_completion_tokens"))
    }

    /**
     * [T-provider-onboarding] 智谱根因②的请求侧守护：/api/paas/v4 是终态
     * 路径，chat 端点 = 基址原样 + /chat/completions——不许出现任何 /v1 注入。
     */
    @Test
    fun `智谱基址的 chat 端点保持完整路径不被追加 v1`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val zhipu = provider(call = call, basePath = "https://open.bigmodel.cn/api/paas/v4")
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            zhipu.completionUrl().toString(),
        )
    }

    /**
     * [T-provider-onboarding] 智谱根因④：GLM 直连思考席位——智谱官方 host +
     * 思考开 → thinking:{"type":"enabled"} + 根级 reasoning_effort 兄弟键；
     * 思考关 → disabled 被拒（5.3 系文档原文），按官方迁移指引发
     * enabled + reasoning_effort=low 模拟关闭——省略会落回厂商默认
     * enabled+max（最深最贵），与关闭意图相反。
     */
    @Test
    fun `智谱主机思考开时请求体含 thinking enabled 与根级 reasoning_effort`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val glm = LLMModel(
            "glm-5.3",
            "GLM-5.3",
            "智谱",
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
        )
        val body = JSONObject(
            provider(model = glm, call = call, basePath = "https://open.bigmodel.cn/api/paas/v4")
                .buildStreamRequest(
                    listOf(LLMMessage(LLMMessage.Role.USER, "问")),
                    null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
                ).encode(),
        )
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getString("reasoning_effort"))
        // 关档 = 模拟关闭形态：enabled + low（disabled 会报错，省略落回 max）。
        val off = JSONObject(
            provider(model = glm, call = call, basePath = "https://open.bigmodel.cn/api/paas/v4")
                .buildStreamRequest(
                    listOf(LLMMessage(LLMMessage.Role.USER, "问")),
                    null, 512, emptyList(), emptyList(), ThinkingLevel.OFF,
                ).encode(),
        )
        assertEquals("enabled", off.getJSONObject("thinking").getString("type"))
        assertEquals("low", off.getString("reasoning_effort"))
    }

    /** GLM 席位只设在智谱官方 host：中转上的 glm 仍走通用线（根因④不越界）。 */
    @Test
    fun `中转主机上的 glm 不命中智谱思考席位`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val glm = LLMModel("glm-5.3", "GLM-5.3", "中转", supportsReasoning = true)
        val body = JSONObject(
            provider(model = glm, call = call, basePath = "https://relay.example.com/v1")
                .buildStreamRequest(
                    listOf(LLMMessage(LLMMessage.Role.USER, "问")),
                    null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
                ).encode(),
        )
        assertFalse(body.has("thinking"))
    }

    /**
     * [PR#83 净眼] host 判定统一严格 URI 相等：形如
     * open.bigmodel.cn.relay.tld 的伪造域中转子串包含会误命中 GLM 思考席位。
     */
    @Test
    fun `伪造智谱域名的中转不命中 GLM 思考席位`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val glm = LLMModel("glm-5.3", "GLM-5.3", "中转", supportsReasoning = true)
        val body = JSONObject(
            provider(model = glm, call = call, basePath = "https://open.bigmodel.cn.relay.tld/v1")
                .buildStreamRequest(
                    listOf(LLMMessage(LLMMessage.Role.USER, "问")),
                    null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
                ).encode(),
        )
        assertFalse("伪造域不得命中 GLM 思考席位", body.has("thinking"))
        // 伪造域同样不在 host 白名单、glm 不命中模型族——token 键走 max_tokens。
        assertEquals(512, body.getInt("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun `音频部件映射为 input_audio 块`() {
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "听", audioParts = listOf(LLMMessage.AudioPart("wav", "YXVkaW8=")))),
                null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val audio = content.getJSONObject(1)
        assertEquals("input_audio", audio.getString("type"))
        assertEquals("wav", audio.getJSONObject("input_audio").getString("format"))
        assertEquals("YXVkaW8=", audio.getJSONObject("input_audio").getString("data"))
    }

    @Test
    fun `超长工具调用 id 确定性折叠且结果按折叠后 id 配对`() {
        val longId = "call_" + "x".repeat(80) // 85 字符，超过 64 上限
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(AgentContentPart.ToolUse(longId, "probe", JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, content = "", contentParts = listOf(AgentContentPart.ToolResult(longId, "probe", "结果"))),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(history, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF).encode(),
        )
        val messages = body.getJSONArray("messages")
        val folded = messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertTrue("折叠后 id 应不超过 64 字符，实际 ${folded.length}", folded.length <= 64)
        assertTrue(folded.startsWith("call_"))
        // 工具结果按折叠后的 id 配对，而非原始超长 id。
        assertEquals(folded, messages.getJSONObject(1).getString("tool_call_id"))
        assertEquals("结果", messages.getJSONObject(1).getString("content"))
        // 确定性：同一历史两次装配得到同一折叠 id（SHA-256 摘要，无随机成分）。
        val again = JSONObject(
            provider(call = call).buildStreamRequest(history, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF).encode(),
        )
        assertEquals(folded, again.getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun `配不上调用批次的孤儿工具结果被丢弃`() {
        val history = listOf(
            LLMMessage(
                LLMMessage.Role.USER, content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("ghost-id", "probe", "孤儿结果"),
                    AgentContentPart.Text("正文"),
                ),
            ),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val body = JSONObject(
            provider(call = call).buildStreamRequest(history, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF).encode(),
        )
        // 孤儿 tool 块不发（发出去整单被网关 400），user 正文保留。
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("正文", messages.getJSONObject(0).getString("content"))
    }

    // ---------------------------------------------------------------------
    // 流式时序桥
    // ---------------------------------------------------------------------

    @Test
    fun `文本思考工具用量按上游时序转发且 Finished 先于 ToolCallComplete`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("答"),
                StreamChunk.ThinkingDelta("想"),
                StreamChunk.ToolCallDelta(0, "call-1", "get_weather", "{\"city\":"),
                StreamChunk.ToolCallDelta(0, null, null, "\"上海\"}"),
                StreamChunk.Usage(30, 7),
                StreamChunk.Done("tool_calls"),
            ),
            StreamResult.Completed,
        )
        val chunks = stream(provider(call = call))
        // org.json 的 JSONObject 不重写 equals，ToolCallComplete 逐字段断言。
        assertEquals(10, chunks.size)
        assertEquals(LLMStreamChunk.Started, chunks[0])
        assertEquals(LLMStreamChunk.Text("答"), chunks[1])
        assertEquals(LLMStreamChunk.ThinkingDelta("想"), chunks[2])
        assertEquals(LLMStreamChunk.ToolUseStart("call-1", "get_weather"), chunks[3])
        assertEquals(LLMStreamChunk.ToolInputDelta("call-1", "{\"city\":"), chunks[4])
        assertEquals(LLMStreamChunk.ToolInputDelta("call-1", "{\"city\":\"上海\"}"), chunks[5])
        assertEquals(LLMStreamChunk.Usage(novex.android.data.model.LLMUsage(30, 7, latestContextTokens = 30)), chunks[6])
        assertEquals(LLMStreamChunk.ReasoningContent("想"), chunks[7])
        assertEquals(LLMStreamChunk.Finished("tool_calls"), chunks[8])
        val complete = chunks[9] as LLMStreamChunk.ToolCallComplete
        assertEquals("call-1", complete.id)
        assertEquals("get_weather", complete.name)
        assertEquals(1, complete.args.length())
        assertEquals("上海", complete.args.getString("city"))
    }

    @Test
    fun `HTTP 失败在 Started 之前以 InvalidApiKey 收流`() {
        val call = ScriptedCall(
            listOf(StreamChunk.Failure("HTTP 401", 401, "authentication", null, null)),
            StreamResult.Failed,
        )
        val outcome = runCatching { stream(provider(call = call)) }
        val error = outcome.exceptionOrNull() as LLMError
        assertTrue(error is LLMError.InvalidApiKey)
        assertTrue(outcome.exceptionOrNull() !is kotlin.coroutines.cancellation.CancellationException)
    }

    @Test
    fun `流中 error 对象在已发块之后以 ProviderError 收流`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("开头"),
                StreamChunk.Failure("额度耗尽", code = "insufficient_quota"),
            ),
            StreamResult.Failed,
        )
        val outcome = runCatching { stream(provider(call = call)) }
        val error = outcome.exceptionOrNull() as LLMError
        assertTrue(error is LLMError.ProviderError)
        assertTrue(error.message!!.contains("额度耗尽"))
    }

    @Test
    fun `5xx 服务端错误归为瞬态可重试`() {
        for (status in listOf(500, 502, 503, 504, 529)) {
            val call = ScriptedCall(
                listOf(StreamChunk.Failure("HTTP $status", status, "service", null, null)),
                StreamResult.Failed,
            )
            val error = runCatching { stream(provider(call = call)) }.exceptionOrNull() as LLMError
            assertTrue("status=$status 应为瞬态错误", error is LLMError.TransientError)
        }
    }

    @Test
    fun `429 归为限流 404 归为供应商错误`() {
        val limited = runCatching {
            stream(provider(call = ScriptedCall(listOf(StreamChunk.Failure("HTTP 429", 429, "rate_limit", null, "30")), StreamResult.Failed)))
        }.exceptionOrNull() as LLMError
        assertTrue(limited is LLMError.RateLimited)

        val notFound = runCatching {
            stream(provider(call = ScriptedCall(listOf(StreamChunk.Failure("HTTP 404", 404, "request", null, null)), StreamResult.Failed)))
        }.exceptionOrNull() as LLMError
        assertTrue(notFound is LLMError.ProviderError)
    }

    @Test
    fun `无终止信号断流冲出工具调用但不发 Finished`() {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("半截"),
                StreamChunk.ToolCallDelta(0, "call-9", "long_task", "{\"step\":"),
            ),
            StreamResult.NetworkFailure,
        )
        val chunks = stream(provider(call = call))
        assertEquals(5, chunks.size)
        assertEquals(LLMStreamChunk.Started, chunks[0])
        assertEquals(LLMStreamChunk.Text("半截"), chunks[1])
        assertEquals(LLMStreamChunk.ToolUseStart("call-9", "long_task"), chunks[2])
        assertEquals(LLMStreamChunk.ToolInputDelta("call-9", "{\"step\":"), chunks[3])
        val complete = chunks[4] as LLMStreamChunk.ToolCallComplete
        assertEquals("call-9", complete.id)
        assertEquals("long_task", complete.name)
        // 参数 JSON 不完整时按上游惯例回落为空对象。
        assertEquals(0, complete.args.length())
    }

    @Test
    fun `读超时归为网络错误`() {
        val call = ScriptedCall(emptyList(), StreamResult.TimedOut)
        val error = runCatching { stream(provider(call = call)) }.exceptionOrNull() as LLMError
        assertTrue(error is LLMError.NetworkError)
    }

    @Test
    fun `慢消费者大量块零丢失且顺序保持（背压契约）`() = runBlocking {
        // 生产快、消费慢：300 块远超 callbackFlow 默认 64 缓冲，生产者必然撞满。
        // trySendBlocking 维持 ChatCompletionCall.stream 的背压契约（慢消费者
        // 放慢读取而非丢块）；丢任何 Text/Finished 都会被此测试抓出。
        val count = 300
        val scripted = buildList {
            for (i in 0 until count) add(StreamChunk.TextDelta("$i,"))
            add(StreamChunk.Done("stop"))
        }
        val call = ScriptedCall(scripted, StreamResult.Completed)
        val received = mutableListOf<LLMStreamChunk>()
        (provider(call = call) as LLMProvider).streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "长文")), null, 64,
        ).collect { chunk ->
            received += chunk
            delay(2)
        }
        assertEquals(count + 2, received.size) // Started + 300 Text + Finished，一块不丢
        assertEquals(LLMStreamChunk.Started, received.first())
        val text = received.filterIsInstance<LLMStreamChunk.Text>().joinToString(separator = "") { it.text }
        assertEquals((0 until count).joinToString(separator = "") { "$it," }, text)
        assertEquals(LLMStreamChunk.Finished("stop"), received.last())
    }

    @Test
    fun `流中 error 对象按数字 code 走上游分类矩阵`() {
        val p = provider(call = ScriptedCall(emptyList(), StreamResult.Completed))
        fun classified(code: String?) = p.errorOf(StreamChunk.Failure("boom", code = code))
        assertTrue(classified("401") is LLMError.InvalidApiKey)
        assertTrue(classified("403") is LLMError.InvalidApiKey)
        assertTrue(classified("429") is LLMError.RateLimited)
        assertTrue(classified("500") is LLMError.TransientError)
        assertTrue(classified("502") is LLMError.TransientError)
        assertTrue(classified("529") is LLMError.TransientError)
        assertTrue(classified("404") is LLMError.ProviderError)
        // 非数字 code：同上游 optInt 默认 0，落供应商错误。
        assertTrue(classified("server_error") is LLMError.ProviderError)
        assertTrue(classified(null) is LLMError.ProviderError)
        // 503 带永久失败标记 → 供应商错误（触发组回退，不原地重试）。
        assertTrue(
            p.errorOf(StreamChunk.Failure("no_available_providers for this model", code = "503")) is LLMError.ProviderError,
        )
        assertTrue(p.errorOf(StreamChunk.Failure("overloaded", code = "503")) is LLMError.TransientError)
        // OpenCode 免费档日落：401/403 带 free tier 的文案转友好供应商错误。
        val sunset = p.errorOf(
            StreamChunk.Failure("OpenCode free tier can only be used from within OpenCode", code = "403"),
        )
        assertTrue(sunset is LLMError.ProviderError)
        assertTrue(sunset.message!!.contains("OpenCode"))
    }

    @Test
    fun `非流式入口拼回整体响应`() = runBlocking {
        val call = ScriptedCall(
            listOf(
                StreamChunk.TextDelta("你"),
                StreamChunk.TextDelta("好"),
                StreamChunk.Usage(12, 2),
                StreamChunk.Done("stop"),
            ),
            StreamResult.Completed,
        )
        val response = (provider(call = call) as LLMProvider).sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "打招呼")), null, 64,
        )
        assertEquals("你好", response.text)
        assertEquals("stop", response.stopReason)
        assertEquals(12, response.usage!!.inputTokens)
        assertEquals(2, response.usage!!.outputTokens)
        assertEquals(12, response.usage!!.latestContextTokens)
    }

    @Test
    fun `取消收集即断开底层调用`() {
        val entered = CountDownLatch(1)
        val call = BlockingCall(entered)
        val provider = provider(call = call)
        val collected = java.util.concurrent.ConcurrentLinkedQueue<LLMStreamChunk>()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                (provider as LLMProvider).streamMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER, "慢速")), null, 64,
                ).collect { collected.add(it) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            kotlinx.coroutines.withTimeout(10_000) { job.join() }
        }
        assertTrue("取消应触发 call.cancel()", call.cancelled.get())
    }

    // ---------------------------------------------------------------------
    // 适配器自身契约
    // ---------------------------------------------------------------------

    @Test
    fun `名字与文本整块语义与上游 OpenAI 线路一致`() {
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val provider = provider(call = call)
        assertEquals("OpenAI", (provider as LLMProvider).name)
        assertTrue(provider.streamTextIsMonolithic)
    }

    @Test
    fun `端点安全契约只收 https 与本机 http`() {
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        assertTrue(provider(call = call, basePath = "https://relay.example.com/v1").endpointAcceptable())
        assertTrue(provider(call = call, basePath = "http://127.0.0.1:11434/v1").endpointAcceptable())
        assertFalse(provider(call = call, basePath = "http://192.168.1.10:11434/v1").endpointAcceptable())
        assertFalse(provider(call = call, basePath = "ftp://relay.example.com/v1").endpointAcceptable())
    }

    @Test
    fun `出站头携带默认或自定义 User-Agent`() {
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val default = provider(call = call).outboundHeaders()
        assertTrue(default.getValue("User-Agent").startsWith("Minis/"))
        val custom = NovexTransportProvider(
            apiKey = "k", model = LLMModel("m", "M", "OpenAI"),
            basePath = "https://relay.example.com/v1",
            customUserAgent = "  Claude-Code/1.0  ",
            callOpener = { call },
        ).outboundHeaders()
        assertEquals("Claude-Code/1.0", custom.getValue("User-Agent"))
    }

    // ---------------------------------------------------------------------
    // [P3.1d] 生图接口面：imageDelegate 走自有 novex.model ImagesClient
    // ---------------------------------------------------------------------

    @Test
    fun `imageDelegate 对 OpenAI 兼容线可用且走自有 Images 端点`() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val pngB64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """{"data":[{"b64_json":"$pngB64","revised_prompt":"rev"}]}"""))
            server.start()
            val provider = NovexTransportProvider(
                apiKey = "relay-key", model = LLMModel("image-model", "Image", "OpenAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            val delegate = provider.imageDelegate
            assertTrue("OpenAI 兼容线应有生图能力", delegate != null)
            val response = delegate!!.generateImage("一只猫", 1, "1024x1024", null)
            val recorded = server.takeRequest()
            assertEquals("/v1/images/generations", recorded.path)
            assertEquals("Bearer relay-key", recorded.getHeader("Authorization"))
            assertEquals("POST", recorded.method)
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("image-model", body.getString("model"))
            assertEquals("b64_json", body.getString("response_format"))
            assertEquals("rev", response.text)
            assertEquals(1, response.mediaAttachments.size)
            assertEquals("image/png", response.mediaAttachments.single().mimeType)
            // 换模型后生图请求跟随当前模型（delegate 每次取值时同步）。
            provider.model = LLMModel("image-model-2", "Image 2", "OpenAI")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"data":[]}"""))
            provider.imageDelegate!!.generateImage("再画", 1, null, null)
            assertEquals("image-model-2", JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
        }
    }

    @Test
    fun `imageDelegate 的 HTTP 失败按聊天线同款矩阵分类`() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setResponseCode(429).setBody(
                    """{"error":{"message":"quota exceeded"}}"""))
            server.start()
            val provider = NovexTransportProvider(
                apiKey = "relay-key", model = LLMModel("image-model", "Image", "OpenAI"),
                basePath = server.url("/v1").toString().trimEnd('/'),
            )
            val failure = runCatching { provider.imageDelegate!!.generateImage("一只猫", 1, null, null) }.exceptionOrNull()
            assertTrue("应为限流错误，实际 $failure", failure is LLMError.RateLimited)
        }
    }

    // ---------------------------------------------------------------------
    // P3.1e：Responses 线 / Azure / 动态 OAuth / 回退 / codex 生图
    // ---------------------------------------------------------------------

    private fun responsesProvider(
        call: NovexTransportProvider.TransportCall,
        model: LLMModel = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        basePath: String = "https://relay.example.com/v1",
        isCodexOAuth: Boolean = false,
        codexAccountId: String? = null,
        oauthTokenProvider: (suspend () -> String)? = null,
        azureBase: String? = null,
        allowResponsesFallback: Boolean = false,
    ) = NovexTransportProvider(
        apiKey = "relay-key",
        model = model,
        basePath = basePath,
        instanceId = "instance-resp",
        protocol = novex.model.WireProtocol.RESPONSES,
        isCodexOAuth = isCodexOAuth,
        codexAccountId = codexAccountId,
        oauthTokenProvider = oauthTokenProvider,
        azureBase = azureBase,
        allowResponsesFallback = allowResponsesFallback,
        callOpener = { call },
    )

    @Test
    fun `responses 自定端点走 v1 responses 路径且 Bearer 鉴权`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("stop")), StreamResult.Completed)
        val provider = responsesProvider(call)
        assertEquals("https://relay.example.com/v1/responses", provider.completionUrl().toString())
        val request = provider.buildResponsesRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 256, emptyList(), emptyList(), ThinkingLevel.OFF,
        )
        assertEquals("api-key-null", null, provider.wireEndpoint(request).tokenHeader)
    }

    @Test
    fun `codex oauth 端点与客户端指纹头`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("stop")), StreamResult.Completed)
        val provider = responsesProvider(call, isCodexOAuth = true, codexAccountId = "acct-9")
        assertEquals(
            novex.model.ResponsesWire.CODEX_BACKEND_URL,
            provider.completionUrl().toString(),
        )
        val headers = provider.outboundHeaders()
        assertEquals("0.144.1", headers["Version"])
        assertEquals("responses=experimental", headers["Openai-Beta"])
        assertEquals("codex_cli_rs", headers["Originator"])
        assertEquals("acct-9", headers["Chatgpt-Account-Id"])
        assertTrue(headers["User-Agent"]!!.startsWith("codex_cli_rs/0.144.1"))
        // 账号 id 缺失时不发头（可空语义）。
        val noAccount = responsesProvider(ScriptedCall(emptyList(), StreamResult.Completed), isCodexOAuth = true)
        assertFalse(noAccount.outboundHeaders().containsKey("Chatgpt-Account-Id"))
    }

    @Test
    fun `responses 请求体形状-instructions-工具扁平-store-缓存键`() {
        val tool = AgentToolDefinition(
            name = "save_card", description = "保存",
            parameters = mapOf("title" to novex.android.data.model.AgentToolParam(type = "string", description = "t")),
            required = listOf("title"),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done("stop")), StreamResult.Completed)
        val body = JSONObject(
            responsesProvider(call).buildResponsesRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "你好")), "系统提示", 512,
                emptyList(), listOf(tool), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals("gpt-5.5", body.getString("model"))
        assertEquals("系统提示", body.getString("instructions"))
        assertFalse(body.has("messages"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("parallel_tool_calls"))
        assertEquals(512, body.getInt("max_output_tokens"))
        assertTrue(body.getString("prompt_cache_key").startsWith("minis-"))
        val toolJson = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", toolJson.getString("type"))
        assertEquals("save_card", toolJson.getString("name"))
        assertFalse(toolJson.has("function"))
        assertEquals("auto", body.getString("tool_choice"))
        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
    }

    @Test
    fun `codex oauth 请求体带加密思考回放且不写 max_output_tokens`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("stop")), StreamResult.Completed)
        val body = JSONObject(
            responsesProvider(call, isCodexOAuth = true).buildResponsesRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 512,
                emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        // codex 指纹体：include 加密思考；思考关也回落 reasoning low；不写 token 上限。
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("max_output_tokens"))
    }

    @Test
    fun `responses 工具历史映射为 function_call 与 function_call_output 双 id 回放`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("查"))),
            LLMMessage(
                LLMMessage.Role.ASSISTANT, "",
                contentParts = listOf(AgentContentPart.ToolUse("call_a|fc_b", "get_weather", JSONObject().put("city", "沪"))),
            ),
            LLMMessage(
                LLMMessage.Role.USER, "",
                contentParts = listOf(AgentContentPart.ToolResult("call_a|fc_b", "get_weather", "晴")),
            ),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done("tool_use")), StreamResult.Completed)
        val body = JSONObject(
            responsesProvider(call).buildResponsesRequest(
                history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        val input = body.getJSONArray("input")
        val functionCall = input.getJSONObject(1)
        assertEquals("function_call", functionCall.getString("type"))
        assertEquals("fc_b", functionCall.getString("id"))
        assertEquals("call_a", functionCall.getString("call_id"))
        assertEquals("get_weather", functionCall.getString("name"))
        val output = input.getJSONObject(2)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_a", output.getString("call_id"))
        assertEquals("晴", output.getString("output"))
    }

    @Test
    fun `azure deployments 路径剥离游离 v1 与 openai 段并保留 api-version 查询`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("stop")), StreamResult.Completed)
        val provider = responsesProvider(
            call,
            basePath = "https://x.openai.azure.com/ignored",
            azureBase = "https://x.openai.azure.com/openai/v1/?api-version=2025-04-01-preview",
        )
        assertEquals(
            "https://x.openai.azure.com/openai/deployments/gpt-5.5/responses?api-version=2025-04-01-preview",
            provider.completionUrl().toString(),
        )
        val request = provider.buildResponsesRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 128, emptyList(), emptyList(), ThinkingLevel.OFF,
        )
        val endpoint = provider.wireEndpoint(request)
        assertEquals("api-key", endpoint.tokenHeader)
        assertTrue(provider.endpointAcceptable())
    }

    @Test
    fun `动态 oauth 令牌每请求解析并送达传输调用`() = runBlocking {
        var resolveCount = 0
        val call = object : NovexTransportProvider.TransportCall {
            var token: String? = null
            override fun cancel() {}
            override fun begin(token: String?) { this.token = token }
            override fun stream(request: novex.model.CompletionStreamRequest, onChunk: (StreamChunk) -> Unit) =
                StreamResult.Completed
        }
        val provider = responsesProvider(call, oauthTokenProvider = { resolveCount++; "tok-$resolveCount" })
        (provider as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "a")), null, 32).toList()
        (provider as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "b")), null, 32).toList()
        assertEquals("每次请求都解析令牌（刷新感知）", 2, resolveCount)
        assertEquals("tok-2", call.token)
    }

    @Test
    fun `oauth 令牌解析失败以 InvalidApiKey 收流不进重试链`() {
        val call = ScriptedCall(emptyList(), StreamResult.Completed)
        val provider = responsesProvider(call, oauthTokenProvider = { throw LLMError.InvalidApiKey() })
        val error = runCatching {
            runBlocking { (provider as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "x")), null, 32).toList() }
        }.exceptionOrNull() as LLMError
        assertTrue(error is LLMError.InvalidApiKey)
    }

    @Test
    fun `回退-chat 首块前失败自动改走 responses 且进程粘性`() = runBlocking {
        // 第一个调用对 chat 请求失败；第二个对 responses 请求成功——断言重试换线。
        val seenRequests = java.util.concurrent.ConcurrentLinkedQueue<novex.model.CompletionStreamRequest>()
        var chatFailedOnce = false
        val call = object : NovexTransportProvider.TransportCall {
            override fun cancel() {}
            override fun begin(token: String?) {}
            override fun stream(request: novex.model.CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
                seenRequests += request
                return if (request is novex.model.StreamRequest && !chatFailedOnce) {
                    chatFailedOnce = true
                    onChunk(StreamChunk.Failure("HTTP 500", 500, "service", null, null))
                    StreamResult.Failed
                } else {
                    onChunk(novex.model.StreamChunk.TextDelta("ok"))
                    onChunk(novex.model.StreamChunk.Done("stop"))
                    StreamResult.Completed
                }
            }
        }
        val provider = NovexTransportProvider(
            apiKey = "k", model = LLMModel("m", "M", "OpenAI"),
            basePath = "https://relay.example.com/v1",
            instanceId = "sticky-test-instance",
            allowResponsesFallback = true,
            callOpener = { call },
        )
        val first = (provider as LLMProvider).streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 64,
        ).toList()
        assertTrue(first.any { it is LLMStreamChunk.Text && it.text == "ok" })
        // 首次往返：chat 失败 → responses 重试成功。
        assertTrue(seenRequests.first() is novex.model.StreamRequest)
        assertTrue(seenRequests.last() is novex.model.ResponsesStreamRequest)

        // 粘性：同实例 id 的新 provider 对象直接以 responses 起步（省一次必败 chat）。
        val call2 = object : NovexTransportProvider.TransportCall {
            var sawRequest: novex.model.CompletionStreamRequest? = null
            override fun cancel() {}
            override fun begin(token: String?) {}
            override fun stream(request: novex.model.CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
                sawRequest = request
                onChunk(novex.model.StreamChunk.Done("stop"))
                return StreamResult.Completed
            }
        }
        val provider2 = NovexTransportProvider(
            apiKey = "k", model = LLMModel("m", "M", "OpenAI"),
            basePath = "https://relay.example.com/v1",
            instanceId = "sticky-test-instance",
            allowResponsesFallback = true,
            callOpener = { call2 },
        )
        (provider2 as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 64).toList()
        assertTrue("粘性实例应直接走 responses", call2.sawRequest is novex.model.ResponsesStreamRequest)

        // 已发块后的失败不触发回退（中途中断照常抛错）。
        val midDropRequests = java.util.concurrent.ConcurrentLinkedQueue<novex.model.CompletionStreamRequest>()
        val midDrop = NovexTransportProvider(
            apiKey = "k", model = LLMModel("m2", "M", "OpenAI"),
            basePath = "https://qianchen2.example.com/v1",
            instanceId = "sticky-test-instance-2",
            allowResponsesFallback = true,
            callOpener = {
                object : NovexTransportProvider.TransportCall {
                    override fun cancel() {}
                    override fun begin(token: String?) {}
                    override fun stream(request: novex.model.CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
                        midDropRequests += request
                        onChunk(novex.model.StreamChunk.TextDelta("partial"))
                        onChunk(StreamChunk.Failure("HTTP 500", 500, "service", null, null))
                        return StreamResult.Failed
                    }
                }
            },
        )
        val error = runCatching {
            (midDrop as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 64).toList()
        }.exceptionOrNull()
        assertTrue(error is LLMError.TransientError)
        // 已发块后的失败不触发回退：只发过那一次 chat 请求，没有 responses 重试。
        assertEquals("中途中断不得追加 responses 重试请求", 1, midDropRequests.size)
        assertTrue(midDropRequests.single() is novex.model.StreamRequest)
    }

    @Test
    fun `回退-responses 重试也失败则粘性不落-新回合仍从 chat 起步`() = runBlocking {
        // 负向粘性：粘性等重试真的产出首块再落。重试也失败（连首块都没有）时
        // 本实例不得锁死在 responses 上——下一回合仍从 chat 试起。
        val seenRequests = java.util.concurrent.ConcurrentLinkedQueue<novex.model.CompletionStreamRequest>()
        val call = object : NovexTransportProvider.TransportCall {
            override fun cancel() {}
            override fun begin(token: String?) {}
            override fun stream(request: novex.model.CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
                seenRequests += request
                onChunk(StreamChunk.Failure("HTTP 500", 500, "service", null, null))
                return StreamResult.Failed
            }
        }
        val provider = NovexTransportProvider(
            apiKey = "k", model = LLMModel("m", "M", "OpenAI"),
            basePath = "https://qianchen3.example.com/v1",
            instanceId = "sticky-negative-instance",
            allowResponsesFallback = true,
            callOpener = { call },
        )
        val error = runCatching {
            (provider as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 64).toList()
        }.exceptionOrNull()
        assertTrue(error is LLMError.TransientError)
        // 首回合：chat 失败 → responses 重试也失败（两次请求，都无首块）。
        assertEquals(2, seenRequests.size)
        assertTrue(seenRequests.first() is novex.model.StreamRequest)
        assertTrue(seenRequests.last() is novex.model.ResponsesStreamRequest)

        // 同实例 id 的新 provider 对象：仍从 chat 起步（粘性未落）。
        val secondRequests = java.util.concurrent.ConcurrentLinkedQueue<novex.model.CompletionStreamRequest>()
        val call2 = object : NovexTransportProvider.TransportCall {
            override fun cancel() {}
            override fun begin(token: String?) {}
            override fun stream(request: novex.model.CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
                secondRequests += request
                onChunk(novex.model.StreamChunk.Done("stop"))
                return StreamResult.Completed
            }
        }
        val provider2 = NovexTransportProvider(
            apiKey = "k", model = LLMModel("m", "M", "OpenAI"),
            basePath = "https://qianchen3.example.com/v1",
            instanceId = "sticky-negative-instance",
            allowResponsesFallback = true,
            callOpener = { call2 },
        )
        (provider2 as LLMProvider).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 64).toList()
        assertEquals("失败重试不落粘性，新回合应从 chat 起步", 1, secondRequests.size)
        assertTrue(secondRequests.single() is novex.model.StreamRequest)
    }

    @Test
    fun `mistral 端点在 responses 与 chat 两线都抑制思考参数`() {
        // Mistral 闭 schema：请求拒 reasoning（422 extra_forbidden），历史回放也
        // 不收 reasoning_content——思考参数两头都不发。responses 线此前无测试
        // 钉（chat 线由金表覆盖）。
        val mistral = NovexTransportProvider(
            apiKey = "k",
            model = LLMModel("mistral-large-2411", "M", "Mistral", supportsReasoning = true),
            basePath = "https://api.mistral.ai/v1",
            protocol = novex.model.WireProtocol.RESPONSES,
        )
        val responsesReq = mistral.buildResponsesRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
        )
        assertNull("responses 线 Mistral 不发思考档", responsesReq.thinkingLevel)
        assertNull("responses 线 Mistral 不发关闭档", responsesReq.offEffort)

        val chatReq = mistral.buildStreamRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
        )
        val extras = chatReq.request.extraParameters
        assertTrue("chat 线 Mistral 思考参数为空", extras == null || extras.length() == 0 || !extras.has("reasoning_effort"))

        // 对照：非 Mistral 的 responses 端点同条件照发 effort。
        val other = NovexTransportProvider(
            apiKey = "k",
            model = LLMModel("gpt-5.5", "G", "OpenAI", supportsReasoning = true),
            basePath = "https://relay.example.com/v1",
            protocol = novex.model.WireProtocol.RESPONSES,
        )
        assertEquals(
            novex.model.WireThinkingLevel.HIGH,
            other.buildResponsesRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "q")), null, 512, emptyList(), emptyList(), ThinkingLevel.HIGH,
            ).thinkingLevel,
        )
    }

    @Test
    fun `codex 生图请求体-gpt-image-2 固定指纹`() {
        val call = ScriptedCall(listOf(StreamChunk.Done("end_turn")), StreamResult.Completed)
        val provider = responsesProvider(
            call,
            model = LLMModel("gpt-image-2", "GPT Image 2", "OpenAI"),
            isCodexOAuth = true,
        )
        val request = provider.buildWireRequest(
            listOf(LLMMessage(LLMMessage.Role.USER, "画一只猫")), null, 512,
            emptyList(), emptyList(), ThinkingLevel.OFF,
        )
        assertTrue(request is novex.model.ResponsesStreamRequest && request.codexImageRun)
        val body = JSONObject(request.encode())
        assertEquals("gpt-5.5", body.getString("model"))
        assertEquals("image_generation", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertTrue(body.getJSONArray("input").getJSONObject(0).getString("content").contains("画一只猫"))
        // 请求选择走 responses 生图解码器（流式事件族由模块测试钉）。
        assertTrue(provider.isCodexImageRun)
        // 非 codex 或非 gpt-image-2 不受此门影响。
        val plain = responsesProvider(ScriptedCall(emptyList(), StreamResult.Completed),
            model = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI"), isCodexOAuth = true)
        assertFalse(plain.isCodexImageRun)
    }

    @Test
    fun `responses 线 streamTextIsMonolithic 为假`() {
        val provider = responsesProvider(ScriptedCall(emptyList(), StreamResult.Completed))
        assertFalse((provider as LLMProvider).streamTextIsMonolithic)
        // chat 线仍为真（上游时序语义）。
        assertTrue((provider(call = ScriptedCall(emptyList(), StreamResult.Completed)) as LLMProvider).streamTextIsMonolithic)
    }

    @Test
    fun `OpenRouter 上 anthropic 前缀模型携带顶层 cache_control`() {
        val claude = LLMModel("anthropic/claude-sonnet-4.5", "Claude", "OpenRouter")
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val onRouter = JSONObject(
            provider(model = claude, call = call, basePath = "https://openrouter.ai/api/v1").buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals("ephemeral", onRouter.getJSONObject("cache_control").getString("type"))
        // 非 anthropic/ 前缀：请求体逐字节不带该键。
        val gpt = LLMModel("openai/gpt-4o", "GPT", "OpenRouter")
        val offPrefix = JSONObject(
            provider(model = gpt, call = call, basePath = "https://openrouter.ai/api/v1").buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertFalse(offPrefix.has("cache_control"))
        // anthropic 前缀但不在 openrouter 主机：同样不带。
        val notRouter = JSONObject(
            provider(model = claude, call = call, basePath = "https://relay.example.com/v1").buildStreamRequest(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertFalse(notRouter.has("cache_control"))
    }

    @Test
    fun `responses 线失败码 server_error 与 rate_limit_exceeded 归瞬态`() {
        val provider = responsesProvider(ScriptedCall(emptyList(), StreamResult.Completed))
        assertTrue(provider.errorOf(StreamChunk.Failure("[server_error] boom", code = "server_error")) is LLMError.TransientError)
        assertTrue(provider.errorOf(StreamChunk.Failure("[rate_limit_exceeded] boom", code = "rate_limit_exceeded")) is LLMError.TransientError)
        // chat 线同码仍按上游 optInt 口径归供应商错误。
        val chatProvider = provider(call = ScriptedCall(emptyList(), StreamResult.Completed))
        assertTrue(chatProvider.errorOf(StreamChunk.Failure("[server_error] boom", code = "server_error")) is LLMError.ProviderError)
    }

    @Test
    fun `anthropic 线工具结果图 mime 缺失时按魔数探测`() {
        val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val history = listOf(
            LLMMessage(
                LLMMessage.Role.ASSISTANT, "",
                contentParts = listOf(AgentContentPart.ToolUse("call-1", "screenshot", JSONObject())),
            ),
            LLMMessage(
                LLMMessage.Role.USER, "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        "call-1", "screenshot", "截图完成",
                        imageData = jpegMagic, imageMimeType = null,
                    ),
                ),
            ),
        )
        val call = ScriptedCall(listOf(StreamChunk.Done(null)), StreamResult.Completed)
        val provider = NovexTransportProvider(
            apiKey = "k", model = LLMModel.claudeSonnet46,
            basePath = "https://relay.example.com",
            instanceId = "i",
            protocol = novex.model.WireProtocol.ANTHROPIC_MESSAGES,
            callOpener = { call },
        )
        val body = JSONObject(
            provider.buildWireRequest(history, null, 256, emptyList(), emptyList(), ThinkingLevel.OFF).encode(),
        )
        // anthropic 形状：工具结果在 user 轮的 tool_result 块内，图片是其 content 的第二块。
        val mediaType = body.getJSONArray("messages")
            .getJSONObject(1).getJSONArray("content").getJSONObject(0)
            .getJSONArray("content").getJSONObject(1)
            .getJSONObject("source").getString("media_type")
        assertEquals("魔数探测应为 image/jpeg 而非假定 png", "image/jpeg", mediaType)
    }
}
