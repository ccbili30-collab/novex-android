package novex.model

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * 模型目录拉取（GET /models 三方言：OpenAI 兼容 / anthropic / gemini）的自有实现。
 * 只负责纯函数面（URL 构造、基址爬升、响应解析）与一次无重试的 GET 执行；
 * 缓存/兜底列表/models.dev 增补/LLMModel 映射归 app 侧包装器（那些是 Android 侧
 * 关注点，本模块保持零上游依赖）。
 *
 * 执行走 [ModelEndpoint] 同一套端点安全契约（https 或本机回环、头整洁、令牌不进
 * URL）；gemini 的 API key 由调用方以 `x-goog-api-key` 头携带（等价 `?key=`）。
 */
object ModelsCatalog {

    /** 中性目录条目：方言共有的 id/显示名 + 各自的盖章位；raw 供 app 侧解析方言扩展位。 */
    data class CatalogModel(
        val id: String,
        val displayName: String,
        /** anthropic：/v1/models 无能力元数据，Claude 思考机型预置思考开关（否则 UI 隐藏）。 */
        val supportsReasoning: Boolean? = null,
        /** gemini：supportedGenerationMethods 是否含 generateContent（对话可用过滤）。 */
        val chatCapable: Boolean = true,
        /** openai 方言的原始条目（architecture modalities / context window 由 app 侧数据模型解析）。 */
        val raw: JSONObject? = null,
    )

    sealed interface FetchResult {
        data class Success(val body: String) : FetchResult
        data class HttpError(val status: Int, val body: String) : FetchResult
        data object NetworkFailure : FetchResult
    }

    /**
     * 一次 GET，无内部重试。同步阻塞，调用方自备 IO 调度。头经 [ModelEndpoint] 校验
     * 后原样设置（Authorization 直接放头表，令牌不进 URL）。
     */
    fun get(url: URI, headers: Map<String, String>, timeoutMillis: Int = 30_000,
            permitQueryParams: Boolean = false): FetchResult {
        val endpoint = ModelEndpoint(url, null, headers, permitQueryParams)
        var connection: HttpURLConnection? = null
        return try {
            connection = url.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            endpoint.authorize(connection)
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status in 200..299) FetchResult.Success(body) else FetchResult.HttpError(status, body)
        } catch (_: IOException) {
            FetchResult.NetworkFailure
        } finally {
            connection?.disconnect()
        }
    }

    // -----------------------------------------------------------------
    // anthropic 方言
    // -----------------------------------------------------------------

    /**
     * anthropic 候选基址：用户给的 base 在前（已过 effectiveBaseURL 规范化，只拼
     * /models 不强注 /v1），其后最多爬三层父路径（厂商子路径如
     * `https://api.deepseek.com/anthropic` 借此发现主机根的 /v1/models）。
     */
    fun anthropicCandidateBases(baseURL: String?): List<String?> {
        val bases = mutableListOf(baseURL)
        if (baseURL != null) {
            var current = baseURL
            var hops = 0
            while (hops < 3) {
                val parent = parentPath(current) ?: break
                if (parent == current) break
                bases += parent
                current = parent
                hops++
            }
        }
        return bases.distinct()
    }

    /**
     * anthropic /models 端点。首层（base 已规范化）只追加 /models——强注 /v1 会越过
     * 用户的 appendV1Suffix 开关；父路径发现层（[forceV1Discovery]）保持 /v1 自动补
     * 齐以维持主机根发现约定。null → 官方端点。
     */
    fun anthropicModelsUrl(baseURL: String?, forceV1Discovery: Boolean = false): String {
        if (baseURL == null) return "$ANTHROPIC_OFFICIAL_BASE/v1/models?limit=512"
        val base = baseURL.trimEnd('/')
        val endpoint = when {
            base.endsWith("/v1") -> "$base/models"
            forceV1Discovery -> "$base/v1/models"
            else -> "$base/models"
        }
        return "$endpoint?limit=512"
    }

    /**
     * anthropic 响应解析（`{data:[{id,display_name}]}`）。思考盖章经 [AnthropicWire.
     * supportsThinking]。解析失败返回 null；data 缺失/空表返回空表（调用方走各自兜底）。
     */
    fun parseAnthropic(body: String): List<CatalogModel>? = try {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.getString("id")
            CatalogModel(
                id = id,
                displayName = if (item.isNull("display_name")) id else item.optString("display_name", id).ifEmpty { id },
                supportsReasoning = if (AnthropicWire.supportsThinking(id)) true else null,
            )
        }
    } catch (_: Exception) { null }

    /** 剥掉 URL 最后一个路径段（查询串/锚点先行剥离）；null 入参、已在主机根或无法解析均返回 null。 */
    fun parentPath(base: String?): String? {
        val withoutQuery = base?.substringBefore('#')?.substringBefore('?') ?: return null
        val segments = withoutQuery.split("://", limit = 2)
        if (segments.size != 2) return null
        val authority = segments[1].substringBefore('/', missingDelimiterValue = "")
        if (authority.isEmpty()) return null
        val path = segments[1].substringAfter('/', missingDelimiterValue = "").trimEnd('/')
        if (path.isEmpty()) return null
        // 路径无 '/'（单段）：父级就是主机根。
        val parent = if ('/' in path) path.substringBeforeLast('/') else ""
        return segments[0] + "://" + authority + if (parent.isEmpty()) "" else "/$parent"
    }

    // -----------------------------------------------------------------
    // gemini 方言
    // -----------------------------------------------------------------

    /** gemini 模型目录端点（官方 v1beta；鉴权走请求头，key 不进 URL）。 */
    const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    /** gemini 响应解析（`{models:[{name,displayName,supportedGenerationMethods}]}`）。 */
    fun parseGemini(body: String): List<CatalogModel>? = try {
        val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
        (0 until models.length()).mapNotNull { index ->
            val item = models.optJSONObject(index) ?: return@mapNotNull null
            val name = item.getString("name").removePrefix("models/")
            val methods = item.optJSONArray("supportedGenerationMethods")
            val chatCapable = methods?.let { list -> (0 until list.length()).any { list.getString(it).contains("generateContent") } } == true
            CatalogModel(
                id = name,
                displayName = if (item.isNull("displayName")) name else item.optString("displayName", name).ifEmpty { name },
                chatCapable = chatCapable,
            )
        }
    } catch (_: Exception) { null }

    // -----------------------------------------------------------------
    // OpenAI 兼容方言
    // -----------------------------------------------------------------

    /** OpenAI 兼容 /models 端点。base 已过 effectiveBaseURL 规范化，只追加 /models。 */
    fun openAiModelsUrl(baseURL: String?): String =
        if (baseURL == null) "$OPENAI_OFFICIAL_BASE/models" else baseURL.trimEnd('/') + "/models"

    /**
     * OpenAI 兼容响应解析（`{data:[{id,name,architecture?}]}`）。官方端点按上游口径
     * 过滤聊天系（gpt-/o1/o3/o4-/codex-/chatgpt- 前缀，排除 instruct/realtime/
     * audio/transcribe/tts/embedding 与 :ft: 微调）；自定端点（vLLM/Ollama）不过滤。
     * 原始条目经 raw 透出，modalities/context window 归 app 侧解析。
     */
    fun parseOpenAi(body: String, officialEndpoint: Boolean): List<CatalogModel>? = try {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.getString("id")
            if (officialEndpoint) {
                if (CHAT_PREFIXES.none(id::startsWith)) return@mapNotNull null
                if (EXCLUDE_SUFFIXES.any(id::contains)) return@mapNotNull null
                if (id.contains(":ft-")) return@mapNotNull null
            }
            CatalogModel(
                id = id,
                displayName = if (item.isNull("name")) id else item.optString("name", id).ifEmpty { id },
                raw = item,
            )
        }
    } catch (_: Exception) { null }

    const val ANTHROPIC_OFFICIAL_BASE = "https://api.anthropic.com"
    const val OPENAI_OFFICIAL_BASE = "https://api.openai.com/v1"

    private val CHAT_PREFIXES = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")
    private val EXCLUDE_SUFFIXES = listOf("-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding")
}
