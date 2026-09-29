package novex.android.transport

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.openai.OpenAIProvider
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
        // 非 OpenRouter 主机走上游口径：max_completion_tokens，且不带默认 max_tokens
        // （OpenAI 对 o 系/gpt-5 拒收 max_tokens）。
        assertEquals(777, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
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
                "title" to com.openminis.app.data.model.AgentToolParam(
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
        OpenAIProvider.imageDegradedModels.add("test-model")
        try {
            val degraded = JSONObject(
                provider(call = call).buildStreamRequest(request, null, 128, parts, emptyList(), ThinkingLevel.OFF).encode(),
            )
            val message = degraded.getJSONArray("messages").getJSONObject(0)
            assertTrue(message.get("content") is String)
            assertTrue(message.getString("content").contains("不支持图片输入"))
        } finally {
            OpenAIProvider.imageDegradedModels.remove("test-model")
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
    fun `OpenRouter 主机收 max_tokens 其余主机收 max_completion_tokens`() {
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

        // 其余主机（含中转）：上游口径的 max_completion_tokens，不带 max_tokens。
        val relay = JSONObject(
            provider(call = call, basePath = "https://relay.example.com/v1").buildStreamRequest(
                messages, null, 64, emptyList(), emptyList(), ThinkingLevel.OFF,
            ).encode(),
        )
        assertEquals(64, relay.getInt("max_completion_tokens"))
        assertFalse(relay.has("max_tokens"))
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
        assertEquals(LLMStreamChunk.Usage(com.openminis.app.data.model.LLMUsage(30, 7, latestContextTokens = 30)), chunks[6])
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
}
