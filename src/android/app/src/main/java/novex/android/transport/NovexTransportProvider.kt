package novex.android.transport

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.MinisUserAgent
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.openCodeSunsetFriendlyError
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import novex.conversation.ModelCapacity
import novex.conversation.TokenMeasurement
import novex.model.ChatCompletionCall
import novex.model.ModelEndpoint
import novex.model.PendingTool
import novex.model.StreamChunk
import novex.model.StreamRequest
import novex.model.StreamResult
import novex.model.TextRequest
import novex.model.ToolDefinition
import novex.model.WireAudio
import novex.model.WireImage
import novex.model.WireMessage
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URI

/**
 * P3.1b 绞杀式适配器：对外实现上游 [LLMProvider] 接口（调用面零改动），
 * 对内把 OpenAI 兼容线路的聊天流量全部委托给自有 novex.model 传输。
 *
 * 本文件是绞杀缝：允许 import 上游类型（接口与数据形状），实现体为自有
 * 写法；图片生成不经此管，经 [imageDelegate] 原样回到上游实现。
 *
 * 已知不对齐项（judgment calls）见 docs/UPSTREAM_EXIT_PLAN.md P3.1b 行。
 */
class NovexTransportProvider(
    private val apiKey: String,
    override var model: LLMModel,
    /** 已规范化的基址（ProviderInstance.effectiveBaseURL，形如 https://host/v1）。 */
    private val basePath: String,
    private val customUserAgent: String? = null,
    /** 供应商实例 id：思考规则解析器的自定义规则键。 */
    private val instanceId: String? = null,
    /** 可注入传输面（单测钉时序用）；null → 真实 novex.model 调用。 */
    callOpener: (() -> TransportCall)? = null,
) : LLMProvider {

    /** 与 [ChatCompletionCall.stream] 同形的可注入传输面：单测用它钉流式时序。 */
    interface TransportCall {
        fun cancel()
        fun stream(request: StreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult
    }

    private val openCall: () -> TransportCall = callOpener ?: ::openRealCall

    override val name: String = "OpenAI"

    /**
     * Chat Completions 的 text 是单一整块（见上游接口注释）；下游按此
     * 决定文本块与工具块的重组策略，适配器必须如实报告。
     */
    override val streamTextIsMonolithic: Boolean get() = true

    /**
     * 生图不走自有传输：消费方（GenerateImageTool / QuickTestSheet）经
     * 此取上游实现，Images API 行为一字不差。model 在每次取值时同步，
     * 兜底换模型后生图请求仍用当前模型。
     */
    private val upstreamImageProvider by lazy {
        OpenAIProvider(apiKey = apiKey, model = model, basePath = basePath, customUserAgent = customUserAgent)
    }
    val imageDelegate: OpenAIProvider get() {
        upstreamImageProvider.model = model
        return upstreamImageProvider
    }

    // ---------------------------------------------------------------------
    // 请求装配
    // ---------------------------------------------------------------------

    internal fun completionUrl(): URI = URI(basePath.trimEnd('/') + "/chat/completions")

    private fun tokenOrNull(): String? = apiKey.takeIf { it.isNotEmpty() }

    internal fun outboundHeaders(): Map<String, String> {
        val userAgent = customUserAgent?.trim()?.takeIf { it.isNotEmpty() } ?: MinisUserAgent.DEFAULT
        return mapOf("User-Agent" to userAgent)
    }

    /** 端点安全契约（https 或本机 http，含请求头与令牌合法性）：工厂在选择点先行判定。 */
    internal fun endpointAcceptable(): Boolean =
        runCatching { ModelEndpoint(completionUrl(), tokenOrNull(), outboundHeaders()); true }.getOrDefault(false)

    private fun openRealCall(): TransportCall {
        val call = ChatCompletionCall(
            ModelEndpoint(completionUrl(), tokenOrNull(), outboundHeaders()),
            CALL_TIMEOUT_MILLIS,
        )
        return object : TransportCall {
            override fun cancel() = call.cancel()
            override fun stream(request: StreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult =
                call.stream(request, BYPASS_CAPACITY, BYPASS_WINDOW, { BYPASS_MEASUREMENT }, onChunk)
        }
    }

    internal fun buildStreamRequest(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): StreamRequest {
        val idRegistry = ToolCallIdRegistry()
        val wire = buildList {
            if (systemPrompt != null) add(WireMessage("system", systemPrompt))
            val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
            for ((index, message) in messages.withIndex()) {
                if (message.contentParts.isNotEmpty()) addAll(structuredParts(message, thinkingLevel, idRegistry))
                else addAll(legacyMessage(message, index == lastUserIndex, imageParts, thinkingLevel))
            }
        }
        val toolDefs = tools.map { definition ->
            val parameters = definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
            ToolDefinition(definition.name, definition.description, parameters.toString())
        }
        val extras = thinkingParameters(maxTokens, thinkingLevel) ?: JSONObject()
        // token 上限键按主机选择（贴上游 OpenAIProvider 口径）：OpenRouter 主机收
        // max_tokens（wire() 默认键），其余一切端点收 max_completion_tokens——OpenAI
        // 对 o 系/gpt-5 拒收 max_tokens（整线 400），中转前端同受此约束。
        if (!isOpenRouterHost) extras.put("max_completion_tokens", maxTokens)
        return StreamRequest(
            TextRequest(model.id, wire, maxTokens.toLong(), toolDefs, extras.takeIf { it.length() > 0 }),
            includeUsage = !isOpenRouterHost,
        )
    }

    /** 结构化部件（代理循环的真实路径）：assistant 文本+工具调用历史，user 工具结果+文本+图片。 */
    private fun structuredParts(
        message: LLMMessage,
        thinkingLevel: ThinkingLevel,
        idRegistry: ToolCallIdRegistry,
    ): List<WireMessage> = when (message.role) {
        LLMMessage.Role.ASSISTANT -> {
            idRegistry.beginBatch()
            listOf(
                WireMessage(
                    role = "assistant",
                    text = message.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text },
                    toolCalls = message.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { use ->
                        PendingTool(idRegistry.declare(use.id), use.name, use.input.toString())
                    },
                    reasoningContent = echoReasoningContent(message, thinkingLevel),
                ),
            )
        }
        LLMMessage.Role.USER -> buildList {
            for (result in message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                // 无法配对到本批工具调用的孤儿结果直接丢弃：发出去整单必被
                // 网关 400，丢弃只是少一条历史污染。
                idRegistry.resolve(result.id)?.let { paired ->
                    add(WireMessage(role = "tool", text = result.content, toolCallId = paired))
                }
            }
            val text = StringBuilder()
            for (part in message.contentParts.filterIsInstance<AgentContentPart.Text>()) {
                if (part.text.isNotEmpty()) text.append(part.text)
            }
            val images = mutableListOf<WireImage>()
            for (part in message.contentParts.filterIsInstance<AgentContentPart.ImageData>()) {
                val encoded = encodeImage(part.data, part.mimeType)
                if (encoded != null) images += encoded
                else text.append('\n').append(part.noVisionPlaceholder ?: NO_VISION_PLACEHOLDER)
            }
            if (images.isNotEmpty() || text.isNotEmpty()) {
                add(WireMessage(role = "user", text = text.toString(), images = images))
            }
        }
    }

    /** 旧式消息：纯文本；最后一条 user 消息挂顶层图片部件；音频部件照发。 */
    private fun legacyMessage(
        message: LLMMessage,
        isLastUser: Boolean,
        imageParts: List<LLMMessage.ImagePart>,
        thinkingLevel: ThinkingLevel,
    ): List<WireMessage> {
        if (message.role == LLMMessage.Role.ASSISTANT) {
            return listOf(
                WireMessage("assistant", message.content, reasoningContent = echoReasoningContent(message, thinkingLevel)),
            )
        }
        val attachImages = isLastUser && imageParts.isNotEmpty()
        if (!attachImages && message.audioParts.isEmpty()) return listOf(WireMessage("user", message.content))
        val text = StringBuilder(message.content)
        val images = mutableListOf<WireImage>()
        if (attachImages) {
            for (part in imageParts) {
                val encoded = encodeImage(part.data, part.mimeType)
                if (encoded != null) images += encoded
                else text.append('\n').append(part.noVisionPlaceholder ?: NO_VISION_PLACEHOLDER)
            }
        }
        val audios = message.audioParts.map { WireAudio(it.format, it.base64Data) }
        return listOf(WireMessage("user", text.toString(), images = images, audios = audios))
    }

    private fun echoReasoningContent(message: LLMMessage, thinkingLevel: ThinkingLevel): String? {
        if (isMistralHost) return null
        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        if (!modelMayReason) return null
        if (!thinkingLevel.isEnabled && !modelAlwaysReasons) return null
        return message.reasoningContent ?: ""
    }

    /**
     * 思考等级 → 请求体参数。沿用思考规则解析器（纯函数、golden 快照钉行为），
     * 在空对象上解析出本请求的思考键，作为附加顶层参数并入请求。
     * Mistral 端点两头都不发（请求参数与 reasoning_content 均被其闭 schema 拒绝）。
     */
    private fun thinkingParameters(maxTokens: Int, level: ThinkingLevel): JSONObject? {
        if (isMistralHost) return null
        val holder = JSONObject()
        ThinkingRuleResolver.apply(
            holder,
            ThinkingResolveContext(
                modelId = model.id,
                instanceId = instanceId,
                supportsReasoning = model.supportsReasoning,
                declaredEffortValues = model.reasoningEffortValues,
                declaresNoEffortTiers = model.declaresNoEffortTiers == true,
                level = level,
                maxTokens = maxTokens,
                isOpenRouter = isOpenRouterHost,
                usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
                isMistral = isMistralHost,
                isDashScope = isDashScopeHost,
                isXAI = isXAIHost,
                offEffort = explicitOffEffort,
            ),
        )
        return holder.takeIf { it.length() > 0 }
    }

    /**
     * 图片 → WireImage。先过图片预算再内联。图片学习式降级（乐观发送）：
     * 未学习降级的模型一律真实发送像素；仅当「无原生视觉输入且已被端点
     * 拒绝学习降级」时返回 null（调用方以占位文本顶替像素）。
     */
    private fun encodeImage(data: ByteArray, mimeType: String): WireImage? {
        val supportsImages = model.hasImageInput || model.id !in OpenAIProvider.imageDegradedModels
        if (!supportsImages) return null
        val encoder = java.util.Base64.getEncoder()
        return if (mimeType in ACCEPTED_IMAGE_TYPES) {
            val budgeted = ImageBudget.compressUnderBudget(data)
            if (budgeted === data) WireImage(mimeType, encoder.encodeToString(data))
            else WireImage("image/jpeg", encoder.encodeToString(budgeted))
        } else {
            // 传输层只收四种位图类型；其余（如 heic）强制重编码为 jpeg。
            WireImage("image/jpeg", encoder.encodeToString(ImageBudget.compressUnderBudget(data, 0)))
        }
    }

    // ---------------------------------------------------------------------
    // 端点嗅探（供思考规则与 include_usage 决策）
    // ---------------------------------------------------------------------

    private val loweredBase: String get() = basePath.lowercase()
    private val isOpenRouterHost: Boolean get() = loweredBase.contains("openrouter.ai")
    private val isMistralHost: Boolean get() = loweredBase.contains("mistral.ai")
    private val isDashScopeHost: Boolean get() = loweredBase.contains("dashscope")
    private val isXAIHost: Boolean get() = loweredBase.contains("api.x.ai") || loweredBase.contains("//x.ai")
    private val usesUnifiedReasoningEffort: Boolean
        get() = loweredBase.contains("volces") || loweredBase.contains("ark.") || loweredBase.contains("api.venice.ai")

    private val explicitOffEffort: String?
        get() {
            if (loweredBase.startsWith("https://api.openai.com")) return "none"
            val modelHint = model.id.lowercase()
            if (loweredBase.contains("volces") || loweredBase.contains("ark.") ||
                modelHint.contains("seed-") || modelHint.contains("doubao")
            ) return "minimal"
            return null
        }

    // ---------------------------------------------------------------------
    // 流式桥接
    // ---------------------------------------------------------------------

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> {
        // 图片学习式降级（乐观发送）：带图请求先按真实像素发送；首块前失败且
        // 报错指向图片输入时，记入降级集并用中性占位重试一次。与上游同策略、
        // 共用同一降级集与判别函数，跨实现互相可见。
        val requestHasImages = imageParts.isNotEmpty() ||
            messages.any { it.imageParts.isNotEmpty() || it.contentParts.any { part -> part is AgentContentPart.ImageData } }
        val raw: () -> Flow<LLMStreamChunk> = {
            rawStream(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        }
        if (!requestHasImages || model.id in OpenAIProvider.imageDegradedModels) return raw()
        var emittedAny = false
        return raw()
            .onEach { emittedAny = true }
            .catch { failure ->
                if (failure is kotlinx.coroutines.CancellationException || emittedAny) throw failure
                if (!OpenAIProvider.looksLikeImageRejection(failure.message)) throw failure
                AppLogger.warning(
                    "NovexTransport",
                    "[ImageDegrade] endpoint rejected image input for ${model.id} — retrying once with text placeholders",
                )
                OpenAIProvider.imageDegradedModels.add(model.id)
                emitAll(raw())
            }
    }

    private fun rawStream(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        // 注意：temperature 被丢弃——当前全部调用点都传 null（gpt-5 系兼容），
        // novex.model 请求体也不含该键；这是记录在案的刻意不对齐。
        val request = try {
            buildStreamRequest(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        } catch (failure: Throwable) {
            close(if (failure is LLMError) failure else LLMError.ProviderError(failure.message ?: "请求装配失败"))
            return@callbackFlow
        }
        val call = openCall()
        // [诊断 parity] 与上游同款的 ModelRequestAudit 事件：wire_request /
        // response_headers / http_error，凭据照走 safeText/safeUrl 脱敏。
        val audit = kotlinx.coroutines.currentCoroutineContext()[com.openminis.app.diagnostics.ModelRequestAudit]
        val secrets = listOfNotNull(tokenOrNull())
        audit?.event(
            "wire_request",
            JSONObject()
                .put("url", com.openminis.app.diagnostics.ModelRequestAudit.safeText(
                    com.openminis.app.diagnostics.ModelRequestAudit.safeUrl(completionUrl().toString()), secrets))
                .put("providerInstanceId", instanceId)
                .put("method", "POST")
                .put("protocol", "chat_completions")
                .put("modelId", model.id)
                .put("stream", true)
                .put("toolCount", tools.size)
                .put("maxOutputTokens", maxTokens),
        )
        // 背压：ChatCompletionCall.stream 的契约是 onChunk 在读线程上就地回调、
        // 慢消费者自然放慢读取。桥到 Flow 时必须以阻塞送进来维持该契约——
        // trySend 在 callbackFlow 默认 64 缓冲满时静默丢块（丢 Text=缺字、
        // 丢 ToolCallComplete=工具挂起、丢 Finished=误报中断），trySendBlocking
        // 让生产者（IO 线程）阻塞等消费者腾位，语义与直连回调一致。
        val bridge = StreamBridge { chunk -> trySendBlocking(chunk) }
        launch(Dispatchers.IO) {
            val outcome = try {
                call.stream(request, bridge::accept)
            } catch (failure: Throwable) {
                close(if (failure is LLMError) failure else LLMError.NetworkError(failure))
                return@launch
            }
            when (outcome) {
                StreamResult.Completed -> {
                    // chat completions 的 SSE 成功响应恒为 200；自有传输只报
                    // 「2xx 已到」，此处以 200 记账。
                    audit?.event("response_headers", JSONObject().put("status", 200))
                    close()
                }
                StreamResult.Cancelled -> close()
                StreamResult.Failed -> {
                    val failure = bridge.failure
                    audit?.event(
                        "http_error",
                        JSONObject()
                            .put("status", failure?.status ?: -1)
                            .put("errorType", com.openminis.app.diagnostics.ModelRequestAudit.safeText(
                                failure?.category ?: "", secrets))
                            .put("message", com.openminis.app.diagnostics.ModelRequestAudit.safeText(
                                failure?.message ?: "stream failed", secrets)),
                    )
                    val error = failure?.let(::errorOf) ?: LLMError.ProviderError("流在错误状态结束")
                    AppLogger.warning("NovexTransport", "[$name] stream failed: ${error.message}")
                    close(error)
                }
                StreamResult.TimedOut -> close(
                    LLMError.NetworkError(
                        SocketTimeoutException("stream read timeout (${CALL_TIMEOUT_MILLIS / 1000}s) from $name"),
                    ),
                )
                StreamResult.NetworkFailure -> {
                    // 无终止信号的断流：冲出已拼装的工具调用（截断恢复路径），
                    // 不发 Finished —— 与上游“静默截断”表现一致，交由调用方裁决。
                    bridge.drainToolCalls()
                    close()
                }
                is StreamResult.NotSent -> close(LLMError.ProviderError("请求被容量闸门拦下：${outcome.capacity}"))
            }
        }
        awaitClose { runCatching { call.cancel() } }
    }

    /** 非流式入口与上游同策：内部走流式（含停滞看门狗），再把增量拼回整体响应。 */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        val text = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> text.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                else -> Unit
            }
        }
        return LLMResponse(text.toString(), stopReason, usage)
    }

    /**
     * 失败块 → 上游错误分类，两条来源各自对齐：
     *  - HTTP 非 200（category 非空）：401/403 鉴权、429 限流、5xx 瞬态、其余供应商错误；
     *  - 流中 error 对象（category 为空、code 为字符串）：按上游 mapHttpError 的数字
     *    状态矩阵分类——401/403 鉴权、429 限流、500/502/503/504/529 瞬态（503 带
     *    no_available_providers / model_not_found 归供应商错误以触发组回退）、其余及
     *    非数字 code（同上游 optInt 默认 0）归供应商错误。
     *  - 401/403 两路都过 openCode 免费档日落友好文案（与上游同判别函数）。
     */
    internal fun errorOf(failure: StreamChunk.Failure): LLMError {
        val detail = buildString {
            append(failure.message)
            failure.code?.takeIf { it.isNotBlank() }?.let { append(" [code=").append(it).append(']') }
            failure.retryAfter?.takeIf { it.isNotBlank() }?.let { append(" [retry-after=").append(it).append(']') }
        }
        val status = failure.status ?: failure.code?.toIntOrNull()
        if (failure.category == null) {
            return when {
                status == 401 || status == 403 -> openCodeSunsetFriendlyError(failure.message) ?: LLMError.InvalidApiKey(detail)
                status == 429 -> LLMError.RateLimited(detail)
                status in TRANSIENT_HTTP_STATUSES ->
                    if (status == 503 && PERMANENT_503_MARKERS.any { failure.message.contains(it) })
                        LLMError.ProviderError(detail)
                    else LLMError.TransientError(detail)
                else -> LLMError.ProviderError(detail)
            }
        }
        return when (failure.category) {
            "authentication" -> openCodeSunsetFriendlyError(failure.message) ?: LLMError.InvalidApiKey(detail)
            "rate_limit" -> LLMError.RateLimited(detail)
            "service" -> if (status != null && status in TRANSIENT_HTTP_STATUSES) LLMError.TransientError(detail)
            else LLMError.ProviderError(detail)
            else -> LLMError.ProviderError(detail)
        }
    }

    internal companion object {
        /**
         * 读超时：上游该线路为 600s 读超时 + 300s 停滞看门狗；这里取
         * 300s 对齐看门狗边界（合法的长思考静默实测 ~190s，留有余量），
         * 连接阶段同样受此上限，黑洞连接由看门狗先一步切断。
         */
        const val CALL_TIMEOUT_MILLIS = 300_000

        val ACCEPTED_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

        private val TRANSIENT_HTTP_STATUSES = setOf(500, 502, 503, 504, 529)

        /** 503 携带这些标记时是永久性失败（触发组回退而非原地重试），与上游同款。 */
        private val PERMANENT_503_MARKERS = listOf("no_available_providers", "model_not_found")

        /** 容量闸门在此层直通：窗口/输入计量决策归调用方（ChatViewModel 动态预算），传输层不重复设卡。 */
        private val BYPASS_CAPACITY = ModelCapacity(1L shl 40)
        private const val BYPASS_WINDOW = 1L shl 40
        private val BYPASS_MEASUREMENT = TokenMeasurement(0, "适配器直通，窗口决策在调用方", true)

        /** 与上游 T264 占位语义一致的中性文案（无 Vision Group 提示时的兜底）。 */
        const val NO_VISION_PLACEHOLDER =
            "[本条消息附带过图片，但当前模型不支持图片输入，本轮未见图片内容；如需图片细节请让用户描述图片或改用支持图片输入的模型]"
    }

    /**
     * novex.model 流块 → 上游 LLMStreamChunk 时序桥。
     *
     * 关键时序（贴上游 Chat Completions 的调用习惯）：
     *  - Started 在首个非失败块之前发出；HTTP 错误在 Started 之前直接以异常收流；
     *  - 工具调用：分片到达即发 ToolUseStart（id+name 齐备时，仅一次）与
     *    ToolInputDelta（参数累积值）；
     *  - Done：先 ReasoningContent（若有过思考增量），再 Finished，最后补
     *    ToolCallComplete —— Finished 先于 ToolCallComplete 是上游 [DONE]
     *    路径的实际顺序，下游已按此消化；
     *  - 断流（无 Done）时只补 ToolCallComplete，不发 Finished。
     */
    internal class StreamBridge(private val send: (LLMStreamChunk) -> Unit) {
        private var started = false
        private val thinking = StringBuilder()
        private val toolAccumulators = LinkedHashMap<Int, ToolAccumulator>()
        var failure: StreamChunk.Failure? = null
            private set

        private class ToolAccumulator {
            var id = ""
            var name = ""
            val arguments = StringBuilder()
            var announced = false
        }

        fun accept(chunk: StreamChunk) {
            if (chunk is StreamChunk.Failure) {
                failure = chunk
                return
            }
            ensureStarted()
            when (chunk) {
                is StreamChunk.TextDelta -> if (chunk.text.isNotEmpty()) send(LLMStreamChunk.Text(chunk.text))
                is StreamChunk.ThinkingDelta -> if (chunk.text.isNotEmpty()) {
                    thinking.append(chunk.text)
                    send(LLMStreamChunk.ThinkingDelta(chunk.text))
                }
                is StreamChunk.ToolCallDelta -> acceptToolDelta(chunk)
                is StreamChunk.Usage -> send(
                    LLMStreamChunk.Usage(
                        LLMUsage(
                            inputTokens = chunk.inputTokens?.toInt() ?: 0,
                            outputTokens = chunk.outputTokens?.toInt() ?: 0,
                            latestContextTokens = (chunk.inputTokens ?: 0).toInt(),
                        ),
                    ),
                )
                is StreamChunk.Done -> {
                    if (thinking.isNotEmpty()) send(LLMStreamChunk.ReasoningContent(thinking.toString()))
                    send(LLMStreamChunk.Finished(chunk.finishReason))
                    drainToolCalls()
                }
                else -> Unit
            }
        }

        private fun acceptToolDelta(chunk: StreamChunk.ToolCallDelta) {
            val acc = toolAccumulators.getOrPut(chunk.index) { ToolAccumulator() }
            chunk.id?.let { acc.id = it }
            chunk.name?.let { acc.name = it }
            acc.arguments.append(chunk.argumentsDelta)
            if (!acc.announced && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                acc.announced = true
                send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
            }
            if (acc.id.isNotEmpty() && acc.arguments.isNotEmpty()) {
                send(LLMStreamChunk.ToolInputDelta(acc.id, acc.arguments.toString()))
            }
        }

        fun drainToolCalls() {
            for (acc in toolAccumulators.values) {
                if (acc.id.isEmpty() || acc.name.isEmpty()) continue
                val args = try { JSONObject(acc.arguments.toString()) } catch (_: Exception) { JSONObject() }
                send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
            }
        }

        private fun ensureStarted() {
            if (!started) {
                started = true
                send(LLMStreamChunk.Started)
            }
        }
    }
}

/**
 * 工具调用 id 规范化（按批次配对）：
 *  - assistant 声明批次（beginBatch + declare）：超长 id 折成确定性短 id，
 *    跨消息重复 id 改名——部分兼容网关以 tool_call_id 重复为由整单 400；
 *  - tool 结果（resolve）取本批配对 id；配不上的孤儿结果由调用方丢弃。
 * 同一批内配对一致性由 WireMessage 的装配校验兜底。
 */
private class ToolCallIdRegistry {
    private val used = mutableSetOf<String>()
    private var openBatch: Map<String, String> = emptyMap()

    fun beginBatch() { openBatch = emptyMap() }

    fun declare(raw: String): String {
        val base = if (raw.length <= MAX_TOOL_CALL_ID_LENGTH) raw else shorten(raw)
        var chosen = base
        if (base in used) {
            var suffix = 1
            do {
                chosen = "${base}_$suffix"
                suffix += 1
            } while (chosen in used)
        }
        used += chosen
        openBatch = openBatch + (raw to chosen)
        return chosen
    }

    fun resolve(raw: String): String? = openBatch[raw]

    private fun shorten(raw: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "call_${hex.take(SHORTENED_ID_HEX_LENGTH)}"
    }

    private companion object {
        const val MAX_TOOL_CALL_ID_LENGTH = 64
        const val SHORTENED_ID_HEX_LENGTH = 56
    }
}
