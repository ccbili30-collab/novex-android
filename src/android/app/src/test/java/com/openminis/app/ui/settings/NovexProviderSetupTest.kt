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
}
