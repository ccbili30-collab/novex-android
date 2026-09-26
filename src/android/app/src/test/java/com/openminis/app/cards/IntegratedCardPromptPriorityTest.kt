package com.openminis.app.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-identity-priority]（用户 2026-09-27 报告："对话设置写了对话提示词
 * 他却不会代入"）守护：挂卡链路下用户对话提示词（identitySection）必须位于
 * system 开头、在全部卡片管理条款之前；空 identity 不产生空块；尾部不再有
 * 旧的"用户保存的对话要求"双份块。
 */
class IntegratedCardPromptPriorityTest {

    @Test fun `user instructions lead the carded system prompt`() {
        val prepared = IntegratedCardPrompt.build(
            identity = "你扮演隐世剑客顾长风，说话简短冷淡，绝不用现代词汇。",
            memory = false, tools = setOf("read_card"),
        )
        val prompt = prepared.prompt
        val identityIdx = prompt.indexOf("<用户为本对话设定的指令>")
        val contractIdx = prompt.indexOf("卡片编辑：")
        assertTrue("用户指令块必须在场", identityIdx >= 0)
        assertTrue("卡片契约必须在场", contractIdx > identityIdx)
        assertTrue("用户指令必须先于卡片契约", identityIdx < contractIdx)
        assertTrue(prompt.contains("顾长风"))
    }

    @Test fun `blank identity yields no block and no legacy tail`() {
        val prepared = IntegratedCardPrompt.build(identity = "  ", memory = false, tools = emptySet())
        assertFalse(prepared.prompt.contains("<用户为本对话设定的指令>"))
        assertFalse(prepared.prompt.contains("用户保存的对话要求"))
    }

    @Test fun `prompt body still carries card contract lines`() {
        val prepared = IntegratedCardPrompt.build(identity = "", memory = true, tools = setOf("read_card"))
        assertTrue(prepared.prompt.contains("你运行在 Novex（诺文）中"))
        assertTrue(prepared.prompt.contains("长期记忆当前开启"))
        assertTrue("memory=false 时应为关闭态",
            IntegratedCardPrompt.build("", memory = false, tools = emptySet()).prompt.contains("长期记忆当前关闭"))
    }
}
