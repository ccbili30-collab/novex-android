package com.openminis.app.data.model

import org.junit.Assert.*
import org.junit.Test

class TemporaryPreviewModelTest {
    private fun userConfig(): ProviderConfig {
        val instance = ProviderInstance("mine", "DeepSeek API", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://my-relay.example/v1")
        val entry = ModelEntry("mine", LLMModel("deepseek-flash", "My Flash", "deepseek", contextWindow = 200000),
            overrides = ModelOverrides(contextWindow = 64000), uuid = "mine/deepseek-flash")
        return ProviderConfig(mutableListOf(instance), mutableListOf(entry),
            mutableListOf(ModelGroup("my-group", "DeepSeek API", mutableListOf(entry.id))),
            defaultPrimaryGroupId = "my-group", defaultSubGroupId = "my-group")
    }
    @Test fun sameProviderModelAndDisplayNamesDoNotMergeOrReplaceUserChoices() {
        val before = userConfig()
        val after = TemporaryPreviewModel.install(before, true)
        assertEquals(1, before.instances.size)
        assertEquals(before.instances.single(), after.instances.first())
        assertEquals(before.modelEntries.single(), after.modelEntries.first())
        assertEquals(before.modelGroups.single(), after.modelGroups.first())
        assertEquals("my-group", after.defaultPrimaryGroupId)
        assertEquals("my-group", after.defaultSubGroupId)
        assertEquals(2, after.instances.size)
        assertEquals(2, after.modelEntries.size)
        assertEquals(TemporaryPreviewModel.ENTRY_ID, after.modelGroups.last().memberEntryIds.single())
        assertEquals(1_048_576, after.modelEntries.last().model.contextWindowTokens)
    }
    @Test fun reinstallRestoresOnlyReservedConfigurationWithoutDuplicatingIt() {
        val config = TemporaryPreviewModel.install(userConfig(), true)
        config.instances.last().customBaseURL = "https://wrong.example"
        config.modelEntries[1] = config.modelEntries.last().copy(isHidden = true, overrides = ModelOverrides(contextWindow = 1))
        config.modelGroups.last().memberEntryIds.clear()
        val restored = TemporaryPreviewModel.install(config, true)
        assertEquals(2, restored.instances.size)
        assertEquals("https://api.deepseek.com/v1", restored.instances.last().effectiveBaseURL)
        assertFalse(restored.modelEntries.last().isHidden)
        assertTrue(restored.modelEntries.last().overrides.isEmpty)
        assertEquals(listOf(TemporaryPreviewModel.ENTRY_ID), restored.modelGroups.last().memberEntryIds)
        assertEquals(64000, restored.modelEntries.first().model.contextWindowTokens)
    }
    @Test fun buildWithoutCredentialRemovesOnlyTemporaryReferences() {
        val config = TemporaryPreviewModel.install(userConfig(), true)
        config.modelGroups.first().memberEntryIds.add(TemporaryPreviewModel.ENTRY_ID)
        config.defaultSubGroupId = TemporaryPreviewModel.GROUP_ID
        val removed = TemporaryPreviewModel.install(config, false)
        assertEquals(listOf("mine"), removed.instances.map { it.id })
        assertEquals(listOf("mine/deepseek-flash"), removed.modelEntries.map { it.id })
        assertEquals(listOf("mine/deepseek-flash"), removed.modelGroups.single().memberEntryIds)
        assertEquals("my-group", removed.defaultPrimaryGroupId)
        assertNull(removed.defaultSubGroupId)
    }
}
