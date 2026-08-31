package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexSystemPromptTest {
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
