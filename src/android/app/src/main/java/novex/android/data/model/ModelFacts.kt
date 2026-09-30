package novex.android.data.model

import org.json.JSONObject
import java.net.URI

/*
 * Vendor-documented facts that the generic catalog cannot express:
 * DeepSeek family context sizes, the context-capacity keys various
 * providers put in their model JSON, and the id-shape fallback for context
 * windows when neither the catalog nor the API said anything.
 */

/** DeepSeek-line facts; never overrides a value the user set explicitly. */
object NovexDeepSeekModelMetadata {
    const val FLASH_CONTEXT_TOKENS = 128_000
    const val PRO_CONTEXT_TOKENS = 1_000_000

    private val v4Family = setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp")
    private val v4DatedShape = Regex("deepseek-v4-(flash|pro)-[0-9]{4}")
    private val mimoFamily = setOf("mimo-v2.5", "mimo-v2.5-pro")

    fun isKnownV4(id: String): Boolean {
        val short = id.substringAfterLast('/').lowercase()
        return short in v4Family || v4DatedShape.matches(short)
    }

    fun contextTokens(id: String): Int {
        val pro = id.substringAfterLast('/').lowercase().contains("pro")
        return if (pro) PRO_CONTEXT_TOKENS else FLASH_CONTEXT_TOKENS
    }

    /** Stamps documented context sizes onto catalog models from official hosts. */
    fun official(model: LLMModel, baseUrl: String?): LLMModel {
        val host = runCatching { URI(baseUrl.orEmpty()).host?.lowercase() }.getOrNull()
        val documentedDeepSeek = host == "api.deepseek.com" && isKnownV4(model.id)
        val documentedMimo = host == "api.xiaomimimo.com" && model.id.lowercase() in mimoFamily
        if (!documentedDeepSeek && !documentedMimo) return model

        val stamped = when {
            model.id.lowercase().contains("deepseek") -> contextTokens(model.id)
            else -> PRO_CONTEXT_TOKENS
        }
        return model.copy(contextWindow = stamped)
    }

    /** Re-runs [official] over every non-custom entry and returns the repaired config. */
    fun repairCatalog(config: ProviderConfig): ProviderConfig {
        val hostById = config.instances.associate { it.id to it.effectiveBaseURL }
        val repaired = config.modelEntries.map { entry ->
            if (entry.isCustom) {
                entry
            } else {
                val stamped = official(entry.baseModel, hostById[entry.providerInstanceId])
                if (stamped === entry.baseModel || stamped == entry.baseModel) entry else entry.copy(baseModel = stamped)
            }
        }
        return if (repaired == config.modelEntries) config else config.copy(modelEntries = repaired.toMutableList())
    }
}

/**
 * Reads only the context capacity a provider explicitly declares — never
 * the max-output length, which is a different quantity entirely.
 */
object ReportedContextWindow {
    private val topLevelKeys = listOf(
        "context_length", "context_window", "contextWindow", "max_model_len",
        "max_context_length", "max_position_embeddings", "inputTokenLimit",
        "input_token_limit", "max_input_tokens",
    )
    private val nestedContainers = listOf("limit", "limits", "architecture", "capabilities")

    fun read(model: JSONObject): Int? {
        topLevelKeys.forEach { key -> parseCount(model.opt(key))?.let { return it } }
        nestedContainers.forEach { container ->
            val nested = model.optJSONObject(container) ?: return@forEach
            (topLevelKeys + "context").forEach { key -> parseCount(nested.opt(key))?.let { return it } }
        }
        return null
    }

    /** Accepts "128k"/"1m"/"1.5m"/"200000"/"200,000"-style declarations. */
    private fun parseCount(value: Any?): Int? {
        val raw = value?.toString()?.trim()?.replace(",", "") ?: return null
        val match = Regex("(?i)^([0-9]+(?:\\.[0-9]+)?)\\s*([km]?)$").matchEntire(raw) ?: return null
        val scale = when (match.groupValues[2].lowercase()) {
            "m" -> 1_000_000
            "k" -> 1_000
            else -> 1
        }
        val parsed = match.groupValues[1].toDouble() * scale
        return parsed.takeIf { it >= 1 && it <= Int.MAX_VALUE }?.toInt()
    }
}

/**
 * The id-shape fallback behind [LLMModel.contextWindowTokens]. Checked in
 * family order; within the OpenAI family the order matters (gpt-4o before
 * plain gpt-4), and Grok defaults high because an underestimated window
 * triggers runaway compaction.
 */
internal fun inferContextWindowTokens(model: LLMModel): Int {
    val id = model.id.lowercase()
    val short = id.substringAfterLast('/')

    if (id.contains("claude")) {
        return when {
            id.contains("haiku") || id.contains("claude-2") || id.contains("claude-3") -> 200_000
            else -> 1_000_000
        }
    }
    if (id.contains("gemini")) {
        return if (id.contains("1.0")) 32_000 else 1_000_000
    }
    if (id.contains("gpt-3.5")) return 16_000
    if (id.contains("gpt-4o") || id.contains("gpt-4-turbo")) return 128_000
    if (id.contains("gpt-5")) return 400_000
    if (id.contains("gpt-4")) return 8_000
    if (id.contains("o3") || id.contains("o4")) return 200_000
    if (id.contains("codex")) return 200_000
    if (NovexDeepSeekModelMetadata.isKnownV4(model.id)) return NovexDeepSeekModelMetadata.contextTokens(model.id)
    if (id.contains("deepseek")) return 128_000
    if (short in setOf("mimo-v2.5", "mimo-v2.5-pro", "glm-5.2", "glm-5.2-cheap")) return 1_000_000
    if (short in setOf("glm-5", "glm-5.1", "glm-5.1-highspeed")) return 200_000
    if (id.contains("grok")) {
        return if (id.contains("grok-2") || id.contains("grok-3")) 131_072 else 256_000
    }
    // Assume a modern long-context model; a low default collapses the
    // context-limit slider to one stop.
    return 128_000
}
