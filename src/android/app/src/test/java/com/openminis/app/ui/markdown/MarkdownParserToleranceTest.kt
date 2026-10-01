package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [血统清剿 P3.7 行为钉] MarkdownParser 解析容忍度抽验——重写后把最容易
 * 退化的容忍路径钉死：围栏代码、表格链接竖线、数学抽取（代码掩码/引用标
 * 记剥离/货币启发）、有序表两位数上限、独立媒体行路由。
 */
class MarkdownParserToleranceTest {

    // ── 块解析 ─────────────────────────────────────────────────────────────

    @Test
    fun `fenced code block keeps body verbatim and language label`() {
        val blocks = MarkdownParser.parse(
            "before\n```kotlin\nval x = 1\n```\nafter",
        )
        val code = blocks.filterIsInstance<MarkdownParser.Block.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `unclosed fence swallows the rest as code`() {
        val blocks = MarkdownParser.parse("```python\nprint(1)\nprint(2)")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownParser.Block.CodeBlock
        assertEquals("print(1)\nprint(2)", code.code)
    }

    @Test
    fun `table parses header alignment and rows`() {
        val blocks = MarkdownParser.parse(
            "| Name | Score |\n|:-----|------:|\n| a | 1 |\n| b | 2 |",
        )
        val table = blocks.filterIsInstance<MarkdownParser.Block.Table>().single()
        assertEquals(listOf("Name", "Score"), table.headers)
        assertEquals(MarkdownParser.Alignment.LEFT, table.alignments[0])
        assertEquals(MarkdownParser.Alignment.RIGHT, table.alignments[1])
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a", "1"), table.rows[0])
    }

    @Test
    fun `markdown link with raw pipe in url does not split the cell`() {
        // [T-minis-url-fullwidth-pipe-android]：链接 URL 内的裸 `|` 不得被
        // 当作单元格分隔符。
        val blocks = MarkdownParser.parse(
            "| link | plain |\n|---|---|\n| [f](minis://ns/a|b.png) | x |",
        )
        val table = blocks.filterIsInstance<MarkdownParser.Block.Table>().single()
        val cell = table.rows[0][0]
        assertTrue("cell must keep the full link: $cell", cell.contains("minis://ns/a|b.png"))
    }

    @Test
    fun `numbered list marker capped at two digits - year paragraph is not a list`() {
        // 「2020. 年的…」不得变成第 2020 项。
        val para = MarkdownParser.parse("2020. 年的时候一切都很简单。").single()
        assertTrue(para is MarkdownParser.Block.Paragraph)
        val list = MarkdownParser.parse("1. first\n2. second\n3. third")
            .filterIsInstance<MarkdownParser.Block.NumberedList>()
            .single()
        assertEquals(1, list.startNumber)
        assertEquals(3, list.items.size)
    }

    @Test
    fun `task list items parse checked state`() {
        val list = MarkdownParser.parse("- [x] done\n- [ ] todo\n- plain")
            .filterIsInstance<MarkdownParser.Block.BulletList>()
            .single()
        assertEquals(true, list.items[0].checked)
        assertEquals(false, list.items[1].checked)
        assertEquals(null, list.items[2].checked)
    }

    @Test
    fun `standalone image line routes by extension`() {
        val blocks = MarkdownParser.parse(
            "![alt text](minis://workspace/clip.mp4)\n\n![pic](https://e/x.png)\n\n![song](minis://a/b.mp3)",
        )
        assertEquals(
            MarkdownParser.Block.Video("alt text", "minis://workspace/clip.mp4"),
            blocks[0],
        )
        assertEquals(MarkdownParser.Block.Image("pic", "https://e/x.png"), blocks[1])
        assertEquals(MarkdownParser.Block.Audio("song", "minis://a/b.mp3"), blocks[2])
    }

    @Test
    fun `image-like line that is not standalone stays a paragraph`() {
        val blocks = MarkdownParser.parse("text ![a](u.png) more text on one line")
        assertTrue(blocks.single() is MarkdownParser.Block.Paragraph)
    }

    // ── 数学抽取 ───────────────────────────────────────────────────────────

    @Test
    fun `display and inline math extracted with placeholders and restored`() {
        val result = MarkdownParser.parseWithMath(
            "前文 \$x+y\$ 中段\n\n$$\nE = mc^2\n$$\n",
        )
        val mathBlock = result.blocks.filterIsInstance<MarkdownParser.Block.MathBlock>().single()
        assertEquals("\nE = mc^2\n", mathBlock.latex)
        // 行内占位符留在段落里供行内趟渲染。
        val para = result.blocks.filterIsInstance<MarkdownParser.Block.Paragraph>().first()
        assertTrue(para.content.contains("x+y").not()) // latex 已被占位符替换
        assertEquals(2, result.mathSpans.size)
        assertEquals(false, result.mathSpans[0].isBlock)
        assertEquals(true, result.mathSpans[1].isBlock)
    }

    @Test
    fun `dollar signs inside fenced code survive math extraction`() {
        val result = MarkdownParser.parseWithMath(
            "```\ncost = \$5 and \$\$big\$\$\n```\n\n\$实\$",
        )
        val code = result.blocks.filterIsInstance<MarkdownParser.Block.CodeBlock>().single()
        assertTrue(code.code.contains("\$\$big\$\$"))
        // 代码内的 $$ 不产生 span。
        assertTrue(result.mathSpans.none { it.latex.contains("big") })
    }

    @Test
    fun `stray unclosed double dollar does not swallow prose before a later code fence`() {
        // [T-android-latex-code-mask] 缺陷三的回归钉：散文里的裸 $$ 与后面
        // 围栏里的 $$ 不得跨代码边界配对。
        val result = MarkdownParser.parseWithMath(
            "价格是 \$\$ 一大段散文还在这里\n\n更多段落\n\n```\ninner \$\$x\$\$\n```\n",
        )
        val paras = result.blocks.filterIsInstance<MarkdownParser.Block.Paragraph>()
        assertTrue("prose must survive", paras.any { it.content.contains("一大段散文") })
        val code = result.blocks.filterIsInstance<MarkdownParser.Block.CodeBlock>().single()
        assertTrue(code.code.contains("inner \$\$x\$\$"))
        assertTrue("no math span may pair across the code boundary", result.mathSpans.isEmpty())
    }

    @Test
    fun `currency dollars stay plain text`() {
        val result = MarkdownParser.parseWithMath("咖啡 \$5，续杯 \$5.99。")
        assertTrue(result.mathSpans.isEmpty())
        val para = result.blocks.single() as MarkdownParser.Block.Paragraph
        assertTrue(para.content.contains("\$5.99"))
    }

    @Test
    fun `single letter math is recognized`() {
        // T208 B 层：`$c$` 这类单字母数学不得漏进纯文本。
        val result = MarkdownParser.parseWithMath("光速是 \$c\$ 不是别的。")
        assertEquals(1, result.mathSpans.size)
        assertEquals("c", result.mathSpans[0].latex)
    }

    @Test
    fun `blockquote markers stripped from multiline display math`() {
        val result = MarkdownParser.parseWithMath("> $$\n> E = mc^2\n> $$")
        // 引用里的展示式：还原后嵌在 Blockquote 内层。
        val quote = result.blocks.filterIsInstance<MarkdownParser.Block.Blockquote>().single()
        val math = quote.blocks.filterIsInstance<MarkdownParser.Block.MathBlock>().single()
        assertEquals("\nE = mc^2\n", math.latex)
        assertTrue(math.latex.contains(">").not())
    }

    @Test
    fun `multiline math body with paragraph break is not a formula`() {
        // 空行 = 段落断——未闭合的 $$ 即便闭符在代码外也不吞散文。
        val result = MarkdownParser.parseWithMath("$$ 开始\n\n段落还在。\n\n$$ 结束")
        val paras = result.blocks.filterIsInstance<MarkdownParser.Block.Paragraph>()
        assertTrue(paras.any { it.content.contains("段落还在") })
    }

    @Test
    fun `latex style delimiters are extracted`() {
        val result = MarkdownParser.parseWithMath(
            "行内 \\(a+b\\) 与展示 \\[c=d\\]",
        )
        assertEquals(2, result.mathSpans.size)
        assertEquals(false, result.mathSpans[0].isBlock)
        assertEquals(true, result.mathSpans[1].isBlock)
        assertEquals("c=d", result.mathSpans[1].latex)
    }
}
