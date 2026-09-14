package com.openminis.app.data.model

import java.net.URI

/** Provider-specific context defaults; never changes an explicit user override. */
object NovexDeepSeekModelMetadata {
    const val FLASH_CONTEXT_TOKENS = 128_000
    const val PRO_CONTEXT_TOKENS = 1_000_000
    private val v4 = setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp")
    fun isKnownV4(id: String): Boolean = id.substringAfterLast('/').lowercase().let { value -> value in v4 || Regex("deepseek-v4-(flash|pro)-[0-9]{4}").matches(value) }
    fun contextTokens(id: String): Int = if (id.substringAfterLast('/').lowercase().contains("pro")) PRO_CONTEXT_TOKENS else FLASH_CONTEXT_TOKENS
    fun official(model: LLMModel, baseUrl: String?): LLMModel {
        val host = runCatching { URI(baseUrl.orEmpty()).host?.lowercase() }.getOrNull()
        val documented = host == "api.deepseek.com" && isKnownV4(model.id) ||
            host == "api.xiaomimimo.com" && model.id.lowercase() in setOf("mimo-v2.5", "mimo-v2.5-pro")
        return if (documented) model.copy(contextWindow = if (model.id.lowercase().contains("deepseek")) contextTokens(model.id) else PRO_CONTEXT_TOKENS) else model
    }
    fun repairCatalog(config: ProviderConfig): ProviderConfig {
        val instances = config.instances.associateBy { it.id }
        val entries = config.modelEntries.map { entry ->
            if (entry.isCustom) entry else run {
                val updated = official(entry.baseModel, instances[entry.providerInstanceId]?.effectiveBaseURL)
                if (updated == entry.baseModel) entry else entry.copy(baseModel = updated)
            }
        }
        return if (entries == config.modelEntries) config else config.copy(modelEntries = entries.toMutableList())
    }
}
