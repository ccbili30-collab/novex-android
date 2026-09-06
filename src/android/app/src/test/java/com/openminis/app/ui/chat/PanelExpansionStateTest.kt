package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelExpansionStateTest {
    @Test
    fun `user collapse survives panel leaving and re-entering the visible list`() {
        val state = PanelExpansionState()
        val panelKey = "message-7:tool-2"

        assertTrue(state.value(panelKey, defaultExpanded = true))
        state.set(panelKey, expanded = false)

        assertFalse(state.value(panelKey, defaultExpanded = true))
    }

    @Test
    fun `different panel instances do not share expansion overrides`() {
        val state = PanelExpansionState()
        state.set("message-1:panel", expanded = false)

        assertFalse(state.value("message-1:panel", defaultExpanded = true))
        assertTrue(state.value("message-2:panel", defaultExpanded = true))
    }
}
