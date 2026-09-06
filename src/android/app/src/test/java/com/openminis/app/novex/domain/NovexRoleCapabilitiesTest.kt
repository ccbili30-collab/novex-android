package com.openminis.app.novex.domain

import com.openminis.app.agent.buildNovexToolWorldSection
import com.openminis.app.data.character.CharacterCard
import com.openminis.app.data.character.CharacterSystemPromptComposer
import com.openminis.app.data.character.CharacterToolPolicy
import com.openminis.app.tools.AgentTools
import org.junit.Assert.*
import org.junit.Test

class NovexRoleCapabilitiesTest {
    @Test
    fun `changing persona retains available standard tools without enabling absent capabilities`() {
        val available = AgentTools.makeAgentTools(
            memoryEnabled = false, imageGenerationConfigured = false,
            interactiveFictionActive = true, documentsAvailable = true,
            sourceCollectionsAvailable = true, workspaceAvailable = true,
        ).mapTo(linkedSetOf()) { it.name }
        val character = CharacterCard(id = "c1", name = "艾琳", createdAt = 1, updatedAt = 1)
        assertTrue("novex_propose_content_changes" in available)
        assertTrue("workspace_write" in available)
        assertTrue("register_controls" in available)
        assertEquals(available, CharacterToolPolicy.allowedToolNames(character, available))
        assertEquals(available, CharacterToolPolicy.allowedToolNames(
            character.copy(allowedTools = listOf("shell_execute", "generate_image")), available,
        ))
        assertFalse("shell_execute" in available)
        assertFalse("generate_image" in available)
        assertFalse("novex_apply_memory_changes" in available)
    }

    @Test
    fun `both personas receive the application card creation and source structure protocol`() {
        val tools = AgentTools.makeAgentTools().mapTo(linkedSetOf()) { it.name }
        val role = CharacterCard(id = "c1", name = "艾琳", createdAt = 1, updatedAt = 1)
        val prompts = listOf(
            buildNovexToolWorldSection("session", true, "", tools),
            CharacterSystemPromptComposer.compose(role.toJson().toString(), null, null, tools),
        )
        prompts.forEach { prompt ->
            assertTrue(prompt.contains("默认创建应用内卡片"))
            assertTrue(prompt.contains("章节、主题与条目"))
            assertTrue(prompt.contains("不得把整份资料塞进一个模块"))
            assertTrue(prompt.contains("content_example"))
            assertTrue(prompt.contains("保存后重新读取"))
        }
    }
}
