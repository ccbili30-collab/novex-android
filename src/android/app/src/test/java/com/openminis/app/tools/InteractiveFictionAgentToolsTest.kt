package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveFictionAgentToolsTest {
    @Test
    fun endingAGameRequiresItsSnapshotAndIsUnavailableOutsidePlay() {
        assertFalse(AgentTools.makeAgentTools().any { it.name == "end_interactive_fiction" })
        val tool = AgentTools.makeAgentTools(interactiveFictionActive = true).single { it.name == "end_interactive_fiction" }
        assertEquals(listOf("playthrough_id"), tool.required)
        assertTrue(tool.description.contains("明确结束"))
    }

    @Test
    fun playthroughStateToolIsExposedOnlyWhileAGameIsActive() {
        val ordinary = AgentTools.makeAgentTools(interactiveFictionActive = false)
        val playing = AgentTools.makeAgentTools(interactiveFictionActive = true)

        assertFalse(ordinary.any { it.name == "update_playthrough_state" })
        assertTrue(playing.any { it.name == "update_playthrough_state" })
        assertTrue(playing.any { it.name == "register_controls" })
    }

    @Test
    fun ordinaryCheckpointRequiresOnlyNameAndKeepsOptionalStructuredNotes() {
        val definition = AgentTools.makeAgentTools()
            .single { it.name == "save_checkpoint" }

        assertEquals(listOf("name"), definition.required)
        assertEquals(listOf("name", "summary", "state_json"), definition.propertyOrdering)
        assertTrue(definition.parameters.keys.containsAll(listOf("summary", "state_json")))
        assertTrue(definition.description.contains("application captures original branch messages"))
        assertTrue(definition.description.contains("structured", ignoreCase = true))
        assertFalse(definition.parameters.containsKey("state"))
    }
}
