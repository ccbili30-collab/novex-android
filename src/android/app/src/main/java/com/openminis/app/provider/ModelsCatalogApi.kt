package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.NovexDeepSeekModelMetadata
import com.openminis.app.data.model.ReportedContextWindow
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.model.ModelsCatalog
import java.net.URI

/**
 * P3.1d 模型目录拉取（GET /models 三方言）——上游 AnthropicModelsApi /
 * GeminiModelsApi / OpenAIModelsApi 的自有替代。传输与解析纯函数面在 novex.model
 * [ModelsCatalog]（零上游依赖）；本包装器承担 Android 侧关注点：7 天磁盘缓存
 * （[ProviderModelsCache]，anthropic 由无命名空间旧缓存并入 "anthropic" 命名空间）、
 * 官方端点内置兜底列表、models.dev 增补与 LLMModel 映射。
 *
 * 行为对齐被替换实现；两处记录在案的等价改写：
 *  - gemini API key 由 `?key=` 查询参数改为 `x-goog-api-key` 请求头（服务端等价，
 *    令牌不进 URL——自有传输的端点契约）；
 *  - anthropic 缓存落 `models-cache/anthropic/`（旧 `models-cache/` 直接条目自然
 *    过期，缓存为建议性数据，无迁移必要）。
 */
object ModelsCatalogApi {
    private const val TAG = "ModelsCatalogApi"
    private const val TIMEOUT_MILLIS = 30_000
    private val anthropicCache = ProviderModelsCache("anthropic")
    private val geminiCache = ProviderModelsCache("gemini")
    private val openAiCache = ProviderModelsCache("openai")

    // -----------------------------------------------------------------
    // anthropic 方言
    // -----------------------------------------------------------------

    /**
     * Anthropic 模型目录。自定义基址逐层爬升（厂商子路径借主机根 /v1/models 发现）；
     * 自定义端点失败返回空表（调用方回落 models.dev），仅官方端点给内置兜底列表。
     */
    suspend fun fetchAnthropicModels(
        apiKey: String,
        baseURL: String? = null,
        isOAuth: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] 模型目录 UA 覆盖；null/空白用默认品牌 UA。
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = (baseURL ?: "") + "|" + apiKey + "|" + isOAuth
        if (context != null && !forceRefresh) {
            anthropicCache.load(context, cacheKey)?.let { return@withContext it }
        }
        val isCustomEndpoint = baseURL != null
        val userAgent = customUserAgent?.trim()?.takeIf { it.isNotEmpty() } ?: MinisUserAgent.DEFAULT
        val authHeaders = when {
            // OAuth：Bearer + 必备 beta 头（镜像 iOS fetchModels(oauthToken:)）。
            isOAuth -> mapOf("Authorization" to "Bearer $apiKey", "anthropic-beta" to "oauth-2025-04-20")
            // 自定义端点：Bearer；官方：x-api-key。
            baseURL != null -> mapOf("Authorization" to "Bearer $apiKey")
            else -> mapOf("x-api-key" to apiKey)
        }
        val headers = authHeaders + mapOf(
            "anthropic-version" to "2023-06-01",
            "User-Agent" to userAgent,
        )

        var lastFailureCode = -1
        // 首个 HTTP 成功响应即定局：解析失败/空表回落兜底（自定义端点→空表，官方→
        // 内置列表），不再继续爬升——对齐被替换实现。
        val successFallback = if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic
        for ((index, candidate) in ModelsCatalog.anthropicCandidateBases(baseURL).withIndex()) {
            // 首层（用户基址）只拼 /models；爬升的父路径层保持 /v1 自动补齐（发现约定）。
            val url = ModelsCatalog.anthropicModelsUrl(candidate, forceV1Discovery = index > 0)
            when (val outcome = ModelsCatalog.get(URI(url), headers, TIMEOUT_MILLIS, permitQueryParams = true)) {
                is ModelsCatalog.FetchResult.NetworkFailure ->
                    AppLogger.warning(TAG, "anthropic models fetch error (level=$index url=$url)")
                is ModelsCatalog.FetchResult.HttpError -> {
                    AppLogger.warning(TAG, "anthropic models HTTP ${outcome.status} (level=$index): ${outcome.body.take(200)}")
                    // 401/403 几乎必是凭据轮换：丢弃过期缓存，下次直接打网络。
                    if (context != null && (outcome.status == 401 || outcome.status == 403)) {
                        anthropicCache.invalidate(context, cacheKey)
                    }
                    lastFailureCode = outcome.status
                }
                is ModelsCatalog.FetchResult.Success -> {
                    val parsed = ModelsCatalog.parseAnthropic(outcome.body)
                        ?.map { LLMModel(it.id, it.displayName, "Anthropic", supportsReasoning = it.supportsReasoning) }
                    val models = if (parsed.isNullOrEmpty()) successFallback else ModelsDevApi.enrichModels(parsed)
                    if (context != null && !parsed.isNullOrEmpty()) anthropicCache.save(context, cacheKey, models)
                    return@withContext models
                }
            }
        }
        AppLogger.warning(TAG, "anthropic models: all ${ModelsCatalog.anthropicCandidateBases(baseURL).size} candidate bases failed; lastCode=$lastFailureCode")
        if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic
    }

    // -----------------------------------------------------------------
    // gemini 方言
    // -----------------------------------------------------------------

    /**
     * Gemini 模型目录。三种鉴权形态：API key（x-goog-api-key 头）、OAuth（Bearer）、
     * Cloud Code Assist（无公开目录，cloudCodeFallback 直取内置列表）。OAuth 403
     * （令牌缺 generative-language scope）回落内置列表而非报错。
     *
     * 网络异常（IOException）原样上抛而非回落内置表（P3.1d 净眼建议 ①，对齐被删
     * 的上游 GeminiModelsApi：execute() 无吞网——「拉不到目录」要报给用户看，静默
     * 回落内置表会把网络问题伪装成模型清单）。HTTP 错误仍回落内置列表（上游语义）。
     */
    suspend fun fetchGeminiModels(
        apiKey: String,
        isOAuth: Boolean = false,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext LLMModel.allGemini
        val cacheKey = (if (isOAuth) "oauth|" else "key|") + apiKey
        if (context != null && !forceRefresh) {
            geminiCache.load(context, cacheKey)?.let { return@withContext it }
        }
        val headers = if (isOAuth) {
            // P3.1d 净眼建议 ①：OAuth 路径补品牌 UA（被删上游对两种形态统一盖 UA）。
            mapOf("Authorization" to "Bearer $apiKey", "User-Agent" to MinisUserAgent.DEFAULT)
        } else {
            // x-goog-api-key 头等价 ?key=（密钥不进 URL）。
            mapOf("x-goog-api-key" to apiKey, "User-Agent" to MinisUserAgent.DEFAULT)
        }
        val parsed = when (val outcome = ModelsCatalog.get(URI(ModelsCatalog.GEMINI_MODELS_URL), headers, TIMEOUT_MILLIS)) {
            is ModelsCatalog.FetchResult.HttpError -> {
                // OAuth 403 ≈ 令牌缺 generative-language scope（Cloud Code Assist 常见）：
                // 回落内置列表，保持用户可用。
                if (isOAuth && outcome.status == 403) return@withContext LLMModel.allGemini
                if (context != null && (outcome.status == 401 || outcome.status == 403)) {
                    geminiCache.invalidate(context, cacheKey)
                }
                null
            }
            is ModelsCatalog.FetchResult.NetworkFailure ->
                throw java.io.IOException("gemini models fetch failed (network)")
            is ModelsCatalog.FetchResult.Success ->
                ModelsCatalog.parseGemini(outcome.body)?.filter { it.chatCapable }?.map { LLMModel(it.id, it.displayName, "Google") }
        }
        if (parsed == null || parsed.isEmpty()) return@withContext LLMModel.allGemini
        val models = ModelsDevApi.enrichModels(parsed)
        if (context != null) geminiCache.save(context, cacheKey, models)
        models
    }

    // -----------------------------------------------------------------
    // OpenAI 兼容方言
    // -----------------------------------------------------------------

    /**
     * OpenAI 兼容模型目录。官方端点按聊天系前缀过滤并盖思考章；自定义端点
     * （vLLM/Ollama）全收并解析 architecture 模态。失败回落：官方→内置列表、
     * 自定义→空表（调用方保留既有列表）。
     */
    suspend fun fetchOpenAiModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] 模型目录 UA 覆盖。
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI
        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            openAiCache.load(context, cacheKey)?.let { return@withContext it }
        }
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "User-Agent" to (customUserAgent?.trim()?.takeIf { it.isNotEmpty() } ?: MinisUserAgent.DEFAULT),
        )
        val parsed = when (val outcome = ModelsCatalog.get(URI(ModelsCatalog.openAiModelsUrl(baseURL)), headers, TIMEOUT_MILLIS)) {
            is ModelsCatalog.FetchResult.HttpError -> {
                if (context != null && (outcome.status == 401 || outcome.status == 403)) {
                    openAiCache.invalidate(context, cacheKey)
                }
                null
            }
            is ModelsCatalog.FetchResult.NetworkFailure -> null
            is ModelsCatalog.FetchResult.Success ->
                ModelsCatalog.parseOpenAi(outcome.body, officialEndpoint = !isCustomBase)?.map { entry -> entry.toLLMModel(isCustomBase) }
        }
        if (parsed == null || parsed.isEmpty()) return@withContext fallback
        // 直报的 context window 优先于 models.dev 增补（端点比目录更了解自己）。
        val directLimits = parsed.associate { it.id to it.contextWindow }
        val models = ModelsDevApi.enrichModels(parsed).map { enriched ->
            val reported = directLimits[enriched.id]?.let { enriched.copy(contextWindow = it) } ?: enriched
            NovexDeepSeekModelMetadata.official(reported, baseURL)
        }
        if (context != null) openAiCache.save(context, cacheKey, models)
        models
    }

    /**
     * Codex OAuth 静态模型列表（令牌不能调 /v1/models）。顺序保持——模型选择器
     * 的默认排序不变；gpt-image-2 追加在 enrichModels 之后，保住其声明的模态。
     */
    fun fetchOpenAiModelsOAuth(): List<LLMModel> = listOf(
        // [T-android-thinking-level-arch] GPT-5.6 family — Codex OAuth only。
        LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4", "GPT-5.4", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] gpt-5.4-mini 之外的旧 id 已实测被 Codex 后端
        // 400（"not supported when using Codex with a ChatGPT account"），空转成
        // 「工具坏了」的假象。恢复任何 id 前必须真机重探，勿按文档添加。
        LLMModel("gpt-5.4-mini", "GPT-5.4 Mini", "OpenAI", supportsReasoning = true),
    ).let { list ->
        AppLogger.info(TAG, "Codex OAuth model list (${list.size} models): ${list.joinToString { m -> m.id }}")
        ModelsDevApi.enrichModels(list)
    } + listOf(
        // [T-codex-gpt-image2-oauth-android] gpt-image-2 追加在 enrichModels 之后
        //（models.dev 不认识它，增补会抹掉声明的模态）。
        LLMModel(
            id = "gpt-image-2",
            displayName = "GPT Image 2",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
    )

    /** openai 条目 → LLMModel：模态取 architecture（vLLM/OpenRouter 兼容代理），思考章按已知家族。 */
    private fun ModelsCatalog.CatalogModel.toLLMModel(isCustomBase: Boolean): LLMModel {
        val raw = raw
        val inputModalities = raw?.optJSONObject("architecture")
            ?.takeIf { !it.isNull("input_modalities") }?.optJSONArray("input_modalities")
            ?.toStringList()?.normalizeModalities()
        val outputModalities = raw?.optJSONObject("architecture")
            ?.takeIf { !it.isNull("output_modalities") }?.optJSONArray("output_modalities")
            ?.toStringList()?.normalizeModalities()
        val idLower = id.lowercase()
        // T119：已知思考家族（GPT-5.x / o 系 / codex）预置 supportsReasoning——
        // models.dev 未收录新 id 时思考档位也能亮起。
        val knownReasoning = idLower.startsWith("gpt-5") || idLower.startsWith("o1") ||
            idLower.startsWith("o3") || idLower.startsWith("o4") || idLower.contains("codex")
        return LLMModel(
            id = id,
            displayName = displayName,
            contextWindow = raw?.let { ReportedContextWindow.read(it) },
            provider = if (isCustomBase) "Custom" else "OpenAI",
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            supportsReasoning = if (knownReasoning) true else null,
        )
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = if (isNull(index)) "" else optString(index, "")
            if (value.isNotEmpty()) out += value
        }
        return out
    }

    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }
}
