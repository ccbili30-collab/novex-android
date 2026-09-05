package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexSystemPromptTest {
    @Test
    fun `tool prompt uses Novex references instead of raw Minis paths`() {
        val prompt = buildNovexToolWorldSection(
            sessionId = "session-safe-tools",
            memoryEnabled = true,
            persistentContext = "<世界核心规则>角色不能复活</世界核心规则>",
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
        assertTrue(section.contains("纯聊天模式"))
        assertFalse(section.contains("file_write"))
        assertFalse(section.contains("generate_image"))
    }
}
