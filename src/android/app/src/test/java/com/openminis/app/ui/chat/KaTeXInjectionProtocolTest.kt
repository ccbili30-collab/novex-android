package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [血统清剿 P3.7 行为钉] KaTeX WebView 注入协议——两端（Kotlin 求值侧与
 * assets HTML 承接侧）的契约形状逐字钉死：协议漂移即测试红，双向守护
 * `renderMath(...)` 调用串、桥名、缓存键与 html 资产的函数签名。
 */
class KaTeXInjectionProtocolTest {

    // ── Kotlin 求值侧（KatexWebViewPool / KaTeXView 共用同一协议） ────────

    @Test
    fun `renderMath call shape - display mode`() {
        val js = KatexWebViewPool.buildRenderMathCall(
            latex = "E = mc^2",
            displayMode = true,
            fontSizePx = 17,
            isDark = false,
        )
        assertEquals("renderMath('E = mc^2', true, 17, false)", js)
    }

    @Test
    fun `renderMath call shape - inline mode dark theme`() {
        val js = KatexWebViewPool.buildRenderMathCall(
            latex = "a+b",
            displayMode = false,
            fontSizePx = 20,
            isDark = true,
        )
        assertEquals("renderMath('a+b', false, 20, true)", js)
    }

    @Test
    fun `latex escaping survives backslash quote newline and CR`() {
        val js = KatexWebViewPool.buildRenderMathCall(
            latex = "\\frac{1}{2}\nnext 'q'\r",
            displayMode = false,
            fontSizePx = 17,
            isDark = false,
        )
        // 反斜杠翻倍、单引号转义、换行转 \n 字面、回车剔除。
        assertTrue(js.contains("'\\\\frac{1}{2}\\nnext \\'q\\''"))
        assertTrue(js.contains("\\r").not())
    }

    @Test
    fun `cache key format carries mode theme and font size`() {
        assertEquals("D:k:17:x", KatexWebViewPool.cacheKeyForTest("x", displayMode = true, isDark = true, fontSizePx = 17))
        assertEquals("I:l:20:x", KatexWebViewPool.cacheKeyForTest("x", displayMode = false, isDark = false, fontSizePx = 20))
    }

    @Test
    fun `bridge name is AndroidBridge`() {
        // 反射确认桥名常量未被顺手改名（协议另一半在 JS 侧硬编码）。
        val src = java.io.File(
            "src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt",
        )
        org.junit.Assume.assumeTrue(src.exists())
        assertTrue(src.readText().contains("\"AndroidBridge\""))
    }

    // ── HTML 承接侧（assets/katex/katex-render.html） ─────────────────────

    private fun katexHtml(): String {
        val candidates = listOf(
            java.io.File("src/main/assets/katex/katex-render.html"),
            java.io.File("app/src/main/assets/katex/katex-render.html"),
        )
        val file = candidates.firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue("katex-render.html not found from test working dir", file != null)
        return file!!.readText()
    }

    @Test
    fun `html asset exposes renderMath with the four-arg protocol`() {
        val html = katexHtml()
        assertTrue(
            "html must declare function renderMath(latex, displayMode, fontSize, isDark)",
            Regex("""function\s+renderMath\s*\(\s*latex\s*,\s*displayMode\s*,\s*fontSize\s*,\s*isDark\s*\)""").containsMatchIn(html),
        )
    }

    @Test
    fun `html asset reports back through AndroidBridge onRendered`() {
        val html = katexHtml()
        assertTrue(
            "html must call AndroidBridge.onRendered(w, h, err)",
            Regex("""AndroidBridge\.onRendered\s*\(""").containsMatchIn(html),
        )
    }

    @Test
    fun `html asset loads katex and mhchem`() {
        val html = katexHtml()
        assertTrue(html.contains("katex.min.js"))
        assertTrue(html.contains("mhchem.min.js"))
        assertNotNull(Regex("""katex\.render\s*\(""").find(html))
    }
}
