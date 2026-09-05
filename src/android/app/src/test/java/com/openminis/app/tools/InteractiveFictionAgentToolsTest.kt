package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun checkpointToolRequiresOneStructuredSnapshotInsteadOfRawMarkdown() {
        val definition = AgentTools.makeAgentTools()
            .single { it.name == "save_checkpoint" }

        assertEquals(listOf("name", "summary", "state_json"), definition.required)
        assertEquals(definition.required, definition.propertyOrdering)
        assertTrue(definition.description.contains("structured", ignoreCase = true))
        assertFalse(definition.parameters.containsKey("state"))
    }
}
