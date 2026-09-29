package novex.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import novex.conversation.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

/**
 * P3.1e Responses 线：请求编码（input/instructions/扁平工具/store/缓存键/reasoning/
 * include/max_output_tokens）、SSE 事件族解码（文本/思考/工具双 id/终态三事件/哨兵
 * 缺失补收尾）、codex 生图流、裸 Content-Type 与 api-key 头（Azure 形态）。
 */
class ResponsesWireTest {

    private fun decoder(codexImageRun: Boolean = false) = ResponsesSseDecoder(codexImageRun)
    private fun feed(d: ResponsesSseDecoder, vararg lines: String): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        for (line in lines) out += d.feed(line)
        return out
    }

    private fun request(
        messages: List<WireMessage> = listOf(WireMessage("user", "hi")),
        tools: List<ToolDefinition> = emptyList(),
        level: WireThinkingLevel? = null,
        offEffort: String? = null,
        codex: Boolean = false,
        maxTokens: Long = 512,
    ) = ResponsesStreamRequest(
        model = "gpt-5.5", messages = messages, maxOutputTokens = maxTokens, tools = tools,
        thinkingLevel = level, offEffort = offEffort, isCodexOAuth = codex,
    )

    // ------------------------------------------------------------------
    // 请求编码
    // ------------------------------------------------------------------

    @Test fun `请求体形状-instructions-扁平工具-store-并行工具`() {
        val tool = ToolDefinition("save_card", "保存", """{"type":"object","properties":{}}""")
        val body = JSONObject(request(
            messages = listOf(WireMessage("system", "sys"), WireMessage("user", "问")),
            tools = listOf(tool),
        ).encode())
        assertEquals("gpt-5.5", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("parallel_tool_calls"))
        assertEquals("sys", body.getString("instructions"))
        assertEquals(512, body.getInt("max_output_tokens"))
        val toolJson = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", toolJson.getString("type"))
        assertEquals("save_card", toolJson.getString("name"))
        assertFalse("Responses 工具是扁平形状，非 function 包装", toolJson.has("function"))
        assertEquals("auto", body.getString("tool_choice"))
        val input = body.getJSONArray("input")
        assertEquals(1, input.length()) // system 提升为 instructions，不占 input 位
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("问", input.getJSONObject(0).get("content"))
    }

    @Test fun `prompt_cache_key-同会话稳定且跨会话可分`() {
        val first = JSONObject(request(messages = listOf(WireMessage("user", "开场白"))).encode())
            .getString("prompt_cache_key")
        val again = JSONObject(request(messages = listOf(WireMessage("user", "开场白"))).encode())
            .getString("prompt_cache_key")
        val other = JSONObject(request(messages = listOf(WireMessage("user", "另一个会话"))).encode())
            .getString("prompt_cache_key")
        assertEquals(first, again)
        assertNotEquals(first, other)
        assertTrue(first.startsWith("minis-"))
    }

    @Test fun `思考档映射与 codex 回落 low`() {
        val on = JSONObject(request(level = WireThinkingLevel.HIGH).encode()).getJSONObject("reasoning")
        assertEquals("high", on.getString("effort"))
        assertEquals("auto", on.getString("summary"))
        // ULTRA 折叠 max（端点不认字面 ultra）；XHIGH 原样。
        assertEquals("max", ResponsesWire.effortFor(WireThinkingLevel.ULTRA))
        assertEquals("xhigh", ResponsesWire.effortFor(WireThinkingLevel.XHIGH))
        // codex：思考关也回落 low（后端无 reasoning 对象即拒）。
        val codexOff = JSONObject(request(codex = true).encode()).getJSONObject("reasoning")
        assertEquals("low", codexOff.getString("effort"))
        // 非 codex 思考关 + 允许名单显式关档：只带 effort 不带 summary。
        val explicitOff = JSONObject(request(offEffort = "none").encode()).getJSONObject("reasoning")
        assertEquals("none", explicitOff.getString("effort"))
        assertFalse(explicitOff.has("summary"))
        // 非 codex 无关档：整个字段省略。
        assertFalse(JSONObject(request().encode()).has("reasoning"))
    }

    @Test fun `codex 指纹体-include 加密思考且不写 max_output_tokens`() {
        val body = JSONObject(request(codex = true, maxTokens = 999).encode())
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertFalse("codex 客户端指纹体不携带 max_output_tokens", body.has("max_output_tokens"))
        // 非 codex 带 include 键的第三端点会 400——include 只在 codex 出现。
        assertFalse(JSONObject(request().encode()).has("include"))
    }

    @Test fun `function_call 双 id 回放与无 fc 段时合成`() {
        val body = JSONObject(request(messages = listOf(
            WireMessage("assistant", "", toolCalls = listOf(PendingTool("call_a|fc_b", "get_weather", "{}"))),
            WireMessage("tool", "晴", toolCallId = "call_a|fc_b"),
            WireMessage("assistant", "", toolCalls = listOf(PendingTool("plain_call", "probe", "{}"))),
            WireMessage("tool", "ok", toolCallId = "plain_call"),
        )).encode())
        val input = body.getJSONArray("input")
        val withFc = input.getJSONObject(0)
        assertEquals("function_call", withFc.getString("type"))
        assertEquals("fc_b", withFc.getString("id"))
        assertEquals("call_a", withFc.getString("call_id"))
        val withoutFc = input.getJSONObject(2)
        assertTrue(withoutFc.getString("id").startsWith("fc_syn_"))
        assertEquals("plain_call", withoutFc.getString("call_id"))
    }

    @Test fun `超长双 id 截断到 64 字符`() {
        val longCall = "call_" + "a".repeat(40)
        val longFc = "fc_" + "b".repeat(70)
        val combined = "$longCall|$longFc"
        assertEquals(64, ResponsesWire.capId(longFc).length)
        val split = ResponsesWire.splitIds(combined)
        assertEquals(longCall, split.first)
        assertEquals(longFc, split.second)
    }

    @Test fun `用户轮图片与音频块形状`() {
        val png = WireImage("image/png", "AAAA")
        val wav = WireAudio("wav", "YXVkaW8=")
        val body = JSONObject(request(messages = listOf(WireMessage("user", "看", images = listOf(png), audios = listOf(wav)))).encode())
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        val image = content.getJSONObject(1)
        assertEquals("input_image", image.getString("type"))
        assertEquals("data:image/png;base64,AAAA", image.get("image_url"))
        val audio = content.getJSONObject(2)
        assertEquals("input_audio", audio.getString("type"))
        assertEquals("wav", audio.getJSONObject("input_audio").getString("format"))
    }

    @Test fun `工具结果配对校验-缺结果或幽灵结果拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(messages = listOf(
                WireMessage("assistant", "", toolCalls = listOf(PendingTool("c1", "t", "{}"))),
            )).encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(messages = listOf(WireMessage("tool", "孤儿", toolCallId = "ghost"))).encode()
        }
    }

    // ------------------------------------------------------------------
    // SSE 事件族解码
    // ------------------------------------------------------------------

    @Test fun `文本与思考增量事件`() {
        val chunks = feed(decoder(),
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n",
            "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"想\"}\n\n",
            "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"摘要\"}\n\n",
        )
        assertEquals(listOf<StreamChunk>(
            StreamChunk.TextDelta("你好"), StreamChunk.ThinkingDelta("想"), StreamChunk.ThinkingDelta("摘要"),
        ), chunks)
    }

    @Test fun `工具调用-added-增量-done 全链`() {
        val d = decoder()
        val chunks = mutableListOf<StreamChunk>()
        chunks += d.feed("data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"shell\"}}\n\n")
        chunks += d.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"delta\":\"{\\\"a\\\":\"}\n\n")
        chunks += d.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"delta\":\"1}\"}\n\n")
        assertEquals(StreamChunk.ToolCallDelta(0, "call_1|fc_1", "shell", ""), chunks[0])
        assertEquals(StreamChunk.ToolCallDelta(0, null, null, "{\"a\":"), chunks[1])
        // 增量契约：分片只吐增量本身，累积在聚合器。
        assertEquals(StreamChunk.ToolCallDelta(0, null, null, "1}"), chunks[2])
        // 聚合器拼装出完整调用。
        val assembled = StreamAssembler().also { chunks.forEach(it::accept) }
        val call = assembled.toolCalls.single()
        assertEquals("call_1|fc_1", call.id)
        assertEquals("shell", call.name)
        assertEquals("{\"a\":1}", call.arguments)
    }

    @Test fun `从未 added 的整块 function_call 补全量参数`() {
        val chunks = feed(decoder(),
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_9\",\"call_id\":\"call_9\",\"name\":\"probe\",\"arguments\":\"{\\\"x\\\":2}\"}}\n\n",
        )
        val delta = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals("call_9|fc_9", delta.id)
        assertEquals("probe", delta.name)
        assertEquals("{\"x\":2}", delta.argumentsDelta)
    }

    @Test fun `completed 无哨兵补发 Done-completed 带 stop`() {
        val d = decoder()
        val events = mutableListOf<StreamChunk>()
        events += d.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"完整回复\"}\n\n")
        events += d.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":4}}}}\n\n")
        // 流干净断开（无 [DONE]）——finishReason 已见，补发收尾。
        events += d.finish()
        assertEquals(StreamChunk.TextDelta("完整回复"), events[0])
        val usage = events.filterIsInstance<StreamChunk.Usage>().single()
        assertEquals(10L, usage.inputTokens)
        assertEquals(5L, usage.outputTokens)
        assertEquals(4L, usage.cacheReadTokens)
        assertEquals(StreamChunk.Done("stop"), events.last())
    }

    @Test fun `completed 带 DONE 哨兵只发一个 Done`() {
        val d = decoder()
        val events = mutableListOf<StreamChunk>()
        events += d.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
        events += d.feed("data: [DONE]\n\n")
        events += d.finish()
        assertEquals(1, events.filterIsInstance<StreamChunk.Done>().size)
        assertEquals("stop", events.filterIsInstance<StreamChunk.Done>().single().finishReason)
    }

    @Test fun `见过工具调用的 completed 给 tool_use 收尾`() {
        val d = decoder()
        val events = mutableListOf<StreamChunk>()
        events += d.feed("data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"t\"}}\n\n")
        events += d.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
        events += d.finish()
        assertEquals("tool_use", events.filterIsInstance<StreamChunk.Done>().single().finishReason)
    }

    @Test fun `completed 的 output 里带 function_call 也算 tool_use`() {
        val d = decoder()
        val events = mutableListOf<StreamChunk>()
        events += d.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}]}}\n\n")
        events += d.finish()
        assertEquals("tool_use", events.filterIsInstance<StreamChunk.Done>().single().finishReason)
    }

    @Test fun `截断流-无终止语义不补 Done`() {
        val d = decoder()
        val events = mutableListOf<StreamChunk>()
        events += d.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"半截\"}\n\n")
        events += d.finish()
        assertTrue(events.none { it is StreamChunk.Done })
    }

    @Test fun `response failed 携带结构化错误码`() {
        val d = decoder()
        val events = d.feed("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"too hot\"}}}\n\n")
        val failure = events.filterIsInstance<StreamChunk.Failure>().single()
        assertTrue(failure.message.contains("rate_limit_exceeded"))
        assertTrue(failure.message.contains("too hot"))
        assertEquals("rate_limit_exceeded", failure.code)
        assertTrue(d.sawFailure())
    }

    @Test fun `response incomplete 给出原因与 max_output_tokens 提示`() {
        val d = decoder()
        val events = d.feed("data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n")
        val failure = events.filterIsInstance<StreamChunk.Failure>().single()
        assertTrue(failure.message.contains("max_output_tokens"))
        assertTrue(failure.message.contains("raise the model"))
        assertTrue(d.sawFailure())
    }

    @Test fun `顶层 error 事件收流`() {
        val d = decoder()
        val events = d.feed("data: {\"error\":{\"code\":\"server_error\",\"message\":\"upstream died\"}}\n\n")
        val failure = events.filterIsInstance<StreamChunk.Failure>().single()
        assertEquals("server_error", failure.code)
        assertTrue(failure.message.contains("upstream died"))
    }

    // ------------------------------------------------------------------
    // codex 生图流（gpt-image-2）
    // ------------------------------------------------------------------

    @Test fun `codex 生图-输出图块转媒体附件并收尾`() {
        // 1x1 PNG 的 base64。
        val pngB64 = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val d = decoder(codexImageRun = true)
        val events = mutableListOf<StreamChunk>()
        events += d.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"拒答不该外发\"}\n\n")
        events += d.feed("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"$pngB64\"}}\n\n")
        events += d.finish()
        // 生图模式：文本增量不外发（转拒答文案留档）。
        assertTrue(events.none { it is StreamChunk.TextDelta })
        val media = events.filterIsInstance<StreamChunk.MediaAttachment>().single()
        assertEquals("image/png", media.mimeType)
        assertEquals(pngB64, media.base64)
        assertEquals(StreamChunk.Done("end_turn"), events.last())
    }

    @Test fun `codex 生图-completed 里整块 output 也能提图`() {
        val pngB64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val d = decoder(codexImageRun = true)
        val events = d.feed(
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"image_generation_call\",\"result\":\"$pngB64\"}]}}\n\n")
        assertTrue(events.any { it is StreamChunk.MediaAttachment })
        assertTrue(d.sawDone())
    }

    @Test fun `codex 生图-安全拒答与无图收流`() {
        val refused = decoder(codexImageRun = true)
        val refusalEvents = refused.feed(
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"不能生成\"}]}}\n\n")
        val refusalTail = refused.finish()
        val failure = (refusalEvents + refusalTail).filterIsInstance<StreamChunk.Failure>().single()
        assertTrue(failure.message.contains("safety"))
        assertTrue(failure.message.contains("不能生成"))

        val empty = decoder(codexImageRun = true)
        empty.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")
        val tail = empty.finish()
        assertTrue(tail.filterIsInstance<StreamChunk.Failure>().single().message.contains("No image data"))
    }

    @Test fun `codex 生图-图块前到 DONE 哨兵不发 Done-null 而按无图收流`() {
        // 对齐被删上游：读循环遇 [DONE] 只是 break，收尾判定在流尾——图块前到
        // 哨兵绝不能被翻译成 Done(null)（那会把「无图」伪装成正常完成）。
        val d = decoder(codexImageRun = true)
        val events = d.feed("data: [DONE]\n\n")
        assertTrue("哨兵不该直接产生任何块", events.isEmpty())
        val tail = d.finish()
        val all = events + tail
        assertTrue(all.none { it is StreamChunk.Done })
        assertTrue(all.filterIsInstance<StreamChunk.Failure>().single().message.contains("No image data"))
    }

    @Test fun `codex 生图-思考增量不折进拒答文案`() {
        // 被删上游只认文本/消息条目为拒答源；思考流折进 refusalText 会把「无图无
        // 拒答」误判成安全拒答。
        val d = decoder(codexImageRun = true)
        val events = d.feed(
            "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考片段\"}\n" +
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"摘要片段\"}\n\n")
        assertTrue(events.none { it is StreamChunk.ThinkingDelta })
        val tail = d.finish()
        val all = events + tail
        val failure = all.filterIsInstance<StreamChunk.Failure>().single()
        assertTrue("思考文本不得进入拒答文案: ${failure.message}", failure.message.contains("No image data"))
        assertTrue(!failure.message.contains("思考片段"))
    }

    @Test fun `codex 生图-整段 done 文本替换增量拒答不重复`() {
        val d = decoder(codexImageRun = true)
        d.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"我不能\"}\n\n")
        d.feed("data: {\"type\":\"response.output_text.done\",\"text\":\"我不能生成该图片\"}\n\n")
        val tail = d.finish()
        val failure = tail.filterIsInstance<StreamChunk.Failure>().single()
        assertTrue(failure.message.contains("我不能生成该图片"))
        // 替换而非追加：增量前缀不得在终稿里出现两次。
        assertEquals(failure.message.indexOf("我不能"), failure.message.lastIndexOf("我不能"))
    }

    // ------------------------------------------------------------------
    // 端到端（HttpServer）：Content-Type 裸 json + api-key 头 + 哨兵缺失收尾
    // ------------------------------------------------------------------

    private fun server(block: (HttpServer, URI) -> Unit) {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); httpServer.executor = executor; httpServer.start()
        try { block(httpServer, URI("http://127.0.0.1:${httpServer.address.port}/v1/responses")) }
        finally { httpServer.stop(0); executor.shutdownNow() }
    }

    private fun measure(value: String) = TokenMeasurement(value.toByteArray().size.toLong(), "测试用字节估算", true)

    @Test fun `responses 端到端-裸 Content-Type-无哨兵收尾`() = server { httpServer, endpoint ->
        val captured = java.util.concurrent.ConcurrentHashMap<String, String>()
        httpServer.createContext("/v1/responses") { exchange: HttpExchange ->
            captured["content-type"] = exchange.requestHeaders.getFirst("Content-Type") ?: ""
            captured["authorization"] = exchange.requestHeaders.getFirst("Authorization") ?: ""
            captured["body"] = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write(("data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
                    .toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
        val call = ChatCompletionCall(
            ModelEndpoint(endpoint, "tok-1"),
            10_000, WireProtocol.RESPONSES,
        )
        val assembler = StreamAssembler()
        val result = call.stream(request(), ModelCapacity(128_000), 1024, ::measure) { assembler.accept(it) }
        assertEquals(StreamResult.Completed, result)
        // 裸 application/json（无 charset 后缀）——部分第三方 Responses 中转严格拒收后缀。
        assertEquals("application/json", captured["content-type"])
        assertEquals("Bearer tok-1", captured["authorization"])
        assertEquals("ok", assembler.text)
        assertEquals("stop", assembler.finishReason)
        assertTrue(JSONObject(captured["body"]).has("input"))
    }

    @Test fun `azure 端点-api-key 头替代 Bearer`() = server { httpServer, endpoint ->
        val apiKeyHeader = java.util.concurrent.atomic.AtomicReference<String?>()
        val bearerHeader = java.util.concurrent.atomic.AtomicReference<String?>()
        httpServer.createContext("/v1/responses") { exchange: HttpExchange ->
            apiKeyHeader.set(exchange.requestHeaders.getFirst("api-key"))
            bearerHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n".toByteArray()) }
        }
        val azureEndpoint = ModelEndpoint(endpoint, "azure-key-1", tokenHeader = "api-key")
        val call = ChatCompletionCall(azureEndpoint, 10_000, WireProtocol.RESPONSES)
        call.stream(request(), ModelCapacity(128_000), 1024, ::measure) {}
        assertEquals("azure-key-1", apiKeyHeader.get())
        assertNull("Azure 模式不得再发 Authorization Bearer", bearerHeader.get())
    }

    @Test fun `协议防呆-请求体方言错配开连接前拒绝`() {
        val call = ChatCompletionCall(
            ModelEndpoint(URI("https://relay.example.com/v1/responses"), null),
            10_000, WireProtocol.RESPONSES)
        val wrong = StreamRequest(TextRequest("m", listOf(WireMessage("user", "hi")), 64))
        assertThrows(IllegalArgumentException::class.java) {
            call.stream(wrong, ModelCapacity(128_000), 1024, ::measure) {}
        }
    }
}
