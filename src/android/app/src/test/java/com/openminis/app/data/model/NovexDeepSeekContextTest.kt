package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexDeepSeekContextTest {
    @Test fun v4WithoutMetadataUsesMillionForRuntimeAndGroupPicker() {
        listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp",
            "deepseek/deepseek-v4-flash").forEach { id ->
            val model = LLMModel(id, id, "Custom")
            assertEquals(id, 1_000_000, model.contextWindowTokens)
            assertEquals(id, 1_000_000, inferContextWindowTokens(model))
        }
    }
    @Test fun explicitProviderOrUserLimitStillWins() {
        val model = LLMModel("deepseek-v4-flash", "Flash", "Custom", contextWindow = 128_000)
        assertEquals(128_000, model.contextWindowTokens)
        assertEquals(128_000, inferContextWindowTokens(model))
        val entry = ModelEntry("relay", model, ModelOverrides(contextWindow = 64_000))
        assertEquals(64_000, entry.model.contextWindowTokens)
    }
    @Test fun olderModelsAndUnknownVariantsAreNotPromoted() {
        listOf("deepseek-v3", "deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash-custom").forEach { id ->
            val model = LLMModel(id, id, "Custom")
            assertEquals(128_000, model.contextWindowTokens)
        }
    }
    @Test fun officialCatalogUpgradePreservesUserChoicesAndDoesNotTouchRelays() {
        val direct = ProviderInstance("official", "深度求索", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://api.deepseek.com/v1/")
        val relay = direct.copy(id = "relay", customBaseURL = "https://relay.example/v1")
        val old = LLMModel("deepseek-v4-pro", "我的模型名称", "Custom", contextWindow = 128_000)
        val base = ModelEntry(direct.id, old, uuid = "keep-this-id")
        val overridden = base.copy(uuid = "manual-limit", overrides = ModelOverrides(contextWindow = 64_000))
        val custom = base.copy(uuid = "manual-model", isCustom = true)
        val relayEntry = base.copy(uuid = "relay-entry", providerInstanceId = relay.id)
        val config = ProviderConfig(instances = mutableListOf(direct, relay),
            modelEntries = mutableListOf(base, overridden, custom, relayEntry))
        val upgraded = NovexDeepSeekModelMetadata.repairCatalog(config)
        assertEquals(config.modelEntries.map { it.id }, upgraded.modelEntries.map { it.id })
        assertEquals(listOf(1_000_000, 64_000, 128_000, 128_000), upgraded.modelEntries.map { it.model.contextWindowTokens })
        assertEquals("我的模型名称", upgraded.modelEntries.first().model.displayName)
        assertEquals(upgraded, NovexDeepSeekModelMetadata.repairCatalog(upgraded))
        assertEquals(128_000, config.modelEntries.first().baseModel.contextWindow)
        assertEquals(old, NovexDeepSeekModelMetadata.official(old, "https://api.deepseek.com.evil.example/v1"))
    }

}
