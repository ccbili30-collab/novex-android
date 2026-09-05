package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterCard
import com.openminis.app.data.character.CharacterToolPolicy
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.NovexMemoryAgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexMemoryToolDefinitionsTest {
    @Test
    fun standardMemoryToolsReplaceRawDailyLogToolsAndRespectTheSessionToggle() {
        val enabled = AgentTools.makeAgentTools(memoryEnabled = true)
        val disabled = AgentTools.makeAgentTools(memoryEnabled = false)
        val names = NovexMemoryAgentTools.TOOL_NAMES

        assertTrue(enabled.map { it.name }.containsAll(names))
        assertFalse(enabled.any { it.name == "memory_write" || it.name == "memory_get" })
        assertFalse(disabled.any { it.name in names })

        val proposal = enabled.single { it.name == NovexMemoryAgentTools.PROPOSE }
        assertEquals(listOf("changes"), proposal.required)
        val apply = enabled.single { it.name == NovexMemoryAgentTools.APPLY }
        assertEquals(listOf("proposal_id"), apply.required)
        assertFalse(apply.parameters.containsKey("confirmation"))
    }

    @Test
    fun confirmedMemoryToolsRemainAvailableToAClosedRoleConversation() {
        val available = AgentTools.makeAgentTools(memoryEnabled = true).mapTo(mutableSetOf()) { it.name }
        val character = CharacterCard(id = "role-1", name = "苏晚晴", createdAt = 1, updatedAt = 1)

        val allowed = CharacterToolPolicy.allowedToolNames(character, available)

        assertTrue(allowed.containsAll(NovexMemoryAgentTools.TOOL_NAMES))
        assertFalse("角色对话仍不能获得原始文件工具", "file_write" in allowed)
    }
}
