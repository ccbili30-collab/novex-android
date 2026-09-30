package novex.android.models

import android.content.Context
import android.util.Log
import novex.android.data.model.LLMModel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * models.dev 在线模型目录（P3.5c 自 provider/ModelsDevApi 真重写）。
 *
 * 用途：供应端 /v1/models 拉不到时的回退清单，以及模型能力（上下文窗、
 * 输出上限、推理、模态）的权威数据源。
 *
 * 三级缓存（冻结面）：内存 → 磁盘 `cacheDir/models-dev-cache/api.json`
 * （mtime 为缓存时刻）→ 打包资产 `models-dev-api.json` 兜底。内存缓存
 * 新鲜（TTL 48h）直接用；过期则先用旧值、后台线程静默刷新（单飞）。
 *
 * 源 URL `https://models.dev/api.json`；字段映射见 [parseModelEntry]。
 */
object ModelsDevCatalog {

    private const val TAG = "ModelsDevCatalog"
    private const val SOURCE_URL = "https://models.dev/api.json"
    private const val CACHE_TTL_MS = 48 * 3600 * 1000L // 48 小时

    /** 富化查表时的「供应方显示名 → 目录键」映射（与 iOS 对齐）。 */
    private val providerKeyMap = mapOf(
        "Anthropic" to listOf("anthropic"),
        "Google" to listOf("google", "google-vertex"),
        "OpenAI" to listOf("openai"),
        "OpenRouter" to listOf("openrouter"),
        "Antigravity" to emptyList(), // 自建代理，models.dev 无公开条目
    )

    private var registry: Map<String, ProviderEntry>? = null
    private var registryStamp: Long = 0L
    private val refreshing = AtomicBoolean(false)
    private var appContext: Context? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 启动时以 application context 调一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── 回退清单：按 base URL 找供应方的模型表 ────────────────────────

    fun fetchModels(forBaseURL: String): List<LLMModel> {
        val loaded = currentRegistry() ?: return emptyList()
        // 第一阶段：API base 精确匹配（带 / 不带 /v1 两种候选）；第二阶段：主机名匹配。
        val wanted = baseCandidates(forBaseURL)
        val wantedHost = hostOf(forBaseURL)
        val hit = loaded.values.asSequence()
            .mapNotNull { entry -> entry.api?.takeIf { it.isNotEmpty() }?.let { entry to it } }
            .firstOrNull { (_, api) -> api.trimEnd('/') in wanted || (wantedHost != null && hostOf(api) == wantedHost) }
        if (hit == null) { Log.d(TAG, "no models.dev match for base URL: $forBaseURL"); return emptyList() }
        Log.d(TAG, "matched ${hit.first.id} (api=${hit.second}) — registry fallback")
        return hit.first.asLLMModels()
    }

    // ── 富化：把目录能力数据并进模型条目 ──────────────────────────────

    /** 命中查找：先按显示名映射的键，兜底全目录扫模型 id。 */
    private fun findDevEntry(loaded: Map<String, ProviderEntry>, model: LLMModel): ModelDevEntry? {
        (providerKeyMap[model.provider] ?: emptyList()).forEach { key ->
            loaded[key]?.models?.get(model.id)?.let { return it }
        }
        return loaded.values.asSequence().mapNotNull { it.models[model.id] }.firstOrNull()
    }

    fun enrichModel(model: LLMModel): LLMModel {
        val loaded = currentRegistry() ?: return model
        return findDevEntry(loaded, model)?.let { mergeDevData(model, it) } ?: model
    }

    fun enrichModels(models: List<LLMModel>): List<LLMModel> {
        val loaded = currentRegistry() ?: return models
        return models.map { model ->
            // 兜底扫描：同一模型 id 被多家转发（glm-5.2 挂在 19 家名下），
            // 自建中转的供应方名一个都对不上，第三方网关实际全走这条。
            // Map 迭代序不是稳定契约且各条目能力声明有分歧：按键排序取
            // 稳定结果，优先带推理元数据的条目——信息多的声明赢过稀疏
            // 的重复项。与 iOS ModelsDevAPI.enrichModels 对齐。
            val candidates = loaded.keys.sorted().mapNotNull { loaded[it]?.models?.get(model.id) }
            bestOf(candidates)?.let { mergeDevData(model, it) } ?: model
        }
    }

    /** 稳定择优：先认带 effort 档声明的条目，其次按排序顺位。 */
    private fun bestOf(candidates: List<ModelDevEntry>): ModelDevEntry? =
        candidates.firstOrNull { !it.reasoningEffortValues.isNullOrEmpty() } ?: candidates.firstOrNull()

    /**
     * 目录里明确标记 active 且输入输出双免费的模型。刻意数据驱动：
     * 有些免费模型（如 OpenCode 的 big-pickle）不带 -free 后缀，而旧
     * -free 模型被目录标废弃后仍留在网关清单里。
     */
    fun freeActiveModels(providerId: String): List<CatalogModel> {
        val entry = currentRegistry()?.get(providerId) ?: return emptyList()
        return entry.models.values.mapNotNull { devModel ->
            if (devModel.status.equals("deprecated", ignoreCase = true)) return@mapNotNull null
            if (devModel.inputCost != 0.0 || devModel.outputCost != 0.0) return@mapNotNull null
            CatalogModel(
                model = LLMModel(
                    id = devModel.id,
                    displayName = devModel.name ?: devModel.id,
                    provider = entry.name ?: entry.id,
                    contextWindow = devModel.contextWindow,
                    maxOutputTokens = devModel.maxOutputTokens,
                    supportsReasoning = devModel.reasoning,
                    interleavedReasoningField = devModel.interleavedField,
                    inputModalities = devModel.inputModalities,
                    outputModalities = devModel.outputModalities,
                    reasoningEffortValues = devModel.reasoningEffortValues,
                    declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else null,
                    supportsTools = devModel.supportsTools,
                ),
                providerPackage = devModel.providerPackage ?: entry.npm,
            )
        }
    }

    /** 只读目录快照（[ModelReleaseIndex] 建排表用；目录缺席给空表）。 */
    fun registrySnapshot(): Map<String, ProviderEntry> = currentRegistry() ?: emptyMap()

    /** 用户显式动作触发的同步刷新。 */
    suspend fun refreshNow(): Boolean = withContext(Dispatchers.IO) {
        pullFromNetwork()
    }

    // ── 数据并入规则 ───────────────────────────────────────────────────

    private fun mergeDevData(model: LLMModel, devModel: ModelDevEntry): LLMModel = model.copy(
        contextWindow = model.contextWindow ?: devModel.contextWindow,
        maxOutputTokens = devModel.maxOutputTokens ?: model.maxOutputTokens,
        supportsReasoning = devModel.reasoning ?: model.supportsReasoning,
        interleavedReasoningField = devModel.interleavedField ?: model.interleavedReasoningField,
        inputModalities = devModel.inputModalities ?: model.inputModalities,
        outputModalities = devModel.outputModalities ?: model.outputModalities,
        reasoningEffortValues = devModel.reasoningEffortValues ?: model.reasoningEffortValues,
        // 只携带“肯定没有”的答案：对目录沉默的条目富化，不能用一个无
        // 意义的 false 覆盖先前拿到过的真实答案。
        declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else model.declaresNoEffortTiers,
    )

    // ── URL 归一 ───────────────────────────────────────────────────────

    private fun baseCandidates(url: String): List<String> {
        val stripped = url.trimEnd('/')
        return if (stripped.endsWith("/v1")) {
            listOf(stripped, stripped.removeSuffix("/v1"))
        } else {
            listOf(stripped, "$stripped/v1")
        }
    }

    private fun hostOf(urlString: String): String? = try {
        URL(urlString.trimEnd('/')).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    // ── 三级缓存调度 ───────────────────────────────────────────────────

    @Synchronized
    private fun currentRegistry(): Map<String, ProviderEntry>? {
        // 1) 内存且新鲜。
        val memo = registry
        if (memo != null && System.currentTimeMillis() - registryStamp < CACHE_TTL_MS) return memo

        // 2) 内存有但陈旧：先用，安排后台刷新。
        if (memo != null) {
            scheduleBackgroundRefresh()
            return memo
        }

        // 3) 磁盘缓存（mtime 即缓存时刻）。
        loadDiskCache()?.let { (parsed, stamp) ->
            registry = parsed
            registryStamp = stamp
            if (System.currentTimeMillis() - stamp >= CACHE_TTL_MS) scheduleBackgroundRefresh()
            return parsed
        }

        // 4) 打包资产兜底。
        loadBundledAsset()?.let { parsed ->
            registry = parsed
            registryStamp = System.currentTimeMillis()
            scheduleBackgroundRefresh()
            return parsed
        }
        return null
    }

    private fun scheduleBackgroundRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        kotlin.concurrent.thread(isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                pullFromNetwork()
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun pullFromNetwork(): Boolean {
        return try {
            val response = http.newCall(Request.Builder().url(SOURCE_URL).build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "models.dev HTTP error: ${response.code}")
                response.close()
                return false
            }
            val body = response.body?.string() ?: return false
            response.close()

            val parsed = parseRegistry(body) ?: return false
            synchronized(this) {
                registry = parsed
                registryStamp = System.currentTimeMillis()
            }
            writeDiskCache(body)
            Log.d(TAG, "background-refreshed models.dev registry: ${parsed.size} providers")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to fetch models.dev: ${e.message}")
            false
        }
    }

    // ── JSON 解析（字段映射冻结面）────────────────────────────────────

    private fun parseRegistry(jsonStr: String): Map<String, ProviderEntry>? = try {
        val root = JSONObject(jsonStr)
        val out = mutableMapOf<String, ProviderEntry>()
        for (key in root.keys()) {
            val obj = root.optJSONObject(key) ?: continue
            parseProviderEntry(key, obj)?.let { out[key] = it }
        }
        out.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.e(TAG, "failed to parse models.dev JSON: ${e.message}")
        null
    }

    private fun parseProviderEntry(id: String, obj: JSONObject): ProviderEntry? {
        val name = obj.optString("name", "").ifEmpty { null }
        val api = obj.optString("api", "").ifEmpty { null }
        val npm = obj.optString("npm", "").ifEmpty { null }
        val modelsObj = obj.optJSONObject("models")
            ?: return ProviderEntry(id, name, api, npm, emptyMap())

        val models = mutableMapOf<String, ModelDevEntry>()
        for (modelKey in modelsObj.keys()) {
            val modelObj = modelsObj.optJSONObject(modelKey) ?: continue
            models[modelKey] = parseModelEntry(modelKey, modelObj)
        }
        return ProviderEntry(id, name, api, npm, models)
    }

    /** JSONObject 取值小家族：空串归 null、非正数归 null、数组去空。 */
    private fun JSONObject.strOf(key: String) = optString(key, "").takeIf { it.isNotEmpty() }

    private fun JSONObject.positiveIntFrom(parent: String, key: String): Int? =
        optJSONObject(parent)?.optInt(key, 0)?.takeIf { it > 0 }

    private fun JSONArray?.stringsOf(): List<String>? {
        val arr = this ?: return null
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotEmpty() } }
            .takeIf { it.isNotEmpty() }
    }

    private fun parseModelEntry(id: String, obj: JSONObject): ModelDevEntry {
        val reasoningOptions = obj.optJSONArray("reasoning_options")

        // interleaved 可为布尔，也可为对象 {"field": "reasoning_content"}。
        val interleavedField = when (obj.opt("interleaved")) {
            is JSONObject -> obj.optJSONObject("interleaved")?.optString("field", "")?.ifEmpty { null }
            true -> "reasoning_content"
            else -> null
        }
        val modalities = obj.optJSONObject("modalities")

        // reasoning_options 是 [{type, values?, min?, max?}]；取 type=="effort"
        // 那条的 values（统一小写）。
        val effortValues = (0 until (reasoningOptions?.length() ?: 0))
            .asSequence().mapNotNull { reasoningOptions!!.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "effort" }?.optJSONArray("values")
            ?.let { values -> (0 until values.length()).mapNotNull { values.optString(it, "").takeIf(String::isNotEmpty)?.lowercase() } }
            ?.takeIf { it.isNotEmpty() }
        // 「肯定没有 effort 档」≠「目录没听说过」：前者是 reasoning_options
        // 在场但拿不出可用的 effort 条目。这个区别在线上是硬性的——xAI
        // grok-build-0.1 与 grok-4.20-0309-reasoning 都带着 "reasoning":
        // true + "reasoning_options": []，对它们发 reasoning_effort 是硬
        // 400。刻意按“没有可用 effort 条目”判——只声明 toggle /
        // budget_tokens 的模型同样肯定不吃 effort 参数。
        val affirmsNoEffortTiers = reasoningOptions != null && effortValues == null

        return ModelDevEntry(
            id = id, name = obj.strOf("name"), family = obj.strOf("family"),
            contextWindow = obj.positiveIntFrom("limit", "context"),
            maxOutputTokens = obj.positiveIntFrom("limit", "output"),
            reasoning = if (obj.has("reasoning")) obj.optBoolean("reasoning") else null,
            interleavedField = interleavedField,
            inputModalities = modalities?.optJSONArray("input").stringsOf(),
            outputModalities = modalities?.optJSONArray("output").stringsOf(),
            reasoningEffortValues = effortValues, declaresNoEffortTiers = affirmsNoEffortTiers,
            releaseDate = obj.strOf("release_date"), status = obj.strOf("status"),
            inputCost = obj.optJSONObject("cost")?.optDouble("input", Double.NaN)?.takeIf { !it.isNaN() },
            outputCost = obj.optJSONObject("cost")?.optDouble("output", Double.NaN)?.takeIf { !it.isNaN() },
            supportsTools = if (obj.has("tool_call")) obj.optBoolean("tool_call") else null,
            providerPackage = obj.optJSONObject("provider")?.optString("npm", "")?.ifEmpty { null },
        )
    }

    // ── 兜底与磁盘 ─────────────────────────────────────────────────────

    private fun loadBundledAsset(): Map<String, ProviderEntry>? {
        val ctx = appContext ?: return null
        return try {
            val text = ctx.assets.open("models-dev-api.json").bufferedReader().readText()
            val parsed = parseRegistry(text)
            Log.d(TAG, "loaded bundled models.dev registry: ${parsed?.size ?: 0} providers")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "failed to load bundled models-dev-api.json: ${e.message}")
            null
        }
    }

    private fun cacheFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "models-dev-cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "api.json")
    }

    private fun loadDiskCache(): Pair<Map<String, ProviderEntry>, Long>? {
        val file = cacheFile() ?: return null
        if (!file.exists()) return null
        return try {
            parseRegistry(file.readText())?.let { it to file.lastModified() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskCache(jsonStr: String) {
        val file = cacheFile() ?: return
        try {
            file.writeText(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "failed to save disk cache: ${e.message}")
        }
    }

    // ── 数据模型 ───────────────────────────────────────────────────────

    data class ProviderEntry(
        val id: String, val name: String?, val api: String?,
        val npm: String?, val models: Map<String, ModelDevEntry>,
    ) {
        /** 转成供应方模型清单：剔掉 embedding / moderation 家族。 */
        fun asLLMModels(): List<LLMModel> = models.values.mapNotNull { devModel ->
            val family = devModel.family?.lowercase() ?: ""
            if (family.contains("embedding") || family.contains("moderation")) return@mapNotNull null
            LLMModel(
                id = devModel.id,
                displayName = devModel.name ?: devModel.id,
                provider = name ?: id,
                contextWindow = devModel.contextWindow,
                maxOutputTokens = devModel.maxOutputTokens,
                supportsReasoning = devModel.reasoning,
                interleavedReasoningField = devModel.interleavedField,
                inputModalities = devModel.inputModalities,
                outputModalities = devModel.outputModalities,
                reasoningEffortValues = devModel.reasoningEffortValues,
                // 目录沉默时给 null（不是 false）——“未知”与“声明没有”
                // 必须可区分。
                declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else null,
            )
        }
    }

    data class CatalogModel(
        val model: LLMModel,
        val providerPackage: String?,
    )

    data class ModelDevEntry(
        val id: String, val name: String?, val family: String?,
        val contextWindow: Int?, val maxOutputTokens: Int?, val reasoning: Boolean?,
        val interleavedField: String?,                     // interleaved 为 true 或 {"field": …} 时的承载字段
        val inputModalities: List<String>?,                // modalities.input（如 ["text","image"]）
        val outputModalities: List<String>?,               // modalities.output
        val reasoningEffortValues: List<String>?,          // reasoning_options 里 effort 条目的 values；只声明 toggle/budget_tokens 时为 null
        val declaresNoEffortTiers: Boolean = false,        // 在场但无可用 effort 档：“会推理、但不吃 reasoning_effort”
        val releaseDate: String?,                           // 原始 release_date；181 条只有 YYYY-MM，必须容下
        val inputCost: Double?,                             // USD / 百万输入 token
        val outputCost: Double?,                            // USD / 百万输出 token：同日发布的名次裁决
        val status: String?, val supportsTools: Boolean?, val providerPackage: String?,
    )
}
