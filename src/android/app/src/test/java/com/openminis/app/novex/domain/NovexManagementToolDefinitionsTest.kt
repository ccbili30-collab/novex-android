package com.openminis.app.novex.domain

import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexManagementToolDefinitionsTest {
    @Test
    fun `management is exposed as inspect propose and confirmed apply`() {
        val definitions = AgentTools.makeAgentTools()
            .filter {
                it.name in setOf(
                    "novex_inspect_content",
                    "novex_propose_content_changes",
                    "novex_apply_content_changes",
                )
            }

        assertEquals(
            listOf(
                "novex_inspect_content",
                "novex_propose_content_changes",
                "novex_apply_content_changes",
            ),
            definitions.map { it.name },
        )
        val proposal = definitions.single { it.name == "novex_propose_content_changes" }
        val apply = definitions.single { it.name == "novex_apply_content_changes" }
        assertTrue("changes" in proposal.required)
        assertEquals(listOf("proposal_id"), apply.required)
        assertFalse("confirmed" in apply.parameters)
        assertTrue(apply.description.contains("real user"))
    }

    @Test
    fun `proposal contract tells the model the required fields for structured changes`() {
        val proposal = AgentTools.makeAgentTools().single { it.name == "novex_propose_content_changes" }
        val contract = requireNotNull(proposal.parameters["changes"]).description

        assertTrue(contract.contains("add_module"))
        assertTrue(contract.contains("module_type"))
        assertTrue(contract.contains("stable module types returned by inspect"))
        assertTrue(contract.contains("content_json"))
        assertTrue(contract.contains("create_character_version"))
        assertTrue(contract.contains("source_version_id"))
        assertTrue(contract.contains("attach_artifact"))
        assertTrue(contract.contains("artifact_id"))
        assertTrue(contract.contains("module_id"))
    }

    @Test
    fun `inspection publishes legal module names for each managed subject kind`() {
        val inspection = NovexManagementInspection(
            subjects = emptyList(),
            selectedSubject = null,
            selectedSubjectJson = null,
            modules = emptyList(),
            selectedModule = null,
        ).toToolJson()

        val catalog = inspection.getJSONObject("module_type_catalog")
        val worldTypes = catalog.getJSONArray("world")
        val gameTypes = catalog.getJSONArray("game")
        val worldNames = (0 until worldTypes.length()).map {
            worldTypes.getJSONObject(it).getString("value")
        }
        val gameNames = (0 until gameTypes.length()).map {
            gameTypes.getJSONObject(it).getString("value")
        }

        assertTrue("map" in worldNames)
        assertTrue("custom" in worldNames)
        assertTrue("narrative_rules" in gameNames)
        assertFalse(worldTypes.toString().contains("GAME_NARRATIVE_RULES"))
    }
}
