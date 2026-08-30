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
}
