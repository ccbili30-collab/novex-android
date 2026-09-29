package novex.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Responses 线协议（novex.model 自有实现，零上游依赖）。与 Chat Completions
 * 方言的差异面（对齐被替换的上游实现）：
 *  - 消息序列是顶层 `input`（items），system 提升为顶层 `instructions`；
 *  - 工具是扁平形状 `{type,name,description,parameters}`（非 `{type,function:{…}}` 包装）；
 *  - 工具调用历史是 assistant 轮的 `function_call` item（`id`=fc_… + `call_id`=call_…
 *    双 id，下一轮必须原样回放）与 user 轮的 `function_call_output` item；
 *  - 图片块是 `input_image`，`image_url` 为裸字符串（非 `{url:…}` 对象）；
 *  - 思考是 `reasoning:{effort,summary}`；Codex OAuth（chatgpt.com 后端）另要求
 *    `include:["reasoning.encrypted_content"]` 回放加密思考，且请求体不写
 *    max_output_tokens（codex_cli_rs 客户端指纹）；
 *  - `store:false` + `prompt_cache_key`（按会话稳定）命中 Responses 前缀缓存；
 *  - SSE 事件族 `response.output_text.delta` / `response.reasoning_text.delta` /
 *    `response.output_item.added|done` / `response.function_call_arguments.delta` /
 *    `response.completed|failed|incomplete`，无必然的 `data: [DONE]` 哨兵——
 *    `response.completed` 携带终止语义与用量，干净断流时据此补发收尾。
 */
object ResponsesWire {
    /** Codex OAuth（ChatGPT 后端）的 Responses 端点——与 API-key /v1/responses 不同域。 */
    const val CODEX_BACKEND_URL = "https://chatgpt.com/backend-api/codex/responses"

    /**
     * 工具调用双 id 组合/拆分：agent 循环以单一字符串携带 `call_id|item_id`，下一请求
     * 原样拆开回放（item_id 必须与服务端发的 fc_… 逐字一致）。无 `|` 视为无 item_id。
     */
    fun combineIds(callId: String, itemId: String): String =
        if (itemId.isEmpty()) callId else "$callId|$itemId"

    fun splitIds(combined: String): Pair<String, String?> {
        val separator = combined.indexOf('|')
        return if (separator < 0) combined to null
        else combined.substring(0, separator) to combined.substring(separator + 1)
    }

    /** Responses 的 id 上限 64 字符，超长截断防 400。 */
    fun capId(id: String): String = if (id.length <= 64) id else id.substring(0, 64)

    /** 思考档 → reasoning.effort；ULTRA 折叠到 max（端点不认字面 ultra）。 */
    fun effortFor(level: WireThinkingLevel): String = when (level) {
        WireThinkingLevel.LOW -> "low"
        WireThinkingLevel.MEDIUM -> "medium"
        WireThinkingLevel.HIGH -> "high"
        WireThinkingLevel.XHIGH -> "xhigh"
        WireThinkingLevel.MAX, WireThinkingLevel.ULTRA -> "max"
    }

    /**
     * 会话稳定缓存键：同会话每轮重发同一首条 user 文本 → 其 SHA-256 前缀跨轮稳定、
     * 跨会话可分；无 user 文本（首轮纯附件）退随机 UUID。无此前缀缓存命中时 Responses
     * 后端把每轮当独立 prompt，缓存率大幅劣化。
     */
    fun promptCacheKey(firstUserText: String?): String {
        val text = firstUserText.orEmpty()
        if (text.isEmpty()) return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "minis-${hex.take(32)}"
    }

    /**
     * [T-codex-gpt-image-2] gpt-image-2 经 Codex OAuth 后端生图的固定指纹体：线上模型
     * 是 gpt-5.5（后端再驱动内建 image_generation 工具），用户轮为固定指令文案。
     * Codex OAuth 令牌没有 Images API scope（401 Missing scopes），生图只能走该后端。
     */
    fun codexImageRequest(prompt: String): ResponsesStreamRequest = ResponsesStreamRequest(
        model = CODEX_IMAGE_WIRE_MODEL,
        messages = listOf(WireMessage("user", "Use the image generation tool to create: $prompt")),
        maxOutputTokens = 1,
        codexImageRun = true,
    )

    /** codex 生图指纹体的线上模型（与用户选择的 gpt-image-2 不同）。 */
    const val CODEX_IMAGE_WIRE_MODEL = "gpt-5.5"
}

/**
 * Responses 流式请求体。system 角色的 [WireMessage] 提升为顶层 `instructions`（至多
 * 一条）；工具调用配对校验沿用 [WireMessage] 的装配约束（function_call/function_call_output
 * 以 call_id 配对）。
 */
data class ResponsesStreamRequest(
    val model: String,
    val messages: List<WireMessage>,
    /** 输出预留（max_output_tokens）；Codex OAuth 指纹体不写该键，仅作容量计量。 */
    val maxOutputTokens: Long,
    val tools: List<ToolDefinition> = emptyList(),
    /** 思考档；null=关。 */
    val thinkingLevel: WireThinkingLevel? = null,
    /**
     * 思考关且机型必思考时的显式关档（官方 OpenAI → "none" 等允许名单）；null=省略
     * reasoning 字段（沿用厂商默认）。
     */
    val offEffort: String? = null,
    val supportsReasoning: Boolean? = null,
    /** Codex OAuth（chatgpt.com 后端）：include 加密思考回放；不写 max_output_tokens。 */
    val isCodexOAuth: Boolean = false,
    /** Fast 档（service_tier=priority）； ineligible 上游忽略或静默降档。 */
    val serviceTierPriority: Boolean = false,
    /** [T-codex-gpt-image-2] 固定指纹体（tools=[image_generation]、reasoning low、include []）。 */
    val codexImageRun: Boolean = false,
) : CompletionStreamRequest {
    init {
        require(model.isNotBlank() && messages.isNotEmpty() && outputReserve > 0)
        require(tools.map { it.name }.distinct().size == tools.size)
        require(messages.count { it.role == "system" } <= 1)
        if (codexImageRun) require(thinkingLevel == null && tools.isEmpty() && !isCodexOAuth)
    }
    override val outputReserve: Long get() = maxOutputTokens
    override fun encode(): String = wire().toString()

    private fun wire(): JSONObject = if (codexImageRun) codexImageWire() else normalWire()

    /** gpt-image-2 生图指纹体：逐字对齐被替换实现的固定形状。 */
    private fun codexImageWire(): JSONObject = JSONObject()
        .put("model", ResponsesWire.CODEX_IMAGE_WIRE_MODEL)
        .put("instructions", "You are a helpful assistant. Use tools when available.")
        .put("input", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", messages.first().text)
        }))
        .put("store", false)
        .put("tools", JSONArray().put(JSONObject().put("type", "image_generation")))
        .put("reasoning", JSONObject().put("effort", "low"))
        .put("include", JSONArray())
        .put("tool_choice", "auto")
        .put("parallel_tool_calls", true)
        .put("stream", true)

    private fun normalWire(): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("store", false)
            .put("parallel_tool_calls", true)
            .put("prompt_cache_key", ResponsesWire.promptCacheKey(firstUserText()))
        if (isCodexOAuth) body.put("include", JSONArray().put("reasoning.encrypted_content"))
        when {
            // Mistral 闭 schema 拒收 reasoning 请求参数——两头（chat/responses）都不发。
            // （isMistral 判定在适配层；这里以 thinkingLevel=null + offEffort=null 的调用
            // 约定表达。）
            thinkingLevel != null -> body.put(
                "reasoning",
                JSONObject().put("effort", ResponsesWire.effortFor(thinkingLevel)).put("summary", "auto"),
            )
            // Codex 后端无 reasoning 对象直接拒——关档也要回落 low（对齐被替换实现）。
            isCodexOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            // 必思考机型思考关：显式关档（允许名单端点）替代省略，阻止厂商默认开启。
            offEffort != null -> body.put("reasoning", JSONObject().put("effort", offEffort))
        }
        if (maxOutputTokens > 0 && !isCodexOAuth) body.put("max_output_tokens", maxOutputTokens)
        if (serviceTierPriority) body.put("service_tier", "priority")
        messages.firstOrNull { it.role == "system" }?.let { body.put("instructions", it.text) }
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(JSONObject().put("type", "function")
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", JSONObject(tool.parameters)))
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }
        body.put("input", inputItems())
        return body
    }

    private fun firstUserText(): String? =
        messages.firstOrNull { it.role == "user" }?.text?.takeIf { it.isNotEmpty() }

    /** function_call/function_call_output 以 call_id 配对（与 Chat 线 tool 消息同约束）。 */
    private fun inputItems(): JSONArray {
        val pending = mutableSetOf<String>()
        val items = JSONArray()
        for (message in messages) {
            if (message.role == "system") continue
            when (message.role) {
                "assistant" -> {
                    require(pending.isEmpty()) { "上一批工具调用尚未补齐结果" }
                    if (message.text.isNotEmpty()) items.put(JSONObject()
                        .put("role", "assistant").put("content", message.text))
                    for (call in message.toolCalls) {
                        val (callId, fcId) = ResponsesWire.splitIds(call.id)
                        val safeCallId = ResponsesWire.capId(callId)
                        // Responses 双 id：id（fc_…）必须原样回放；Chat 线历史混入
                        // （无 fc 段）时合成确定性 id，端点仍接受。
                        val safeFcId = fcId?.let(ResponsesWire::capId)
                            ?: "fc_syn_${safeCallId.takeLast(24)}"
                        items.put(JSONObject()
                            .put("type", "function_call")
                            .put("id", safeFcId)
                            .put("call_id", safeCallId)
                            .put("name", call.name)
                            .put("arguments", call.arguments))
                        pending += safeCallId
                    }
                }
                "tool" -> {
                    val (callId, _) = ResponsesWire.splitIds(message.toolCallId!!)
                    val safeCallId = ResponsesWire.capId(callId)
                    require(pending.remove(safeCallId)) { "工具结果没有对应的待处理调用" }
                    items.put(JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", safeCallId)
                        .put("output", message.text))
                }
                else -> items.put(JSONObject().put("role", message.role).put("content", userContent(message)))
            }
        }
        require(pending.isEmpty()) { "工具调用结果尚未齐全，不能继续请求" }
        return items
    }

    /** user 轮内容：纯文本裸字符串；带图/音频为块数组（input_text/input_image/input_audio）。 */
    private fun userContent(message: WireMessage): Any {
        if (message.images.isEmpty() && message.audios.isEmpty()) return message.text
        val parts = JSONArray()
        if (message.text.isNotEmpty()) parts.put(JSONObject().put("type", "input_text").put("text", message.text))
        for (image in message.images) parts.put(JSONObject()
            .put("type", "input_image")
            // Responses 的 image_url 是裸字符串，非 Chat 线的 {url:…} 对象。
            .put("image_url", "data:${image.mediaType};base64,${image.base64}"))
        for (audio in message.audios) parts.put(JSONObject().put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", audio.base64).put("format", audio.format)))
        return parts
    }
}

/**
 * Responses SSE 方言解码器。事件族 → 通用流块：
 *  - `response.output_text.delta` → TextDelta；`response.reasoning_text.delta` /
 *    `response.reasoning_summary_text.delta` → ThinkingDelta；
 *  - `response.output_item.added`（function_call）→ ToolCallDelta（组合 id `call_…|fc_…`）；
 *    `response.function_call_arguments.delta` → 参数增量（按 item_id 归组）；
 *    `response.output_item.done` 对从未 added 过的 function_call 补整块（防御无增量
 *    事件的中转）；
 *  - `response.completed` → 终止语义（function_call 在场 → "tool_use"；completed →
 *    "stop"）+ 用量（input_tokens_details.cached_tokens → cacheRead）；不发 Done——
 *    留给 [DONE] 哨兵或干净断流的补发路径（许多中转不发哨兵）；
 *  - `response.failed` / `response.incomplete` / 顶层 `error` → Failure（server_error /
 *    rate_limit_exceeded 由调用方按瞬态处理）；
 *  - [codexImageRun]：gpt-image-2 生图流——文本/思考增量不再外发（转拒答文案），
 *    image_generation_call 的 base64 结果 → MediaAttachment + Done("end_turn")；
 *    既无图又无拒答时以 Failure 收流。
 */
internal class ResponsesSseDecoder(private val codexImageRun: Boolean = false) : SseLineDecoder() {
    private val itemIndex = HashMap<String, Int>()
    private var nextIndex = 0
    private var sawToolCall = false
    private var imageEmitted = false
    private var imageCallFailed = false
    private var refusalText: String? = null

    override fun decodePayload(event: JSONObject): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        when (event.streamText("type")) {
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
                // 生图流：思考增量既不外发也不折进拒答文案（对齐被删上游——它只认
                // 文本/消息条目为拒答源，思考流从未进 refusalText；折进去会把
                // 「无图无拒答」误判成安全拒答，错误文案与 400 语义都跟着错）。
                if (!codexImageRun) {
                    event.streamText("delta").takeIf { it.isNotEmpty() }?.let { out += StreamChunk.ThinkingDelta(it) }
                }
            "response.output_text.delta" ->
                event.streamText("delta").takeIf { it.isNotEmpty() }?.let {
                    if (codexImageRun) appendRefusal(it) else out += StreamChunk.TextDelta(it)
                }
            "response.output_item.added" -> event.optJSONObject("item")?.let { itemAdded(it, out) }
            "response.function_call_arguments.delta" -> {
                val itemId = event.streamText("item_id")
                val delta = event.streamText("delta")
                if (delta.isNotEmpty()) {
                    sawToolCall = true
                    out += StreamChunk.ToolCallDelta(indexOf(itemId), null, null, delta)
                }
            }
            "response.output_item.done" -> event.optJSONObject("item")?.let { itemDone(it, out) }
            "response.failed" -> {
                markFailed()
                val error = event.optJSONObject("response")?.optJSONObject("error")
                val code = error?.streamText("code").orEmpty().ifBlank { "unknown" }
                val message = error?.streamText("message").orEmpty()
                    .ifBlank { "response.failed with no error detail" }
                out += StreamChunk.Failure("[$code] $message", code = code)
            }
            "response.incomplete" -> {
                markFailed()
                val reason = event.optJSONObject("response")?.optJSONObject("incomplete_details")
                    ?.streamText("reason").orEmpty().ifBlank { "unknown" }
                out += StreamChunk.Failure(
                    "Response ended incomplete (reason: $reason)" +
                        if (reason == "max_output_tokens") {
                            " — output hit max_output_tokens; raise the model's Max Output Tokens or shorten the request."
                        } else "",
                )
            }
            "response.completed" -> completed(event, out)
            "response.output_text.done" -> if (codexImageRun) {
                // 整段 done 文本「替换」累积的增量拒答（对齐被删上游：delta 累积、
                // done 覆盖——两者最终给出的都是完整文案，替换可免增量+整段的重复）。
                event.streamText("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
            }
            "error" -> out += topLevelError(event)
            // 无 type 字段但携带 error 对象的载荷（部分中转的裸错误事件）同样按
            // 终态失败处理——静默忽略会把错误流伪装成空回复。
            else -> if (event.optJSONObject("error") != null) out += topLevelError(event)
        }
        return out
    }

    private fun topLevelError(event: JSONObject): List<StreamChunk> {
        markFailed()
        val error = event.optJSONObject("error")
        return listOf(StreamChunk.Failure(
            error?.streamText("message").orEmpty().ifBlank { "服务在流中报告错误" },
            code = error?.streamText("code").orEmpty().ifBlank { null },
        ))
    }

    /**
     * 生图流不认 [DONE] 哨兵（对齐被删上游：读循环遇 [DONE] 只是 break，收尾
     * 判定全在流尾）——图块前到达的哨兵若在此发 Done(null)，会把「无图」伪装成
     * 正常完成。哨兵行落进非 JSON 丢弃路径，终局一律交给 [finish] 的 Failure
     * 判定（拒答/无图）；图已产出时解码器已在终态，哨兵行本就被忽略。
     */
    override val acceptsDoneSentinel: Boolean get() = !codexImageRun

    /**
     * 生图流不在干净断流时补发默认收尾：Done 只随图片产出（end_turn）发出；
     * 无图的断流走 [finish] 的 Failure 判定（拒答/无图），不能被一个
     * finishReason=stop 的 Done 盖掉。
     */
    override fun shouldFinishAtEndOfStream(): Boolean = !codexImageRun && super.shouldFinishAtEndOfStream()

    /** 生图流的终局：无图无拒答（或明确拒答）以 Failure 收流——不发 Done。 */
    override fun finish(): List<StreamChunk> {
        val events = super.finish().toMutableList()
        if (codexImageRun && !sawDone() && !sawFailure()) {
            markFailed()
            events += StreamChunk.Failure(
                when {
                    imageCallFailed || refusalText != null ->
                        "Image generation was rejected by the safety system" +
                            (refusalText?.trim()?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
                    else -> "No image data in Codex response"
                },
            )
        }
        return events
    }

    private fun indexOf(itemId: String): Int = itemIndex.getOrPut(itemId) { nextIndex++ }

    private fun itemAdded(item: JSONObject, out: MutableList<StreamChunk>) {
        if (item.streamText("type") != "function_call") return
        val itemId = item.streamText("id")
        val callId = item.streamText("call_id")
        val name = item.streamText("name")
        if (itemId.isEmpty() || callId.isEmpty() || name.isEmpty()) return
        sawToolCall = true
        out += StreamChunk.ToolCallDelta(
            indexOf(itemId),
            ResponsesWire.combineIds(callId, itemId),
            name,
            "",
        )
    }

    private fun itemDone(item: JSONObject, out: MutableList<StreamChunk>) {
        when (item.streamText("type")) {
            "function_call" -> {
                // 常规路径：added+增量已发过，此处无事。仅防御无 added/增量事件的中转
                // （一次性整块 function_call）——按到达序补全量参数。
                val itemId = item.streamText("id")
                if (itemId.isNotEmpty() && itemId !in itemIndex) {
                    val callId = item.streamText("call_id")
                    val name = item.streamText("name")
                    if (callId.isNotEmpty() && name.isNotEmpty()) {
                        sawToolCall = true
                        out += StreamChunk.ToolCallDelta(
                            indexOf(itemId),
                            ResponsesWire.combineIds(callId, itemId),
                            name,
                            item.streamText("arguments"),
                        )
                    }
                }
            }
            "image_generation_call" -> {
                if (!codexImageRun) return
                if (item.streamText("status") == "failed") imageCallFailed = true
                item.streamText("result").takeIf { it.isNotEmpty() }?.let { base64 ->
                    imageEmitted = true
                    out += StreamChunk.MediaAttachment(imageMimeOf(base64), base64)
                    finishReason = "end_turn"
                    markDone()
                    out += StreamChunk.Done("end_turn")
                }
            }
            "message", "output_text" -> if (codexImageRun) {
                val content = item.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.streamText("type").contains("text")) {
                            part.streamText("text").takeIf { it.isNotEmpty() }?.let(::appendRefusal)
                        }
                    }
                } else item.streamText("text").takeIf { it.isNotEmpty() }?.let(::appendRefusal)
            }
        }
    }

    private fun completed(event: JSONObject, out: MutableList<StreamChunk>) {
        val response = event.optJSONObject("response")
        val outputHasFunctionCall = response?.optJSONArray("output")?.let { output ->
            (0 until output.length()).any { output.optJSONObject(it)?.streamText("type") == "function_call" }
        } ?: false
        val status = response?.streamText("status").orEmpty()
        finishReason = when {
            sawToolCall || outputHasFunctionCall -> "tool_use"
            status == "completed" -> "stop"
            else -> status.ifBlank { null }
        }
        // 生图流：completed 里再扫一遍 output（部分中转只在此处给整块 item）。
        if (codexImageRun && !imageEmitted) {
            val output = response?.optJSONArray("output") ?: return
            for (i in 0 until output.length()) {
                itemDone(output.optJSONObject(i) ?: continue, out)
            }
        }
        response?.optJSONObject("usage")?.let { usage ->
            val input = usage.streamCount("input_tokens")
            val outputTokens = usage.streamCount("output_tokens")
            val cacheRead = usage.optJSONObject("input_tokens_details")?.streamCount("cached_tokens")
            if (input != null || outputTokens != null || cacheRead != null) {
                out += StreamChunk.Usage(input, outputTokens, cacheReadTokens = cacheRead)
            }
        }
    }

    private fun appendRefusal(text: String) {
        refusalText = (refusalText ?: "") + text
    }

    /** 生图结果的 mime：解 base64 头部字节按魔数探测（PNG/JPEG/WebP/GIF），缺省 png。 */
    private fun imageMimeOf(base64: String): String {
        val header = try {
            java.util.Base64.getDecoder().decode(base64.take(24))
        } catch (_: IllegalArgumentException) {
            return "image/png"
        }
        if (header.size < 4) return "image/png"
        return when {
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> "image/png"
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> "image/jpeg"
            header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte() -> "image/webp"
            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() -> "image/gif"
            else -> "image/png"
        }
    }
}
