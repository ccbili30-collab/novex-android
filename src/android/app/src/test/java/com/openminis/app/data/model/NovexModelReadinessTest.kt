package com.openminis.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexModelReadinessTest {
    private val provider = ProviderInstance(
        id = "provider",
        label = "DeepSeek（深度求索）",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )
    private val entry = ModelEntry(
        providerInstanceId = provider.id,
        baseModel = LLMModel("deepseek-v4-flash", "DeepSeek V4 Flash（深度求索 V4 快速版）", "DeepSeek（深度求索）"),
    )

    @Test fun emptyConfigurationIsNotReady() {
        assertFalse(ProviderConfig().hasUsableNovexModel())
    }

    @Test fun providerWithoutGroupedModelIsNotReady() {
        assertFalse(ProviderConfig(instances = mutableListOf(provider)).hasUsableNovexModel())
    }

    @Test fun disabledProviderIsNotReady() {
        val disabled = provider.copy(isEnabled = false)
        val config = ProviderConfig(
            instances = mutableListOf(disabled),
            modelEntries = mutableListOf(entry),
            modelGroups = mutableListOf(ModelGroup(name = "默认模型", memberEntryIds = mutableListOf(entry.id))),
        )
        assertFalse(config.hasUsableNovexModel())
    }

    @Test fun enabledGroupedModelIsReady() {
        val config = ProviderConfig(
            instances = mutableListOf(provider),
            modelEntries = mutableListOf(entry),
            modelGroups = mutableListOf(ModelGroup(name = "默认模型", memberEntryIds = mutableListOf(entry.id))),
        )
        assertTrue(config.hasUsableNovexModel())
    }
}
