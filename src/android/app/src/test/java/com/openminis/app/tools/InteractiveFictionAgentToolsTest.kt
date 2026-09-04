package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveFictionAgentToolsTest {
    @Test
    fun playthroughStateToolIsExposedOnlyWhileAGameIsActive() {
        val ordinary = AgentTools.makeAgentTools(interactiveFictionActive = false)
        val playing = AgentTools.makeAgentTools(interactiveFictionActive = true)

        assertFalse(ordinary.any { it.name == "update_playthrough_state" })
        assertTrue(playing.any { it.name == "update_playthrough_state" })
        assertTrue(playing.any { it.name == "register_controls" })
    }
}
