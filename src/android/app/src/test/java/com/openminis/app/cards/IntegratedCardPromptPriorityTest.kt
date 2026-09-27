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

    /**
     * [T-card-prompt-restructure] 结构契约（codex 研究模式 6）：平铺 15 条
     * 改分区块后，八个区块头+优先级声明必须在场；原句锚点抽样（编辑纪律/
     * 批量工具/文中立）逐字保留。重排或重写时弄丢任一即红。
     */
    @Test fun `restructured prompt carries section contract`() {
        val prompt = IntegratedCardPrompt.build(
            identity = "测试身份", memory = true, tools = setOf("read_card"),
        ).prompt
        listOf(
            "<优先级>", "<身份与对象>", "<资料与读取纪律>", "<卡片编辑协议>",
            "<创作组织与布局>", "<批量写入与微调>", "<整理纪律>", "<对话与工具边界>",
        ).forEach { section -> assertTrue("缺少区块 $section", prompt.contains(section)) }
        assertTrue(prompt.contains("更具体、更新且有效的要求优先"))
        assertTrue(prompt.contains("卡片编辑：先读取最新结构和版本"))
        assertTrue(prompt.contains("create_card_bulk / add_module_bulk"))
        assertTrue(prompt.contains("文中立：必须由你写的文字"))
    }
}
