package com.openminis.app.data.model

import java.net.URI

/** Official V4 context specification checked 2026-09-09. Never changes user overrides. */
object NovexDeepSeekModelMetadata {
    const val CONTEXT_TOKENS = 1_000_000
    private val v4 = setOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp")
    fun isKnownV4(id: String): Boolean = id.substringAfterLast('/').lowercase() in v4
    fun official(model: LLMModel, baseUrl: String?): LLMModel {
        val direct = runCatching { URI(baseUrl.orEmpty()).host.equals("api.deepseek.com", ignoreCase = true) }.getOrDefault(false)
        return if (direct && isKnownV4(model.id)) model.copy(contextWindow = CONTEXT_TOKENS) else model
    }
    fun repairCatalog(config: ProviderConfig): ProviderConfig {
        val instances = config.instances.associateBy { it.id }
        val entries = config.modelEntries.map { entry ->
            if (entry.isCustom) entry else {
                val updated = official(entry.baseModel, instances[entry.providerInstanceId]?.effectiveBaseURL)
                if (updated == entry.baseModel) entry else entry.copy(baseModel = updated)
            }
        }
        return if (entries == config.modelEntries) config else config.copy(modelEntries = entries.toMutableList())
    }
}
