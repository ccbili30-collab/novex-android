package com.openminis.app.provider.openrouter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.data.model.LLMModel
import novex.android.data.model.ReportedContextWindow
import novex.android.data.model.normalizeModalities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride

/**
 * OpenRouter 模型目录拉取（血统清剿 P3.7 就地真重写；端点 URL、两枚身份
 * 头、JSON 字段读取面与缓存策略为契约冻结面）。
 *
 * OpenRouter 每个模型都带富元数据（上下文窗口、最大输出、推理支持、
 * 输入/输出模态）——在这里就地解析，不等 `ModelsDevApi.enrichModels` 补
 * 齐；对齐 iOS `OpenRouterModelsAPI`。
 */
object OpenRouterModelsApi {
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openrouter")

    /**
     * 拉目录。
     *
     * @param context 给了就把结果缓存 7 天在
     *   `cacheDir/models-cache/openrouter/<sha256(apiKey)>.json`；null 完全
     *   绕过缓存（既有调用方语义）。
     * @param forceRefresh 跳过缓存读，成功后照常写穿。
     */
    suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (context != null && !forceRefresh) {
            cache.load(context, apiKey)?.let { return@withContext it }
        }

        val response = client.newCall(buildCatalogRequest(apiKey)).execute()
        if (!response.isSuccessful) {
            // 鉴权类失败顺手作废缓存——凭据轮换后旧目录不可信。
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, apiKey)
            }
            return@withContext emptyList()
        }
        val body = response.body?.string() ?: return@withContext emptyList()

        val models = runCatching { parseCatalog(JSONObject(body)) }
            .getOrDefault(emptyList())
        val enriched = ModelsDevApi.enrichModels(models)
        if (context != null) cache.save(context, apiKey, enriched)
        enriched
    }

    private fun buildCatalogRequest(apiKey: String): Request =
        Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/ccbili30-collab/novex-android")
            .header("X-Title", "Minis App")
            // [T-android-default-ua] 出站 /api/v1/models 请求带品牌 UA。
            .applyUserAgentOverride(null)
            .build()

    /** 解析 `data` 数组为模型列表；结构对不上返回空表。 */
    private fun parseCatalog(root: JSONObject): List<LLMModel> {
        val rows = root.optJSONArray("data") ?: return emptyList()
        val models = mutableListOf<LLMModel>()
        for (i in 0 until rows.length()) {
            parseOne(rows.getJSONObject(i))?.let(models::add)
        }
        return models
    }

    private fun parseOne(obj: JSONObject): LLMModel? {
        val id = obj.optString("id")
        if (id.isEmpty()) return null

        // OpenRouter 把 `architecture.input_modalities` / `output_modalities`
        // 给成短串数组（"text"/"image"/"audio"）；缺省回落 `[text, text]`——
        // 多数纯文本模型不带这两项。其模态串带 `image_input`/`text_output`
        // 之类的后缀形态，而仓内其余各处（models.dev、能力片段、
        // ModelEntryDetailScreen 开关）用裸形态——在解析边界归一，持久化的
        // 覆盖值才能经开关正确往返。
        val arch = obj.optJSONObject("architecture")
        val inputModalities = arch?.optJSONArray("input_modalities")?.asStringList().normalizeModalities()
        val outputModalities = arch?.optJSONArray("output_modalities")?.asStringList().normalizeModalities()

        val contextWindow = ReportedContextWindow.read(obj)
        val maxOutputTokens = obj.optJSONObject("top_provider")
            ?.optInt("max_completion_tokens")?.takeIf { it > 0 }
        val supportsReasoning = "reasoning" in (obj.optJSONArray("supported_parameters")?.asStringList() ?: emptyList())

        return LLMModel(
            id = id,
            displayName = obj.optString("name", id),
            provider = "OpenRouter",
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            supportsReasoning = if (supportsReasoning) true else null,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
        )
    }

    private fun JSONArray.asStringList(): List<String> =
        (0 until length()).mapNotNull { i ->
            optString(i, "").takeIf { it.isNotEmpty() }
        }
}
