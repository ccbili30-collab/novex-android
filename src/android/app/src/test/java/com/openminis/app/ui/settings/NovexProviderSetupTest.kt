package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexProviderSetupTest {
    @Test fun officialDefaultUsesV4FlashIdentifier() {
        assertEquals("deepseek-v4-flash", NOVEX_DEFAULT_DEEPSEEK_MODEL)
    }

    @Test fun officialDefaultHasLocalizedDisplayName() {
        assertEquals(
            "DeepSeek V4 Flash（深度求索 V4 快速版）",
            novexModelDisplayName(NOVEX_DEFAULT_DEEPSEEK_MODEL),
        )
    }

    @Test fun visionNamedChatModelKeepsImageInputCapability() {
        assertEquals(
            listOf("text", "image"),
            novexChatInputModalities("deepseek-v4-flash-vision"),
        )
    }

    @Test fun existingImageInputOverrideIsNotErasedWhenConnectionIsSavedAgain() {
        assertEquals(
            listOf("text", "image"),
            novexChatInputModalities(
                modelId = "relay-custom-model",
                existingInputModalities = listOf("text", "image"),
            ),
        )
    }

    @Test fun ordinaryChatModelDoesNotGainVisionByDefault() {
        assertEquals(listOf("text"), novexChatInputModalities("deepseek-v4-flash"))
    }

    @Test fun fetchedCapacitySurvivesSetupSaveAndOverridesRemainIndependent() {
        val previous = com.openminis.app.data.model.LLMModel("relay-alias","Alias","Relay",contextWindow=128000)
        val fetched = previous.copy(contextWindow=1048576)
        val saved = novexConnectionModel(fetched,previous,"https://relay.example/v1")
        assertEquals(1048576,saved.contextWindow)
        val entry = com.openminis.app.data.model.ModelEntry("relay",saved,
            com.openminis.app.data.model.ModelOverrides(contextWindow=262144))
        assertEquals(262144,entry.model.contextWindowTokens)
    }
}
