package com.openminis.app.provider.opencode

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** The public, anonymous OpenCode Zen catalog exposed to Novex users. */
object OpenCodeFreeModelsApi {
    const val BASE_URL = "https://opencode.ai/zen/v1"
    const val PUBLIC_KEY = "public"
    const val CHAT_INSTANCE_ID = "builtin-opencode-free-chat"
    const val RESPONSES_INSTANCE_ID = "builtin-opencode-free-responses"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class FreeModel(
        val model: LLMModel,
        val usesResponses: Boolean,
    )

    data class FetchResult(
        val models: List<FreeModel>,
        val catalogWasRefreshed: Boolean,
        val usedBundledFallback: Boolean,
    )

    suspend fun fetch(forceCatalogRefresh: Boolean = false): FetchResult = withContext(Dispatchers.IO) {
        val refreshed = if (forceCatalogRefresh) ModelsDevApi.refreshNow() else false
        val catalog = ModelsDevApi.freeActiveModels("opencode")
            .map { entry ->
                FreeModel(
                    model = entry.model.copy(provider = "OpenCode Zen"),
                    usesResponses = entry.providerPackage == "@ai-sdk/openai",
                )
            }
            .ifEmpty { bundledFallback }
        val liveIds = fetchLiveModelIds()
        val available = filterAvailable(catalog, liveIds)
        FetchResult(
            models = available,
            catalogWasRefreshed = refreshed,
            usedBundledFallback = catalog === bundledFallback,
        )
    }

    internal fun filterAvailable(catalog: List<FreeModel>, liveIds: Set<String>): List<FreeModel> {
        if (liveIds.isEmpty()) return catalog
        return catalog.filter { it.model.id in liveIds }
    }

    private fun fetchLiveModelIds(): Set<String> {
        val request = Request.Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $PUBLIC_KEY")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("OpenCode 模型列表请求失败：HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val data = JSONObject(body).optJSONArray("data")
                ?: error("OpenCode 模型列表缺少 data")
            return buildSet {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
    }

    private val bundledFallback = listOf(
        chat("big-pickle", "Big Pickle"),
        chat(
            "mimo-v2.5-free",
            "MiMo V2.5 Free",
            inputs = listOf("text", "image", "audio", "video"),
        ),
        chat("ling-3.0-flash-fin-free", "Ling 3.0 Flash Fin Free"),
        chat("nemotron-3-ultra-free", "Nemotron 3 Ultra Free"),
        chat("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning Free"),
        responses(
            "muse-spark-1.2-contributor-free",
            "Muse Spark 1.2 Free",
            inputs = listOf("text", "image", "video", "pdf", "audio"),
        ),
    )

    private fun chat(id: String, name: String, inputs: List<String> = listOf("text")) =
        FreeModel(
            model = LLMModel(
                id = id,
                displayName = name,
                provider = "OpenCode Zen",
                inputModalities = inputs,
                outputModalities = listOf("text"),
                supportsTools = true,
            ),
            usesResponses = false,
        )

    private fun responses(id: String, name: String, inputs: List<String>) =
        FreeModel(
            model = LLMModel(
                id = id,
                displayName = name,
                provider = "OpenCode Zen",
                inputModalities = inputs,
                outputModalities = listOf("text"),
                supportsTools = true,
            ),
            usesResponses = true,
        )
}
