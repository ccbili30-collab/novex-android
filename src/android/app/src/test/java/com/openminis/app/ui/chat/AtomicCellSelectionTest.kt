package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Long-press selection boundaries for table cells vs prose.
 *
 * [P4] 行为钉升级：血统清剿把边界算法从 SelectionController 私有方法提为
 * 顶层 internal 纯函数（[atomicCellBounds] / [sentenceSelectionBounds]），
 * 本测试从「镜像算法」升级为直接钉真实实现。
 *
 * A table cell selects in FULL; prose keeps the sentence-level expansion. The
 * distinction matters because most cells are punctuation-free ("Alice Smith"),
 * so the sentence scan happens to grab the whole cell and hides the bug — until
 * a cell contains a comma or a period ("1,200", "v1.2 beta"), where the scan
 * stops mid-cell and the user gets a fragment of the thing they pressed.
 */
class AtomicCellSelectionTest {

    private fun select(text: String, bounds: Pair<Int, Int>) =
        text.substring(bounds.first, bounds.second)

    @Test
    fun `a cell containing punctuation still selects in full`() {
        // This is the case the sentence scan gets wrong.
        val cell = "1,200"
        assertEquals("1,200", select(cell, atomicCellBounds(cell)))
        assertEquals(
            "sentence expansion would stop at the comma",
            "1,", select(cell, sentenceSelectionBounds(cell, 0)),
        )
    }

    @Test
    fun `a cell with a version string selects in full`() {
        val cell = "v1.2 beta"
        assertEquals("v1.2 beta", select(cell, atomicCellBounds(cell)))
    }

    @Test
    fun `a punctuation-free cell selects in full either way`() {
        val cell = "Alice Smith"
        assertEquals("Alice Smith", select(cell, atomicCellBounds(cell)))
        assertEquals("Alice Smith", select(cell, sentenceSelectionBounds(cell, 3)))
    }

    @Test
    fun `a CJK cell selects in full`() {
        val cell = "张三，项目经理"
        assertEquals("张三，项目经理", select(cell, atomicCellBounds(cell)))
    }

    /** Surrounding whitespace is trimmed so the highlight hugs the content. */
    @Test
    fun `padding around cell text is not selected`() {
        val cell = "  spaced value  "
        assertEquals("spaced value", select(cell, atomicCellBounds(cell)))
    }

    @Test
    fun `an empty or blank cell yields an empty range`() {
        assertEquals(0 to 0, atomicCellBounds(""))
        assertEquals(0 to 0, atomicCellBounds("   "))
    }

    /** The press offset is irrelevant for a cell — the whole cell is the unit. */
    @Test
    fun `selection is independent of where inside the cell the press landed`() {
        val cell = "alpha, beta, gamma"
        val expected = select(cell, atomicCellBounds(cell))
        for (offset in cell.indices) {
            assertEquals(expected, select(cell, atomicCellBounds(cell)))
        }
        assertEquals("alpha, beta, gamma", expected)
    }

    /** Prose must NOT become atomic — the sentence behaviour is still wanted. */
    @Test
    fun `prose keeps sentence-level expansion`() {
        val prose = "Hello world. Second sentence here."
        assertEquals("Hello world.", select(prose, sentenceSelectionBounds(prose, 2)))
    }

    /** 句停点计入选区（复制时标点跟上更自然），两端修空白。 */
    @Test
    fun `sentence bounds include the trailing stop and trim whitespace`() {
        val prose = "第一句结束。  第二句"
        assertEquals("第一句结束。", select(prose, sentenceSelectionBounds(prose, 2)))
    }

    /** 空白不是停点：词间空白上的长按仍做句级扩展，吃整句。 */
    @Test
    fun `a press on whitespace between words expands to the whole sentence`() {
        val prose = "word   word"
        assertEquals("word   word", select(prose, sentenceSelectionBounds(prose, 4)))
    }

    /** 纯停点文本退化为单字符——选区永不折叠为零宽。 */
    @Test
    fun `a press inside bare punctuation falls back to one character`() {
        assertEquals(",", select(",", sentenceSelectionBounds(",", 0)))
    }

    /** 偏移越界被钳制，不抛异常。 */
    @Test
    fun `out-of-range offsets are clamped`() {
        val prose = "abcdef"
        assertEquals("abcdef", select(prose, sentenceSelectionBounds(prose, 99)))
        assertEquals("abcdef", select(prose, sentenceSelectionBounds(prose, -5)))
        assertEquals(0 to 0, sentenceSelectionBounds("", 3))
    }
}
