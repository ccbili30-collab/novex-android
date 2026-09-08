package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexSystemPromptTest {
    @Test fun `reply teaching contains only available presentation and state operations`() {
        val creation = buildNovexReplyStructure(setOf("novex_write_card", "present_choices"))
        assertTrue(creation.contains("present_choices"))
        listOf("render_panel", "save_checkpoint", "register_controls", "update_playthrough_state").forEach {
            assertFalse(creation.contains(it))
            assertTrue(buildNovexReplyStructure(setOf(it)).contains(it))
        }
        assertTrue(creation.contains("创作或修改请求完成后自然结束"))
    }

    @Test fun `only the exact obsolete factory style is projected and custom text remains untouched`() {
        val old = SoulStore.LEGACY_NOVEX_DEFAULT_BODY
        assertFalse(SoulStore.currentDefaultStyle(old).contains("直接服务当前文游"))
        assertTrue(SoulStore.currentDefaultStyle(old).contains("直接回应用户当前的请求"))
        val custom = old + "\n本对话我明确要求你做游戏主持。"
        org.junit.Assert.assertEquals(custom, SoulStore.currentDefaultStyle(custom))
        org.junit.Assert.assertEquals("", SoulStore.currentDefaultStyle(""))
        assertTrue(SoulStore.LEGACY_NOVEX_DEFAULT_BODY.contains("直接服务当前文游"))
    }

    @Test
    fun `tool prompt uses Novex references instead of raw Minis paths`() {
        val prompt = buildNovexToolWorldSection(
            sessionId = "session-safe-tools",
            memoryEnabled = true,
            persistentContext = "<世界核心规则>角色不能复活</世界核心规则>",
            availableToolNames = com.openminis.app.tools.AgentTools.makeAgentTools(workspaceAvailable = true)
                .mapTo(linkedSetOf()) { it.name },
        )

        assertTrue(prompt.contains("workspace_inspect"))
        assertTrue(prompt.contains("workspace_read"))
        assertFalse(prompt.contains("shell_execute"))
        assertFalse(prompt.contains("file_read"))
        assertFalse(prompt.contains("/var/minis/"))
    }

    @Test
    fun `pure chat world section keeps loaded world context without tool instructions`() {
        val section = buildNovexPureWorldSection(
            sessionId = "world-session",
            memoryEnabled = true,
            persistentContext = "<世界核心规则>角色不能复活</世界核心规则>",
        )

        assertTrue(section.contains("角色不能复活"))
        assertTrue(section.contains("当前对话未启用工具"))
        assertFalse(section.contains("file_write"))
        assertFalse(section.contains("generate_image"))
    }
}
