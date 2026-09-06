package com.openminis.app.novex.domain

import org.junit.Assert.*
import org.junit.Test

class NovexLegacyPromptProjectionTest {
    @Test
    fun `switching identity removes inactive generated role and player blocks while retaining conversation edits`() {
        val prompt = "用户写的考据要求\n<当前角色卡>旧角色的扮演秘密</当前角色卡>\n" +
            "<当前玩家身份>旧角色配套的玩家秘密</当前玩家身份>\n用户后来补充的文风要求"
        val configuration = NovexConversationConfigurationSnapshot("chat", answerIdentity = NovexPersonaPresets.gameHost,
            playerIdentity = ConversationPlayerIdentity("new-player", "记言人"))
        val result = NovexLegacyPromptProjection.project(prompt, configuration, "old-role", "old-player", null)
        assertFalse(result.contains("旧角色的扮演秘密"))
        assertFalse(result.contains("旧角色配套的玩家秘密"))
        assertTrue(result.contains("用户写的考据要求"))
        assertTrue(result.contains("用户后来补充的文风要求"))
    }
}
