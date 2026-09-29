package novex.android.transport

import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.ImageDegradationLearning
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.MinisUserAgent
import com.openminis.app.provider.failOnSilentEmptyCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import novex.android.thinking.ThinkingContractResolver
import novex.android.thinking.ThinkingResolveContext
import novex.conversation.ModelCapacity
import novex.conversation.TokenMeasurement
import novex.model.AnthropicMessagesRequest
import novex.model.AnthropicWire
import novex.model.ChatCompletionCall
import novex.model.CompletionStreamRequest
import novex.model.GeminiGenerateContentRequest
import novex.model.ModelEndpoint
import novex.model.PendingTool
import novex.model.ResponsesStreamRequest
import novex.model.ResponsesWire
import novex.model.StreamChunk
import novex.model.StreamRequest
import novex.model.StreamResult
import novex.model.TextRequest
import novex.model.ToolDefinition
import novex.model.WireProtocol
import novex.model.WireThinkingLevel
import novex.model.WireAudio
import novex.model.WireImage
import novex.model.WireMessage
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URI

/**
 * P3.1b/P3.1c/P3.1e 绞杀式适配器：对外实现上游 [LLMProvider] 接口（调用面零改动），
 * 对内按 [protocol] 分线把聊天流量全部委托给自有 novex.model 传输——
 *  - [WireProtocol.CHAT_COMPLETIONS]：OpenAI 兼容线（P3.1b 已切真实流量）；
 *  - [WireProtocol.ANTHROPIC_MESSAGES]：Anthropic Messages 原生线（P3.1c）；
 *  - [WireProtocol.GEMINI_GENERATE_CONTENT]：Gemini 原生线（P3.1c）；
 *  - [WireProtocol.RESPONSES]：OpenAI Responses 线（P3.1e）——Codex OAuth
 *    （chatgpt.com 后端 + 客户端指纹头 + gpt-image-2 生图）、useResponsesAPI
 *    自定端点、前尘 chat→responses 自动回退（[allowResponsesFallback]）。
 *
 * 随线差异面（P3.1e 补齐，原上游 openai 包（已随 P3.1e 整体删除）九类实例的全部活路径）：
 *  - 动态 OAuth bearer（[oauthTokenProvider]）：每请求挂起解析，Codex/xAI/Kimi
 *    共用同一机制；
 *  - Azure（[azureBase]）：{端点}/openai/deployments/{model}/{path}?api-version=…
 *    路径 + `api-key` 头（非 Bearer），聊天/Responses/生图三路同规则；
 *  - 附加出站头（[extraHeaders]）：OpenRouter 的 HTTP-Referer / X-Title；
 *  - LAN 明文政策与 P3.1c 一致：自有传输只收 https/本机回环，局域网明文 http
 *    中继以确定性 ProviderError 收流（请求前置预检）。
 *
 * 本文件是绞杀缝：允许 import 上游类型（接口与数据形状），实现体为自有写法；
 * 图片生成自 P3.1d 起走自有 novex.model ImagesClient（[imageDelegate]），
 * anthropic/gemini 线无 Images API 返回 null。图片学习式降级集自 P3.1e 起迁至
 * app 侧 [ImageDegradationLearning]（上游 openai 包已整体删除）。
 *
 * 已知不对齐项（judgment calls）见 docs/UPSTREAM_EXIT_PLAN.md P3.1b/P3.1c/P3.1d/P3.1e 行。
 */
class NovexTransportProvider(
    private val apiKey: String,
    override var model: LLMModel,
    /** 已规范化的基址（OpenAI 兼容线形如 https://host/v1；anthropic/gemini 为各自端点根）。 */
    private val basePath: String,
    private val customUserAgent: String? = null,
    /** 供应商实例 id：思考规则解析器的自定义规则键与 responses 回退粘性键。 */
    private val instanceId: String? = null,
    /** 线协议：决定请求编码、SSE 解码方言与端点/鉴权头。 */
    private val protocol: WireProtocol = WireProtocol.CHAT_COMPLETIONS,
    /** Anthropic OAuth（Claude Code）线路：Bearer 鉴权、系统前缀块、CLI 指纹头。 */
    val isAnthropicOAuth: Boolean = false,
    /** 动态 OAuth bearer（Codex / xAI / Kimi）：每请求挂起取新鲜令牌；null=静态 [apiKey]。 */
    private val oauthTokenProvider: (suspend () -> String)? = null,
    /** Codex OAuth（chatgpt.com Responses 后端）：客户端指纹头 + 请求体约束。 */
    internal val isCodexOAuth: Boolean = false,
    /** Codex 账号 id（JWT 提取）；Chatgpt-Account-Id 头，可空（缺失不 401）。 */
    private val codexAccountId: String? = null,
    /** Azure OpenAI：原始用户端点（含 ?api-version=…）；deployments 路径 + api-key 头。 */
    private val azureBase: String? = null,
    /** 前尘回退：chat 首块前失败自动改走 /v1/responses 重试一次（进程内粘性）。 */
    private val allowResponsesFallback: Boolean = false,
    /** 附加出站头（最后合并，同名替换默认头）：OpenRouter 的 HTTP-Referer / X-Title。 */
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** 可注入传输面（单测钉时序用）；null → 真实 novex.model 调用。 */
    callOpener: (() -> TransportCall)? = null,
) : LLMProvider {

    /** 与 [ChatCompletionCall.stream] 同形的可注入传输面：单测用它钉流式时序。 */
    interface TransportCall {
        fun cancel()
        /** 本次请求的已解析令牌（动态 OAuth 每请求不同）；真实实现存下供端点装配。 */
        fun begin(token: String?) {}
        fun stream(request: CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult
    }

    private val openCall: () -> TransportCall = callOpener ?: ::openRealCall

    override val name: String = when (protocol) {
        WireProtocol.CHAT_COMPLETIONS, WireProtocol.RESPONSES -> "OpenAI"
        WireProtocol.ANTHROPIC_MESSAGES -> "Anthropic"
        WireProtocol.GEMINI_GENERATE_CONTENT -> "Google"
    }

    /** 上游各家默认输出上限：Anthropic 64k，其余 16k（对齐被替换实现）。 */
    override val defaultMaxOutputTokens: Int get() = when (protocol) {
        WireProtocol.ANTHROPIC_MESSAGES -> 64_000
        else -> 16_384
    }

    /** 本实例已激活 responses 回退（粘性命中或回退重试时置真；构造时未知实例 id，推迟到首请求）。 */
    @Volatile internal var responsesFallbackActivated = false

    /**
     * 生效线协议：openAI 族的 chat/responses 可因 Codex OAuth 或前尘回退切换；
     * anthropic/gemini 恒为构造时协议。
     */
    internal fun lineProtocol(): WireProtocol = when {
        protocol == WireProtocol.ANTHROPIC_MESSAGES || protocol == WireProtocol.GEMINI_GENERATE_CONTENT -> protocol
        responsesFallbackActivated -> WireProtocol.RESPONSES
        else -> protocol
    }

    /**
     * [T-codex-gpt-image-2] gpt-image-2 在 Codex OAuth 实例上经 Responses 后端的
     * image_generation 工具生图（线上模型 gpt-5.5）；其余一切机型不受此门影响。
     */
    internal val isCodexImageRun: Boolean get() = isCodexOAuth && model.id == "gpt-image-2"

    /**
     * 文本块语义：Chat Completions 的 text 是单一整块（见上游接口注释）；responses
     * 流的是真正有序的 output items（同 anthropic/gemini），到达序即语义块序。
     */
    override val streamTextIsMonolithic: Boolean get() = lineProtocol() == WireProtocol.CHAT_COMPLETIONS

    /**
     * [T-android-enhanced-cache] Enhanced Cache（1 小时缓存 TTL）：每次请求前由
     * ChatViewModel 盖章（上游在 AnthropicProvider 上同名字段，绞杀后落在此处）。
     * 仅 anthropic 线消费；OpenAI 兼容/gemini 线设置无副作用。
     */
    var enhancedCache: Boolean = false

    /**
     * 生图改走自有 novex.model ImagesClient（P3.1d 移植）：行为面对齐被替换的上游
     * 路径——请求体键、b64_json 自动探测、url 条目下载、误路由 404 语义与错误分类
     * 矩阵（复用 [errorOf]）。model 在每次调用时取当前值，兜底换模型后生图请求仍
     * 用当前模型。OpenAI 族两方言（chat/responses）都开放（自 P3.1e 起九类实例
     * 全在适配器：官方直连/Azure/OpenRouter/xAI/Kimi 的生图随各自线走 ImagesClient；
     * Codex OAuth 的 gpt-image-2 走聊天线的 codex 生图体而非 Images API——Codex
     * 令牌无 Images scope，此处暴露与被替换实现同序：对 api.openai.com 打Images
     * 请求得 401）。anthropic/gemini 线无 Images API，返回 null（调用方回落「不
     * 支持生图」）。
     */
    val imageDelegate: com.openminis.app.provider.ImagesCapableProvider? get() =
        if (lineProtocol() == WireProtocol.CHAT_COMPLETIONS || lineProtocol() == WireProtocol.RESPONSES)
            AdapterImagesDelegate() else null

    /** [ImagesCapableProvider] 的自有实现体：阻塞传输在 Dispatchers.IO 上执行。 */
    private inner class AdapterImagesDelegate : com.openminis.app.provider.ImagesCapableProvider {
        private fun client() = novex.model.ImagesClient(
            base = URI(basePath),
            bearerToken = apiKey.takeIf { it.isNotEmpty() },
            userAgent = customUserAgent?.trim()?.takeIf { it.isNotEmpty() } ?: MinisUserAgent.DEFAULT,
            // [T-android-azure-openai] Azure 生图同样走 deployments 路径 + api-key 头。
            generationsUrl = azureUrl("/images/generations"),
            editsUrl = azureUrl("/images/edits"),
            tokenHeader = if (azureBase != null) "api-key" else null,
            permitQueryParams = azureBase != null,
        )

        override suspend fun generateImage(prompt: String, n: Int, size: String?, quality: String?) =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                mapImagesResult(client().generate(model.id, prompt, n, size, quality))
            }

        override suspend fun editImage(
            prompt: String,
            images: List<LLMMessage.ImagePart>,
            n: Int,
            size: String?,
            quality: String?,
        ) = kotlinx.coroutines.withContext(Dispatchers.IO) {
            mapImagesResult(client().edit(
                model.id, prompt,
                images.map { novex.model.ImageInput(it.mimeType, it.data) }, n, size, quality))
        }

        /** ImagesResult → LLMResponse / 抛错（HTTP 分类复用聊天线的 [errorOf] 矩阵）。 */
        private fun mapImagesResult(result: novex.model.ImagesResult): LLMResponse = when (result) {
            is novex.model.ImagesResult.Success -> LLMResponse(
                text = result.revisedPromptText,
                stopReason = "end_turn",
                usage = null,
                mediaAttachments = result.images.map { LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, it.mimeType, it.data) },
            )
            is novex.model.ImagesResult.HttpError -> throw errorOf(StreamChunk.Failure(result.message, result.status))
            is novex.model.ImagesResult.InvalidResponse -> throw LLMError.ProviderError(result.reason)
            novex.model.ImagesResult.TimedOut -> throw LLMError.NetworkError(
                SocketTimeoutException("images request read timeout from $name"))
            novex.model.ImagesResult.NetworkFailure -> throw LLMError.NetworkError(
                java.io.IOException("images request failed (network) from $name"))
        }
    }

    // ---------------------------------------------------------------------
    // 端点与鉴权（按线协议分线）
    // ---------------------------------------------------------------------

    private val loweredBase: String get() = basePath.lowercase()

    /** Azure 线（api-key 头 + deployments 路径）适用于 openAI 族两方言（chat/responses）。 */
    private val isAzureLine: Boolean get() =
        azureBase != null && lineProtocol() != WireProtocol.ANTHROPIC_MESSAGES &&
            lineProtocol() != WireProtocol.GEMINI_GENERATE_CONTENT

    internal fun completionUrl(): URI = completionUrl(lineProtocol())

    private fun completionUrl(line: WireProtocol): URI {
        val path = when (line) {
            WireProtocol.CHAT_COMPLETIONS -> "/chat/completions"
            WireProtocol.RESPONSES -> "/responses"
            else -> ""
        }
        return when {
            line == WireProtocol.GEMINI_GENERATE_CONTENT ->
                URI(basePath.trimEnd('/') + "/models/${model.id}:streamGenerateContent?alt=sse")
            line == WireProtocol.ANTHROPIC_MESSAGES -> URI(
                // 自定基址可能已带 /v1（effectiveBaseURL 的 appendV1Suffix），先剥再拼，
                // 否则出现 /v1/v1/messages。
                basePath.trimEnd('/').let { if (it.endsWith("/v1")) it.dropLast(3).trimEnd('/') else it } + "/v1/messages")
            isCodexOAuth && line == WireProtocol.RESPONSES -> URI(ResponsesWire.CODEX_BACKEND_URL)
            azureUrl(path) != null -> azureUrl(path)!!
            else -> URI(basePath.trimEnd('/') + path)
        }
    }

    /**
     * Azure deployments 路径（对齐官方 SDK 与被替换实现的 URL 形状）：
     *   {azure_endpoint}/openai/deployments/{model.id}/{path}?api-version=…
     * 用户粘贴的资源端点通常裸 `https://x.openai.azure.com`（可能已含 /openai 与
     * `?api-version=…` 查询）。剥离尾部 `/`、游离 `/v1`（Azure 无 /v1）与尾部
     * `/openai`（随后统一补回），再拼 deployments 路径。非 Azure 线返回 null。
     */
    internal fun azureUrl(path: String): URI? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val queryIndex = raw.indexOf('?')
        val query = if (queryIndex >= 0) raw.substring(queryIndex) else ""
        var base = (if (queryIndex >= 0) raw.substring(0, queryIndex) else raw).trimEnd('/')
        if (base.endsWith("/v1")) base = base.dropLast(3).trimEnd('/')
        if (base.endsWith("/openai")) base = base.dropLast("/openai".length).trimEnd('/')
        val endpointPath = path.removePrefix("/")
        return URI("$base/openai/deployments/${model.id}/$endpointPath$query")
    }

    private fun tokenOrNull(): String? = apiKey.takeIf { it.isNotEmpty() }

    /** 本请求实际令牌：动态 OAuth 每请求挂起解析（刷新感知），否则静态 key。 */
    internal suspend fun resolveToken(): String? =
        oauthTokenProvider?.invoke() ?: tokenOrNull()

    private val isOfficialAnthropic: Boolean get() = loweredBase.trimEnd('/') == "https://api.anthropic.com"

    private fun userAgent(line: WireProtocol): String {
        if (customUserAgent != null) {
            val trimmed = customUserAgent.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        // OAuth 的 Claude-Code 指纹 UA：Anthropic 后端以 UA + X-Stainless-* 组合
        // 识别官方 CLI，非 CLI 请求被降级（额外计费、思考被静默关闭）。
        if (line == WireProtocol.ANTHROPIC_MESSAGES && isAnthropicOAuth)
            return "claude-cli/2.1.195 (external, cli)"
        // Codex OAuth 的 codex_cli_rs 指纹 UA：ChatGPT 后端以该头校验客户端身份，
        // 绝不能回落品牌 UA（对齐被替换实现 applyUserAgentOverride(default=null)）。
        if (isCodexOAuth && line == WireProtocol.RESPONSES)
            return "codex_cli_rs/$CODEX_CLIENT_VERSION (Android; arm64)"
        return MinisUserAgent.DEFAULT
    }

    /** 基础出站头（不含随请求内容变化的 anthropic-beta）；真实调用见 [wireEndpoint]。 */
    internal fun outboundHeaders(): Map<String, String> = outboundHeaders(lineProtocol())

    private fun outboundHeaders(line: WireProtocol): Map<String, String> = when (line) {
        WireProtocol.CHAT_COMPLETIONS, WireProtocol.RESPONSES -> buildMap {
            put("User-Agent", userAgent(line))
            if (isCodexOAuth && line == WireProtocol.RESPONSES) putAll(codexFingerprintHeaders())
            // 附加出站头（OpenRouter 的 HTTP-Referer / X-Title），同名替换默认。
            putAll(extraHeaders)
        }
        WireProtocol.ANTHROPIC_MESSAGES -> buildMap {
            put("anthropic-version", "2023-06-01")
            put("User-Agent", userAgent(line))
            if (isAnthropicOAuth) putAll(oauthFingerprintHeaders())
            // 官方端点的 API key 走 x-api-key 头；OAuth 与自定中继走 Bearer（token 参数）。
            if (!isAnthropicOAuth && isOfficialAnthropic && apiKey.isNotEmpty()) put("x-api-key", apiKey)
        }
        WireProtocol.GEMINI_GENERATE_CONTENT -> buildMap {
            put("User-Agent", userAgent(line))
            if (apiKey.isNotEmpty()) put("x-goog-api-key", apiKey)
        }
    }

    /**
     * Codex OAuth（chatgpt.com Responses 后端）的客户端指纹头：Version /
     * Openai-Beta / Originator / User-Agent（codex_cli_rs）；账号 id 可选（缺失
     * 不 401）。UA 在 [userAgent] 单独装配，此处不重复。
     */
    private fun codexFingerprintHeaders(): Map<String, String> = buildMap {
        put("Version", CODEX_CLIENT_VERSION)
        put("Openai-Beta", "responses=experimental")
        put("Originator", "codex_cli_rs")
        codexAccountId?.takeIf { it.isNotEmpty() }?.let { put("Chatgpt-Account-Id", it) }
    }

    private fun oauthFingerprintHeaders(): Map<String, String> = mapOf(
        "X-Stainless-Lang" to "js",
        "X-Stainless-Package-Version" to "0.106.0",
        "X-Stainless-OS" to "Linux",
        "X-Stainless-Arch" to "arm64",
        "X-Stainless-Runtime" to "node",
        "X-Stainless-Runtime-Version" to "v24.18.0",
        "X-Stainless-Retry-Count" to "0",
        "X-Stainless-Timeout" to "600",
        "X-App" to "cli",
        "Anthropic-Dangerous-Direct-Browser-Access" to "true",
    )

    /**
     * 端点安全契约（https 或本机 http，含请求头与令牌合法性）：工厂在选择点先行
     * 判定。动态 OAuth 令牌此处以静态 key 代位（仅验形）；每请求的真实装配在
     * [wireEndpoint]。
     */
    internal fun endpointAcceptable(): Boolean =
        runCatching {
            ModelEndpoint(completionUrl(), bearerTokenOrNull(), outboundHeaders(),
                permitQueryParams = endpointFlags().permitQueryParams,
                tokenHeader = endpointFlags().tokenHeader)
            true
        }.getOrDefault(false)

    private fun endpointFlags(): ModelEndpointFlags {
        val azure = isAzureLine
        return ModelEndpointFlags(
            permitQueryParams = lineProtocol() == WireProtocol.GEMINI_GENERATE_CONTENT ||
                (azure && completionUrl().query != null),
            tokenHeader = if (azure) "api-key" else null,
        )
    }

    /** [ModelEndpoint] 的 azure/gemini 差异参数包。 */
    internal data class ModelEndpointFlags(val permitQueryParams: Boolean, val tokenHeader: String?)

    /** OpenAI 兼容/Responses 线与 anthropic OAuth/自定中转线的 Bearer 令牌；官方 anthropic 与 gemini 走请求头。 */
    internal fun bearerTokenOrNull(): String? = when (lineProtocol()) {
        WireProtocol.CHAT_COMPLETIONS, WireProtocol.RESPONSES -> tokenOrNull()
        // OAuth 或自定 Anthropic 中继 → Bearer；官方 API key → x-api-key 头；空 key 中继 → 不带鉴权头。
        WireProtocol.ANTHROPIC_MESSAGES -> when {
            isAnthropicOAuth -> tokenOrNull()
            isOfficialAnthropic -> null
            else -> tokenOrNull()
        }
        WireProtocol.GEMINI_GENERATE_CONTENT -> null
    }

    /**
     * 请求级端点：anthropic-beta 旗标随本请求的思考形态与增强缓存开关变化，因此
     * 在打开调用时（已知请求体）才装配；token 为本请求已解析的令牌（动态 OAuth）。
     */
    internal fun wireEndpoint(
        request: CompletionStreamRequest,
        token: String? = bearerTokenOrNull(),
    ): ModelEndpoint {
        val line = lineProtocol()
        val headers = outboundHeaders(line).toMutableMap()
        if (line == WireProtocol.ANTHROPIC_MESSAGES && request is AnthropicMessagesRequest) {
            val beta = anthropicBetaFlags(request)
            if (beta.isNotEmpty()) headers["anthropic-beta"] = beta.joinToString(",")
        }
        val flags = endpointFlags()
        return ModelEndpoint(completionUrl(line), token, headers,
            permitQueryParams = flags.permitQueryParams, tokenHeader = flags.tokenHeader)
    }

    /** anthropic-beta 旗标（对齐被替换实现）：OAuth 全家桶 / API key 只带请求体需要的。 */
    internal fun anthropicBetaFlags(request: AnthropicMessagesRequest): List<String> {
        if (isAnthropicOAuth) return listOf(
            "claude-code-20250219",
            "oauth-2025-04-20",
            "interleaved-thinking-2025-05-14",
            "prompt-caching-scope-2026-01-05",
            "effort-2025-11-24",
            "context-management-2025-06-27",
            "extended-cache-ttl-2025-04-11",
        )
        val flags=mutableListOf<String>()
        val shape=AnthropicWire.thinkingShape(model.id, model.supportsReasoning, request.thinkingLevel, request.maxTokens.toInt())
        when {
            shape.containsKey("effort") -> flags.add("effort-2025-11-24")
            // 与被替换实现同口径：请求体带 thinking 字段（enabled 与 disabled 皆是）
            // 即挂 interleaved 旗标。
            shape.isNotEmpty() -> flags.add("interleaved-thinking-2025-05-14")
        }
        if (request.cacheTtlOneHour) flags.add("extended-cache-ttl-2025-04-11")
        return flags
    }

    private fun openRealCall(): TransportCall {
        // 端点延迟到 stream() 装配（beta 旗标/令牌依赖请求内容）；取消先落标志位再硬断活跃调用。
        return object : TransportCall {
            @Volatile private var active: ChatCompletionCall? = null
            @Volatile private var cancelled = false
            @Volatile private var pendingToken: String? = null
            override fun cancel() { cancelled = true; active?.cancel() }
            override fun begin(token: String?) { pendingToken = token }
            override fun stream(request: CompletionStreamRequest, onChunk: (StreamChunk) -> Unit): StreamResult {
                if (cancelled) return StreamResult.Cancelled
                val line = lineProtocol()
                val call = ChatCompletionCall(
                    wireEndpoint(request, pendingToken ?: bearerTokenOrNull()),
                    CALL_TIMEOUT_MILLIS, line,
                )
                active = call
                return try {
                    call.stream(request, BYPASS_CAPACITY, BYPASS_WINDOW, { BYPASS_MEASUREMENT }, onChunk)
                } finally { active = null }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 请求装配
    // ---------------------------------------------------------------------

    /** 按线协议分发请求装配；四个方言共享同一条 WireMessage 组装线。 */
    internal fun buildWireRequest(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): CompletionStreamRequest = when {
        // [T-codex-gpt-image-2] gpt-image-2 在 Codex OAuth 上是固定指纹生图体——
        // 非 chat 非 responses 的第三种形状（gpt-5.5 线上模型 + image_generation 工具）。
        isCodexImageRun -> ResponsesWire.codexImageRequest(lastUserPrompt(messages))
        else -> when (lineProtocol()) {
            WireProtocol.CHAT_COMPLETIONS -> buildStreamRequest(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
            WireProtocol.ANTHROPIC_MESSAGES -> buildAnthropicRequest(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
            WireProtocol.GEMINI_GENERATE_CONTENT -> buildGeminiRequest(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
            WireProtocol.RESPONSES -> buildResponsesRequest(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        }
    }

    /** 末条 user 消息的纯文本（codex 生图指令的 prompt 源），空则空串。 */
    private fun lastUserPrompt(messages: List<LLMMessage>): String {
        val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER } ?: return ""
        return lastUser.content.takeIf { it.isNotBlank() }
            ?: lastUser.contentParts.filterIsInstance<AgentContentPart.Text>()
                .joinToString(" ") { it.text }.trim()
    }

    private fun assembleWireMessages(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        imageParts: List<LLMMessage.ImagePart>,
        thinkingLevel: ThinkingLevel,
    ): List<WireMessage> {
        val idRegistry = ToolCallIdRegistry()
        return buildList {
            if (systemPrompt != null) add(WireMessage("system", systemPrompt))
            val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
            for ((index, message) in messages.withIndex()) {
            if (message.contentParts.isNotEmpty()) addAll(structuredParts(message, thinkingLevel, idRegistry))
            else addAll(legacyMessage(message, index == lastUserIndex, imageParts, thinkingLevel, idRegistry))
            }
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
        val wire = assembleWireMessages(messages, systemPrompt, imageParts, thinkingLevel)
        val toolDefs = tools.map { definition ->
            val parameters = definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
            ToolDefinition(definition.name, definition.description, parameters.toString())
        }
        val extras = thinkingParameters(maxTokens, thinkingLevel) ?: JSONObject()
        // token 上限键按主机选择（贴已删上游 openai 包口径）：OpenRouter 主机收
        // max_tokens（wire() 默认键），其余一切端点收 max_completion_tokens——OpenAI
        // 对 o 系/gpt-5 拒收 max_tokens（整线 400），中转前端同受此约束。
        if (!isOpenRouterHost) extras.put("max_completion_tokens", maxTokens)
        // [OpenMinis#191] OpenRouter 不自动给 Claude 系开 Anthropic prompt caching——
        // 必须显式携带顶层 cache_control 断点，否则 cache_read/write 恒 0（3-6 倍成本
        // 超支）。门槛=主机匹配 + anthropic/ 模型前缀，其余模型请求体逐字节不变。
        if (needsOpenRouterAnthropicCacheControl) {
            extras.put("cache_control", JSONObject().put("type", "ephemeral"))
        }
        return StreamRequest(
            TextRequest(model.id, wire, maxTokens.toLong(), toolDefs, extras.takeIf { it.length() > 0 }),
            includeUsage = !isOpenRouterHost,
        )
    }

    /**
     * Responses 线请求（P3.1e）：Codex OAuth（chatgpt.com 后端指纹体约束）与
     * useResponsesAPI 自定端点共用。思考档映射 WireThinkingLevel，Mistral 两头
     * 不发（闭 schema 拒收 reasoning 参数）；MiMo/Agnes 的 xhigh 折叠 high
     * （其后端只收 low/medium/high）；Fast 档（service_tier=priority）仅 gpt 系。
     */
    internal fun buildResponsesRequest(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): ResponsesStreamRequest {
        val wire = assembleWireMessages(messages, systemPrompt, imageParts, thinkingLevel)
        val toolDefs = tools.map { definition ->
            val parameters = definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
            ToolDefinition(definition.name, definition.description, parameters.toString())
        }
        val lowerId = model.id.lowercase()
        val effortFamilyClamp = lowerId.contains("mimo") || lowerId.contains("agnes")
        val level = if (thinkingLevel.isEnabled && !isMistralHost) {
            // xhigh → high（effort 家族钳制：被替换实现的 clampEffortForModel 同口径）。
            if (effortFamilyClamp && thinkingLevel == ThinkingLevel.XHIGH) ThinkingLevel.HIGH.toWireLevel()
            else thinkingLevel.toWireLevel()
        } else null
        val offEffort = if (!thinkingLevel.isEnabled && !isMistralHost &&
            model.supportsReasoning == true && !effortFamilyClamp
        ) explicitOffEffort else null
        AppLogger.info(
            "Thinking",
            "[resolve] provider=responses model=${model.id} level=${thinkingLevel.name} " +
                "effort=${level?.name ?: "<omit>"} off=${offEffort ?: "<omit>"}",
        )
        return ResponsesStreamRequest(
            model = model.id,
            messages = wire,
            maxOutputTokens = maxTokens.toLong(),
            tools = toolDefs,
            thinkingLevel = level,
            offEffort = offEffort,
            supportsReasoning = model.supportsReasoning,
            isCodexOAuth = isCodexOAuth,
            // [T-codex-fast-mode] Fast 档 wire 值是 priority（"fast" 只是 UI 名）；
            // Responses 中转会透传该档，ineligible 上游忽略或静默降档。
            serviceTierPriority = com.openminis.app.data.FastModePrefs.isEnabled() &&
                model.id.contains("gpt", ignoreCase = true),
        )
    }

    /** Anthropic Messages 请求：思考形态/缓存 TTL/OAuth 前缀块/交错思考回放均在此落参。 */
    internal fun buildAnthropicRequest(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): AnthropicMessagesRequest {
        val wire = assembleWireMessages(messages, systemPrompt, imageParts, thinkingLevel)
        val toolDefs = tools.map { definition ->
            ToolDefinition(definition.name, definition.description,
                definition.toAnthropicJson().getJSONObject("input_schema").toString())
        }
        val level = thinkingLevel.toWireLevel()
        AppLogger.info(
            "Thinking",
            "[resolve] provider=anthropic model=${model.id} level=${thinkingLevel.name} " +
                "shape=[${AnthropicWire.thinkingShape(model.id, model.supportsReasoning, level, maxTokens)
                    .entries.joinToString(",") { "${it.key}=${it.value}" }}]",
        )
        return AnthropicMessagesRequest(
            model = model.id,
            messages = wire,
            maxTokens = maxTokens.toLong(),
            tools = toolDefs,
            thinkingLevel = level,
            supportsReasoning = model.supportsReasoning,
            cacheTtlOneHour = enhancedCache,
            isOAuth = isAnthropicOAuth,
            // P3.1d 净眼挂账 e 前缀可注入（默认取 BuildConfig 定制常量）：公共镜像
            // 未配置该常量时，测试注入已知前缀也能跑满前缀拆分断言块。
            oauthSystemPrefix = if (isAnthropicOAuth)
                (oauthSystemPrefixOverride ?: ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT) else null,
            echoUnsignedThinking = echoUnsignedAnthropicThinking(),
        )
    }

    /** 测试注入的 OAuth 系统前缀（非空时优先于 BuildConfig 常量）；生产恒 null。 */
    internal var oauthSystemPrefixOverride: String? = null

    /**
     * Anthropic 兼容中继（DeepSeek V4 / GLM / Kimi 等说 Anthropic 协议但交错无签名
     * 思考的机型）要求历史 assistant 轮回放 thinking 块，否则 400。官方端点校验
     * signature 字段，不能回放——与被替换实现的门槛一致。
     */
    private fun echoUnsignedAnthropicThinking(): Boolean =
        protocol == WireProtocol.ANTHROPIC_MESSAGES &&
            model.supportsReasoning != false &&
            model.interleavedReasoningField != null &&
            !isOfficialAnthropic

    /** Gemini 请求：思考配置经规则解析器（golden 快照钉行为），模态声明按模型产出能力。 */
    internal fun buildGeminiRequest(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): GeminiGenerateContentRequest {
        val wire = assembleWireMessages(messages, systemPrompt, imageParts, thinkingLevel)
        val toolDefs = tools.map { definition ->
            val gemini = definition.toGeminiJson()
            ToolDefinition(definition.name, definition.description,
                definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters").toString(),
                propertyOrdering = gemini.optJSONObject("parameters")?.optJSONArray("propertyOrdering")
                    ?.let { ordering -> (0 until ordering.length()).map { ordering.optString(it) } })
        }
        AppLogger.info(
            "Thinking",
            "[resolve] provider=gemini model=${model.id} level=${thinkingLevel.name} " +
                "keys=[${ThinkingContractResolver.geminiThinkingConfig(model.id, thinkingLevel)
                    ?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}]",
        )
        val outputs = model.outputModalities.orEmpty()
        return GeminiGenerateContentRequest(
            model = model.id,
            messages = wire,
            maxOutputTokens = maxTokens.toLong(),
            tools = toolDefs,
            thinkingConfig = ThinkingContractResolver.geminiThinkingConfig(model.id, thinkingLevel),
            responseModalities = when {
                "audio" in outputs -> listOf("AUDIO")
                "image" in outputs -> listOf("TEXT", "IMAGE")
                else -> emptyList()
            },
            rejectsSystemInstruction = "audio" in outputs,
            requiresThoughtSignature = model.id.lowercase().contains("gemini-3"),
        )
    }

    private fun ThinkingLevel.toWireLevel(): WireThinkingLevel? =
        if (isEnabled) WireThinkingLevel.valueOf(name) else null

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
                        PendingTool(idRegistry.declare(use.id), use.name, use.input.toString(), use.thoughtSignature)
                    },
                    reasoningContent = historyReasoningContent(message, thinkingLevel),
                ),
            )
        }
        LLMMessage.Role.USER -> buildList {
            for (result in message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                // 无法配对到本批工具调用的孤儿结果直接丢弃：发出去整单必被
                // 网关 400，丢弃只是少一条历史污染。
                idRegistry.resolve(result.id)?.let { paired ->
                    // P3.1d 净眼挂账 a 工具结果内嵌图片补发（对齐被替换 AnthropicProvider
                    // 语义）：截图类工具产出随 tool_result 以 image 块补发；字节先过
                    // ImageBudget 压缩兜底（历史里可能存着未经预算的原始大图）。仅
                    // anthropic 方言消费 tool 图片（OpenAI 兼容/gemini 线编码不读）。
                    val images = if (protocol == WireProtocol.ANTHROPIC_MESSAGES && result.imageData != null) {
                        // P3.1d 净眼建议 ③：工具结果 mime 缺失时按魔数探测（而非假定 png）
                        // ——截图类工具产出常见 jpeg/webp，假定错 mime 会被 anthropic
                        // 端按 media_type 校验拒绝。
                        val mime = result.imageMimeType ?: detectImageMime(result.imageData!!)
                        listOfNotNull(encodeImage(result.imageData!!, mime))
                    } else emptyList()
                    add(WireMessage(role = "tool", text = result.content, toolCallId = paired, isError = result.isError, images = images))
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
            // P3.1d 净眼挂账 b 孤儿 tool_result 语义对齐被替换实现：一批工具调用的
            // 配对窗口只活到下一条 user 消息——过一条 user 即失效，之后到达的迟到
            // 结果按孤儿丢弃，不再允许匹配旧批次（发出去会被网关 400）。
            idRegistry.expireBatch()
        }
    }

    /** 旧式消息：纯文本；最后一条 user 消息挂顶层图片部件；音频部件仅 OpenAI 兼容线发。 */
    private fun legacyMessage(
        message: LLMMessage,
        isLastUser: Boolean,
        imageParts: List<LLMMessage.ImagePart>,
        thinkingLevel: ThinkingLevel,
        idRegistry: ToolCallIdRegistry,
    ): List<WireMessage> {
        if (message.role == LLMMessage.Role.ASSISTANT) {
            return listOf(
                WireMessage("assistant", message.content, reasoningContent = historyReasoningContent(message, thinkingLevel)),
            )
        }
        // 旧式 user 轮同样让配对窗口过期（净眼挂账 b：过一条 user 即失效）。
        idRegistry.expireBatch()
        val attachImages = isLastUser && imageParts.isNotEmpty()
        // 音频块仅 OpenAI 族两方言发（chat 的 input_audio / responses 的 input_audio 同形）。
        val audioCapableLine = lineProtocol() == WireProtocol.CHAT_COMPLETIONS || lineProtocol() == WireProtocol.RESPONSES
        if (!attachImages && (message.audioParts.isEmpty() || !audioCapableLine))
            return listOf(WireMessage("user", message.content))
        val text = StringBuilder(message.content)
        val images = mutableListOf<WireImage>()
        if (attachImages) {
            for (part in imageParts) {
                val encoded = encodeImage(part.data, part.mimeType)
                if (encoded != null) images += encoded
                else text.append('\n').append(part.noVisionPlaceholder ?: NO_VISION_PLACEHOLDER)
            }
        }
        val audios = if (audioCapableLine)
            message.audioParts.map { WireAudio(it.format, it.base64Data) } else emptyList()
        return listOf(WireMessage("user", text.toString(), images = images, audios = audios))
    }

    /**
     * assistant 历史 reasoning_content 回放门槛，按线协议分置：
     *  - OpenAI 兼容线：思考开启或必思考机型才回放（DeepSeek 系闭 schema）；
     *  - anthropic：兼容中继的交错思考回放（与思考开关无关，官方端点不回放）；
     *  - gemini：思考不回放（只回放 thoughtSignature），恒 null。
     */
    private fun historyReasoningContent(message: LLMMessage, thinkingLevel: ThinkingLevel): String? = when (lineProtocol()) {
        WireProtocol.CHAT_COMPLETIONS -> {
            if (isMistralHost) null
            else {
                val modelAlwaysReasons = model.supportsReasoning == true
                val modelMayReason = model.supportsReasoning ?: true
                if (!modelMayReason) null
                else if (!thinkingLevel.isEnabled && !modelAlwaysReasons) null
                else message.reasoningContent ?: ""
            }
        }
        WireProtocol.ANTHROPIC_MESSAGES -> if (echoUnsignedAnthropicThinking()) message.reasoningContent ?: "" else null
        // Responses 的 input items 不回放 reasoning_content（对齐被替换实现——
        // Codex 加密思考经 include 回放，非 Responses 方言自有键）。
        WireProtocol.RESPONSES, WireProtocol.GEMINI_GENERATE_CONTENT -> null
    }

    /**
     * 思考等级 → 请求体参数（OpenAI 兼容线）。沿用思考规则解析器（纯函数、
     * golden 快照钉行为），在空对象上解析出本请求的思考键，作为附加顶层参数并入请求。
     * Mistral 端点两头都不发（请求参数与 reasoning_content 均被其闭 schema 拒绝）。
     */
    private fun thinkingParameters(maxTokens: Int, level: ThinkingLevel): JSONObject? {
        if (isMistralHost) return null
        val holder = JSONObject()
        ThinkingContractResolver.apply(
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
     * 图片 → WireImage。先过图片预算再内联。图片学习式降级（乐观发送）：仅
     * OpenAI 兼容两方言（chat/responses）参与降级学习（anthropic/gemini 与被替换
     * 实现一致恒真实发送）。
     */
    private fun encodeImage(data: ByteArray, mimeType: String): WireImage? {
        if (lineProtocol() == WireProtocol.CHAT_COMPLETIONS || lineProtocol() == WireProtocol.RESPONSES) {
            val supportsImages = model.hasImageInput || model.id !in ImageDegradationLearning.imageDegradedModels
            if (!supportsImages) return null
        }
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

    /** 图片魔数探测（png/jpeg/webp/gif；过短或未识别回落 png）——工具结果 mime 缺失时兜底。 */
    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        return when {
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png"
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() && data[3] == 0x46.toByte() -> "image/webp"
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() -> "image/gif"
            else -> "image/png"
        }
    }

    // ---------------------------------------------------------------------
    // 端点嗅探（供思考规则与 include_usage 决策；仅 OpenAI 兼容线使用）
    // ---------------------------------------------------------------------

    private val isOpenRouterHost: Boolean get() = loweredBase.contains("openrouter.ai")
    /** OpenRouter 上的 Claude 系（anthropic/ 前缀）需要显式 cache_control 断点（见 buildStreamRequest）。 */
    private val needsOpenRouterAnthropicCacheControl: Boolean
        get() = isOpenRouterHost && model.id.lowercase().startsWith("anthropic/")
    private val isMistralHost: Boolean get() = loweredBase.contains("mistral.ai")
    private val isDashScopeHost: Boolean get() = loweredBase.contains("dashscope")
    private val isXAIHost: Boolean get() = loweredBase.contains("api.x.ai") || loweredBase.contains("//x.ai")
    private val usesUnifiedReasoningEffort: Boolean
        get() = isAzureLine || loweredBase.contains("volces") || loweredBase.contains("ark.") ||
            loweredBase.contains("api.venice.ai")

    private val explicitOffEffort: String?
        get() {
            // Azure 的关档随机型而异（gpt-5.1+ none / 初代 gpt-5 minimal / o1o3 不支持），
            // 显式值有 400 风险——保持省略（对齐被替换实现）。
            if (isAzureLine) return null
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
        // 注意：temperature 被丢弃——当前全部调用点都传 null（gpt-5 系兼容），
        // novex.model 请求体也不含该键；anthropic 线的协议性 temperature=1（legacy
        // 思考）在请求编码内自决。这是记录在案的刻意不对齐。
        if (lineProtocol() != WireProtocol.CHAT_COMPLETIONS && lineProtocol() != WireProtocol.RESPONSES) {
            // anthropic/gemini 与被替换实现一致：静默空完成（无内容也无收尾原因）
            // 按瞬态上游失败抛错，进既有自动重试链。
            return rawStream(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
                .failOnSilentEmptyCompletion(name)
        }
        // 图片学习式降级（乐观发送）：带图请求先按真实像素发送；首块前失败且
        // 报错指向图片输入时，记入降级集并用中性占位重试一次（chat/responses 两
        // 方言；anthropic/gemini 与被替换实现一致恒真实发送）。
        val requestHasImages = imageParts.isNotEmpty() ||
            messages.any { it.imageParts.isNotEmpty() || it.contentParts.any { part -> part is AgentContentPart.ImageData } }
        val raw: () -> Flow<LLMStreamChunk> = {
            withResponsesFallback(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        }
        if (!requestHasImages || model.id in ImageDegradationLearning.imageDegradedModels) return raw()
        var emittedAny = false
        return raw()
            .onEach { emittedAny = true }
            .catch { failure ->
                if (failure is kotlinx.coroutines.CancellationException || emittedAny) throw failure
                if (!ImageDegradationLearning.looksLikeImageRejection(failure.message)) throw failure
                AppLogger.warning(
                    "NovexTransport",
                    "[ImageDegrade] endpoint rejected image input for ${model.id} — retrying once with text placeholders",
                )
                ImageDegradationLearning.imageDegradedModels.add(model.id)
                emitAll(raw())
            }
    }

    /**
     * [T-qianchen-preset] chat → responses 自动回退（仅 [allowResponsesFallback] 的
     * 实例，当前只有内置前尘预设）。chat 首个数据块前失败（HTTP 4xx/5xx 或网络
     * 异常）时，同一实例自动改走 /v1/responses 重试一次；进程内粘性——重试真的
     * 产出首块后，本实例（含工厂重建的同 id provider 对象）后续请求直接走
     * responses，省一次必败的 chat 往返。已是 responses 模式（useResponsesAPI/
     * Codex OAuth）或 anthropic/gemini 线不参与。粘性等首块再落——若 responses
     * 也失败，下一回合仍从 chat 试起，不锁死。
     */
    private fun withResponsesFallback(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> {
        if (!allowResponsesFallback || lineProtocol() != WireProtocol.CHAT_COMPLETIONS) {
            return rawStream(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        }
        if (!responsesFallbackActivated) {
            instanceId?.let { if (it in responsesFallbackSticky) responsesFallbackActivated = true }
        }
        if (responsesFallbackActivated) {
            return rawStream(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        }
        var emittedAny = false
        return rawStream(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
            .onEach { emittedAny = true }
            .catch { failure ->
                if (failure is kotlinx.coroutines.CancellationException || emittedAny) throw failure
                AppLogger.warning(
                    "NovexTransport",
                    "[ResponsesFallback] chat failed before first chunk " +
                        "(${failure.message}) — retrying the same request via /v1/responses",
                )
                responsesFallbackActivated = true
                var stickyCommitted = false
                emitAll(
                    rawStream(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
                        .onEach {
                            if (!stickyCommitted) {
                                stickyCommitted = true
                                instanceId?.let(responsesFallbackSticky::add)
                            }
                        },
                )
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
        // 动态 OAuth 令牌每请求解析（挂起刷新；失败按 InvalidApiKey 收流——令牌
        // 不可用是配置态而非瞬态网络错，不进重试链）。
        val token = try {
            resolveToken()
        } catch (failure: Throwable) {
            close(if (failure is LLMError) failure else LLMError.ProviderError(failure.message ?: "令牌解析失败"))
            return@callbackFlow
        }
        // 请求装配（配对校验等）失败 → 确定性收流。
        val request = try {
            buildWireRequest(messages, systemPrompt, maxTokens, imageParts, tools, thinkingLevel)
        } catch (failure: Throwable) {
            close(if (failure is LLMError) failure else LLMError.ProviderError(failure.message ?: "请求装配失败"))
            return@callbackFlow
        }
        // 净眼 P3.1c 退回 must-fix：明文 LAN 中继既不换管也不回上游，请求前置预检
        // 端点安全契约（以本请求已解析令牌装配），不通过时以确定性 ProviderError
        // （中文可读）收流——绝不映射成 NetworkError 进瞬态重试链，用户看到的是
        // 配置错误而非无谓重试。
        val endpointOk = runCatching { wireEndpoint(request, token); true }.getOrDefault(false)
        if (!endpointOk) {
            close(
                LLMError.ProviderError(
                    "端点不满足自有传输的安全契约：仅支持 https 或本机回环地址" +
                        "（http 仅限 127.0.0.1 / localhost / ::1）。请为该供应商实例改配 https 基址。",
                ),
            )
            return@callbackFlow
        }
        val call = openCall()
        call.begin(token)
        // [诊断 parity] 与上游同款的 ModelRequestAudit 事件：wire_request /
        // response_headers / http_error，凭据照走 safeText/safeUrl 脱敏。
        val audit = kotlinx.coroutines.currentCoroutineContext()[com.openminis.app.diagnostics.ModelRequestAudit]
        val secrets = listOfNotNull(token ?: tokenOrNull())
        audit?.event(
            "wire_request",
            JSONObject()
                .put("url", com.openminis.app.diagnostics.ModelRequestAudit.safeText(
                    com.openminis.app.diagnostics.ModelRequestAudit.safeUrl(completionUrl().toString()), secrets))
                .put("providerInstanceId", instanceId)
                .put("method", "POST")
                .put("protocol", when (lineProtocol()) {
                    WireProtocol.CHAT_COMPLETIONS -> "chat_completions"
                    WireProtocol.ANTHROPIC_MESSAGES -> "anthropic"
                    WireProtocol.GEMINI_GENERATE_CONTENT -> "gemini"
                    WireProtocol.RESPONSES -> "responses"
                })
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
                    // 三家线的 SSE 成功响应恒为 200；自有传输只报「2xx 已到」，
                    // 此处以 200 记账。
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
                    // 不发 Finished —— 与上游「静默截断」表现一致，交由调用方裁决
                    // （anthropic/gemini 线再被 failOnSilentEmptyCompletion 拦成瞬态重试）。
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
        val media = mutableListOf<LLMMediaAttachment>()
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
                is LLMStreamChunk.MediaAttachment -> media += chunk.attachment
                else -> Unit
            }
        }
        return LLMResponse(text.toString(), stopReason, usage, media)
    }

    /**
     * 失败块 → 上游错误分类，三条来源各自对齐：
     *  - HTTP 非 200（category 非空）：401/403 鉴权、429 限流、5xx 瞬态、其余供应商错误；
     *  - 流中 error 对象（category 为空、code 为字符串）：按上游 mapHttpError 的数字
     *    状态矩阵分类——401/403 鉴权、429 限流、500/502/503/504/529 瞬态（503 带
     *    no_available_providers / model_not_found 归供应商错误以触发组回退）、其余及
     *    非数字 code（同上游 optInt 默认 0）归供应商错误；anthropic 的 overloaded_error
     *    等字符串类型按被替换实现的语义归类（overloaded → 瞬态）；
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
                status == 429 || failure.code == "rate_limit_error" -> LLMError.RateLimited(detail)
                failure.code == "overloaded_error" -> LLMError.TransientError(detail)
                // Responses 的 response.failed 两类瞬态码（对齐被替换实现：同模型原地
                // 重试而非组回退）。仅 responses 线——chat 线的同名字符串 code 走
                // 上游 optInt 默认 0 的供应商错误口径（既有矩阵测试钉住）。
                (failure.code == "server_error" || failure.code == "rate_limit_exceeded") &&
                    lineProtocol() == WireProtocol.RESPONSES -> LLMError.TransientError(detail)
                failure.code == "authentication_error" || failure.code == "permission_error" ->
                    openCodeSunsetFriendlyError(failure.message) ?: LLMError.InvalidApiKey(detail)
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

        /**
         * Codex OAuth 客户端版本（Version / User-Agent 指纹头共用）。0.142.3 →
         * 0.144.1 对齐 CLIProxyAPI/sub2api 上游（修复旧客户端上的 gpt-5.6-luna
         * 404）。后续升版本只动这一处。
         */
        const val CODEX_CLIENT_VERSION = "0.144.1"

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
     *    路径的实际顺序，下游已按此消化（anthropic 的 message_stop / gemini 的
     *    干净断流收尾走同一顺序）；
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
            var signature: String? = null
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
                            // P3.1d 净眼挂账 c anthropic 方言的 prompt caching 计量
                            // （cache_creation/cache_read），对齐被替换实现的映射；其余
                            // 方言两键恒 null。
                            cacheCreationInputTokens = chunk.cacheCreationTokens?.toInt()?.takeIf { it > 0 },
                            cacheReadInputTokens = chunk.cacheReadTokens?.toInt()?.takeIf { it > 0 },
                            latestContextTokens = (chunk.inputTokens ?: 0).toInt(),
                        ),
                    ),
                )
                is StreamChunk.MediaAttachment -> {
                    // gemini 图像/音频输出机型的 inlineData：按 mime 归类后透传。
                    val type = when {
                        chunk.mimeType.startsWith("audio/") -> LLMMediaAttachment.MediaType.AUDIO
                        chunk.mimeType.startsWith("video/") -> LLMMediaAttachment.MediaType.VIDEO
                        else -> LLMMediaAttachment.MediaType.IMAGE
                    }
                    val bytes = try { java.util.Base64.getDecoder().decode(chunk.base64) } catch (_: Exception) { return }
                    send(LLMStreamChunk.MediaAttachment(LLMMediaAttachment(type, chunk.mimeType, bytes)))
                }
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
            chunk.signature?.let { acc.signature = it }
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
                send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args, acc.signature))
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
 * [T-qianchen-preset] responses 回退的进程级粘性：key = 供应商实例 id。chat 失败
 * 并成功切到 responses 的实例，本进程内后续请求（含工厂重建的新 provider 对象）
 * 直接走 responses，省一次必败的 chat 往返。重启后自然复位，重新探测。
 */
private val responsesFallbackSticky: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

/**
 * [T-opencode-sunset] OpenCode Zen 的免费档 403 第三方调用（2025-09 起服务端
 * 限制仅 OpenCode 客户端内可用）。没有这条映射，403 会被归为 InvalidApiKey
 * （「API 密钥错误」）——错误归因且引导用户去找一把根本不存在的钥匙。防御路径：
 * 仍绑着已停用 builtin-opencode-free 实例的会话。（自上游 openai 包迁入，随
 * P3.1e 整包删除落地。）
 */
internal fun openCodeSunsetFriendlyError(message: String): LLMError.ProviderError? =
    if (message.contains("free tier", ignoreCase = true)) {
        LLMError.ProviderError(
            "OpenCode 免费模型已停止服务：官方已限制仅 OpenCode 客户端内使用，请切换其他模型",
        )
    } else {
        null
    }

/**
 * 工具调用 id 规范化（按批次配对）：
 *  - assistant 声明批次（beginBatch + declare）：超长 id 折成确定性短 id，
 *    跨消息重复 id 改名——部分兼容网关以 tool_call_id 重复为由整单 400；
 *  - tool 结果（resolve）取本批配对 id；配不上的孤儿结果由调用方丢弃；
 *  - 批次配对窗口只活到下一条 user 消息（expireBatch，对齐被替换实现的
 *    「过一条 user 即失效」）：迟到的旧批结果按孤儿丢弃，绝不跨 user 轮匹配。
 * 同一批内配对一致性由 WireMessage 的装配校验兜底。
 */
private class ToolCallIdRegistry {
    private val used = mutableSetOf<String>()
    private var openBatch: Map<String, String> = emptyMap()

    fun beginBatch() { openBatch = emptyMap() }

    /** user 消息处理完毕后关闭配对窗口：旧批次的原始 id 不再可解析。 */
    fun expireBatch() { openBatch = emptyMap() }

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
