package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexModelSelectionTest {
    @Test
    fun `selecting another model preserves the existing selection`() {
        assertEquals(
            listOf("model-a", "model-b"),
            toggleModelSelection(listOf("model-a"), "model-b"),
        )
    }

    @Test
    fun `selecting an existing model removes only that model`() {
        assertEquals(
            listOf("model-b"),
            toggleModelSelection(listOf("model-a", "model-b"), "model-a"),
        )
    }

    @Test
    fun `common image model ids are recognized for ordering`() {
        assertEquals(true, looksLikeImageGenerationModel("gpt-image-2"))
        assertEquals(true, looksLikeImageGenerationModel("flux-1.1-pro"))
        assertEquals(false, looksLikeImageGenerationModel("deepseek-v4-flash"))
    }
}
