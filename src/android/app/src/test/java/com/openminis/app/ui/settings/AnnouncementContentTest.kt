package com.openminis.app.ui.settings

import com.openminis.app.ui.markdown.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-announcement-hero] hero 正文可见块行为锁（净眼退回件）：真实公告
 * md 首行恒为 `# 标题`，其后才是元信息段——跳过逻辑必须穿透 H1，让
 * hero 正文首段落位真导语（横幅已承载标题/日期/渠道）。
 */
class AnnouncementContentTest {

    @Test
    fun `hero visible blocks skip leading title and meta lines`() {
        val markdown = """
            # Novex 3.0.5 正式版发布

            **日期**：2026-09-28
            **通道**：stable

            导语：本次发布聚焦三个方向。

            ## 新增

            - **模块标签系统**：由标签决定携带与重注入策略。
        """.trimIndent()
        val visible = heroVisibleBlocks(MarkdownParser.parse(markdown))
        val first = visible.first()
        assertTrue(first is MarkdownParser.Block.Paragraph)
        val content = (first as MarkdownParser.Block.Paragraph).content
        assertTrue("首块应落在真导语，实为：$content", content.contains("导语"))
        assertFalse(content.contains("日期"))
    }

    @Test
    fun `hero visible blocks tolerate body without leading title`() {
        val markdown = "**日期**：2026-09-28\n\n导语段。"
        val visible = heroVisibleBlocks(MarkdownParser.parse(markdown))
        val first = visible.first()
        assertTrue(first is MarkdownParser.Block.Paragraph)
        assertTrue((first as MarkdownParser.Block.Paragraph).content.contains("导语"))
    }
}
