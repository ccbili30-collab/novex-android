package com.openminis.app.provider.image

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Strict model discovery for an image-generation source. */
object ImageModelCatalog {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(
        providerType: ProviderType,
        baseURL: String,
        apiKey: String,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        when (providerType) {
            ProviderType.gemini -> fetchGemini(baseURL, apiKey)
            else -> fetchOpenAICompatible(baseURL, apiKey)
        }
    }

    private fun fetchOpenAICompatible(baseURL: String, apiKey: String): List<LLMModel> {
        val official = baseURL.contains("api.openai.com", ignoreCase = true)
        val request = Request.Builder()
            .url(baseURL.trimEnd('/') + "/models")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .build()
        return execute(request) { body ->
            val data = JSONObject(body).optJSONArray("data")
                ?: error("模型列表响应缺少 data")
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isEmpty()) continue
                    val architecture = item.optJSONObject("architecture")
                    val input = architecture?.optJSONArray("input_modalities").toStrings()
                    val output = architecture?.optJSONArray("output_modalities").toStrings()
                    // An explicit text/audio-only declaration is authoritative.
                    // Missing modality metadata remains selectable because many
                    // compatible gateways omit it even for image models.
                    if (output.isNotEmpty() && output.none { it.normalizedModality() == "image" }) continue
                    if (official && output.isEmpty() && !id.looksLikeImageModel()) continue
                    add(
                        LLMModel(
                            id = id,
                            displayName = item.optString("name", id),
                            provider = "Custom",
                            inputModalities = input.map { it.normalizedModality() }.distinct(),
                            outputModalities = output.map { it.normalizedModality() }.distinct(),
                        ),
                    )
                }
            }
        }
    }

    private fun fetchGemini(baseURL: String, apiKey: String): List<LLMModel> {
        val official = baseURL.contains("generativelanguage.googleapis.com", ignoreCase = true)
        val url = (baseURL.trimEnd('/') + "/models").toHttpUrl().newBuilder()
            .apply { if (apiKey.isNotBlank()) addQueryParameter("key", apiKey) }
            .build()
        val request = Request.Builder().url(url).build()
        return execute(request) { body ->
            val data = JSONObject(body).optJSONArray("models")
                ?: error("模型列表响应缺少 models")
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("name").removePrefix("models/").trim()
                    if (id.isEmpty()) continue
                    val methods = item.optJSONArray("supportedGenerationMethods").toStrings()
                    val mayGenerate = methods.isEmpty() || methods.any {
                        it.contains("predict", ignoreCase = true) ||
                            it.contains("generateContent", ignoreCase = true)
                    }
                    if (!mayGenerate) continue
                    if (official && !id.looksLikeImageModel()) continue
                    add(
                        LLMModel(
                            id = id,
                            displayName = item.optString("displayName", id),
                            provider = "Google",
                            inputModalities = listOf("text", "image"),
                            // Gemini's list endpoint does not reliably declare
                            // image output. The user opts in explicitly below.
                            outputModalities = emptyList(),
                        ),
                    )
                }
            }
        }
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = body.replace(Regex("\\s+"), " ").take(500)
                error("HTTP ${response.code}${detail.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}")
            }
            return parse(body)
        }
    }

    private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun String.normalizedModality(): String = lowercase()
        .removeSuffix("_input")
        .removeSuffix("_output")

    private fun String.looksLikeImageModel(): Boolean {
        val id = lowercase()
        return listOf("image", "imagen", "dall-e", "flux", "ideogram", "recraft", "seedream", "nano-banana")
            .any(id::contains)
    }
}
