package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-live-tool-tail] dsh/codex 式滚动尾巴的纯函数守护：bulk 工具解析最近
 * 模块名 + 累积字数；写作工具显示正文片段；其它工具原始尾部；只扫尾部
 * 窗口（卡帧约束）；空串不显示。
 */
class ToolLiveTailTest {

    @Test fun `bulk tool shows latest module name and accumulated size`() {
        // 会话 b314941f 形状的流式片段：模块树边生成边到达。
        val partial = """{"kind":"WORLD","name":"大明百鬼录","modules":[{"name":"00·启动说明","text":"…""""
        val tail = ToolLiveTail.liveTail("create_card_bulk", partial)
        // 尾部窗口里最近的 name 是 00·启动说明（卡片名更早，已被窗口语义
        // 天然覆盖——显示的是"最新出现的名字"，对用户就是当前进度）。
        assertEquals("00·启动说明 · 已生成 ${partial.length} 字", tail)
        val addTail = ToolLiveTail.liveTail("add_module_bulk", """{"module":{"name":"12·事件池","tex""")
        assertEquals("12·事件池 · 已生成 ${("""{"module":{"name":"12·事件池","tex""").length} 字", addTail)
    }

    @Test fun `writing tool shows text fragment`() {
        val partial = """{"module_id":"m1","block_id":"","name":"03·世界观","text":"皖城建安四年，大火之后"""
        val tail = ToolLiveTail.liveTail("write_module_text", partial)
        assertTrue("应以正文片段开头: $tail", tail!!.startsWith("正文片段 · "))
        assertTrue(tail.contains("皖城"))
    }

    @Test fun `other tools show raw tail flattened`() {
        val tail = ToolLiveTail.liveTail("document_read", "第一行\n第二行\n最后一行内容")
        assertTrue("换行应压平: $tail", !tail!!.contains('\n'))
        assertTrue(tail.contains("最后一行内容"))
    }

    @Test fun `blank input hides the tail`() {
        assertNull(ToolLiveTail.liveTail("create_card_bulk", ""))
        assertNull(ToolLiveTail.liveTail("create_card_bulk", "   "))
    }

    @Test fun `long bulk args only scan the tail window`() {
        // 24 万字上限形状：早期模块名不在窗口里，尾巴只反映窗口内最新名。
        val filler = """{"name":"01·引擎宪法","text":"${"宪".repeat(3000)}"},"""
        val late = """{"name":"16·黄金回合样本","text":"样本"""
        val args = """{"modules":[""" + filler.repeat(80) + late
        val tail = ToolLiveTail.liveTail("create_card_bulk", args)
        assertTrue("应显示窗口内最新的 16 号模块: $tail", tail!!.startsWith("16·黄金回合样本 · 已生成 ${args.length} 字"))
    }
}
