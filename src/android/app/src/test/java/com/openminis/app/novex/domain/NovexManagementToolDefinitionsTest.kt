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
            .filter { it.name.startsWith("novex_") }

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
}
