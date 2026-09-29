package novex.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * [A3b] Live-Preview Markdown 编辑（editor-v1/02）：光标所在行保留原始标记
 * 进入源码编辑态，其余行隐藏定界符并按排版渲染（标题加粗放大、列表符号化、
 * 引用条、行内粗/斜/码/链接/删除线）。
 *
 * 变换只作用于显示层：写回的永远是无损源码；光标移动重建变换，不触碰文本。
 * 删除/插入位置通过 [LiveMarkdownMapping] 的双向偏移映射保持正确。
 */
internal object LiveMarkdown {

    private data class Edit(val start: Int, val end: Int, val replacement: String)
    private data class StyleSpan(val start: Int, val end: Int, val style: SpanStyle)

    private val HEADING = Regex("^(#{1,6})\\s+")
    private val ULIST = Regex("^(\\s*)[-*+]\\s+")
    private val OLIST = Regex("^(\\s*)(\\d+)\\.\\s+")
    private val QUOTE = Regex("^>+\\s?")
    private val HRULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")
    private val BOLD = Regex("\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__")
    private val ITALIC = Regex("\\*([^*\\n]+)\\*|_([^_\\n]+)_")
    private val STRIKE = Regex("~~([^~\\n]+)~~")
    private val CODE = Regex("`([^`\\n]+)`")
    private val LINK = Regex("\\[([^]\\n]+)]\\(([^)\\n]+)\\)")

    private val markerColor = Color(0xFF8A8F94)
    private val quoteColor = Color(0xFF8A8F94)
    private val quoteBar = Color(0xFF34D399)
    private val linkColor = Color(0xFF2E9E7B)
    private val codeBg = Color(0x14000000)

    fun transformation(source: String, cursor: Int): VisualTransformation {
        if (source.isEmpty()) return VisualTransformation.None
        // 光标行：selection.min 落在 (lineStart, lineEnd] 的首行即所在行；
        // 行首边界归本行（光标停在行首编辑前缀符号也是源码态）。
        var cursorStart = 0
        var cursorEnd = source.length
        var pos = 0
        while (pos <= source.length) {
            val nl = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
            if (cursor <= nl) { cursorStart = pos; cursorEnd = nl; break }
            if (nl == source.length) { cursorStart = pos; cursorEnd = nl; break }
            pos = nl + 1
        }
        val plan = plan(source, cursorStart, cursorEnd)
        if (plan.edits.isEmpty() && plan.styles.isEmpty()) return VisualTransformation.None
        return LiveMarkdownTransformation(plan)
    }

    private class Plan(val edits: List<Edit>, val styles: List<StyleSpan>)

    private fun plan(source: String, cursorStart: Int, cursorEnd: Int): Plan {
        val edits = mutableListOf<Edit>()
        val styles = mutableListOf<StyleSpan>()
        var pos = 0
        while (pos <= source.length) {
            val nl = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
            val lineEnd = nl
            val line = source.substring(pos, lineEnd)
            val cursorLine = pos == cursorStart
            var contentStart = pos
            if (cursorLine) {
                // 源码态：标记符号淡色提示，其余原样。
                val prefix = linePrefixEnd(line)
                if (prefix > 0) styles += StyleSpan(pos, pos + prefix, SpanStyle(color = markerColor))
                inlineMarkers(source, pos + prefix, lineEnd, hide = false).forEach { (r, s) ->
                    s?.let { styles += StyleSpan(r.first, r.last + 1, it) }
                }
            } else {
                var styledLine = false
                if (line.isNotBlank() && HRULE.matches(line)) {
                    edits += Edit(pos, lineEnd, "────────")
                    styles += StyleSpan(pos, lineEnd, SpanStyle(color = markerColor))
                    styledLine = true
                } else {
                    HEADING.find(line)?.let { m ->
                        val level = m.groupValues[1].length
                        edits += Edit(pos, pos + m.value.length, "")
                        val size = when (level) {
                            1 -> 1.45.em; 2 -> 1.28.em; 3 -> 1.15.em; else -> 1.05.em
                        }
                        styles += StyleSpan(
                            pos + m.value.length, lineEnd,
                            SpanStyle(fontWeight = FontWeight.Bold, fontSize = size),
                        )
                        contentStart = pos + m.value.length
                        styledLine = true
                    }
                    if (!styledLine) {
                        OLIST.find(line)?.let { m ->
                            val markerEnd = pos + m.value.length
                            styles += StyleSpan(pos + m.groups[1]!!.value.length, markerEnd,
                                SpanStyle(color = linkColor, fontWeight = FontWeight.Medium))
                            contentStart = markerEnd
                            styledLine = true
                        }
                    }
                    if (!styledLine) {
                        ULIST.find(line)?.let { m ->
                            val indent = m.groupValues[1]
                            edits += Edit(pos, pos + m.value.length, "$indent•  ")
                            styles += StyleSpan(pos, pos + m.value.length, SpanStyle(color = markerColor))
                            contentStart = pos + m.value.length
                            styledLine = true
                        }
                    }
                    if (!styledLine) {
                        QUOTE.find(line)?.let { m ->
                            edits += Edit(pos, pos + m.value.length, "▏")
                            styles += StyleSpan(pos, pos + m.value.length, SpanStyle(color = quoteBar))
                            styles += StyleSpan(pos + m.value.length, lineEnd, SpanStyle(color = quoteColor))
                            contentStart = pos + m.value.length
                        }
                    }
                }
                inlineMarkers(source, contentStart, lineEnd, hide = true).forEach { (r, s) ->
                    if (s == null) edits += Edit(r.first, r.last + 1, "")
                    else styles += StyleSpan(r.first, r.last + 1, s)
                }
            }
            if (nl == source.length) break
            pos = nl + 1
        }
        return Plan(edits.sortedBy { it.start }, styles)
    }

    /** 行内构造扫描。hide=true 时定界符产出隐藏编辑（style=null 条目），
     * 内容产出样式条目；hide=false（光标行）只产出标记淡色样式。 */
    private fun inlineMarkers(
        source: String, start: Int, end: Int, hide: Boolean,
    ): List<Pair<IntRange, SpanStyle?>> {
        val out = mutableListOf<Pair<IntRange, SpanStyle?>>()
        if (start >= end) return out
        val text = source.substring(start, end)
        // 已认领的隐藏区间——后扫描的构造（如斜体单下划线）不得与先匹配的
        // （如粗体双下划线）重叠，否则偏移映射的编辑集失去有序不相交前提。
        val claimed = mutableListOf<IntRange>()
        fun overlaps(r: IntRange) = claimed.any { r.first <= it.last && it.first <= r.last }
        fun scan(regex: Regex, style: SpanStyle, marker: (MatchResult) -> List<IntRange>) {
            regex.findAll(text).forEach { m ->
                val absStart = start + m.range.first
                val marks = marker(m).map { (absStart + it.first)..(absStart + it.last) }
                if (marks.any(::overlaps)) return@forEach
                if (hide) marks.forEach { r -> claimed += r; out += r to null }
                // 正文范围 = group(1) 或 group(2)（双语法正则有第二个捕获组）
                val body = m.groups[1] ?: m.groups[2]
                if (body != null) {
                    out += (absStart + body.range.first)..(absStart + body.range.last) to style
                }
            }
        }
        if (hide) {
            // 隐藏定界符，正文加样式。代码最先认领（其内部不再解析），
            // 其次链接、粗体、删除线，最后单标记斜体。
            scan(CODE, SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { m ->
                val v = m.value
                listOf(0..0, (v.length - 1)..(v.length - 1))
            }
            LINK.findAll(text).forEach { m ->
                val abs = start + m.range.first
                val label = m.groups[1]!!
                val tailStart = m.range.first + label.range.last + 1
                val marks = listOf(abs..abs, (start + tailStart)..(start + m.range.last))
                if (marks.any(::overlaps)) return@forEach
                claimed += marks
                // 隐藏 "[" 与 "](url)"，只留链接文字
                out += abs..abs to null
                out += (start + tailStart)..(start + m.range.last) to null
                out += (abs + label.range.first)..(abs + label.range.last) to
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            }
            scan(BOLD, SpanStyle(fontWeight = FontWeight.Bold)) { m ->
                val v = m.value
                listOf(0..1, (v.length - 2)..(v.length - 1))
            }
            scan(STRIKE, SpanStyle(textDecoration = TextDecoration.LineThrough)) { m ->
                val v = m.value
                listOf(0..1, (v.length - 2)..(v.length - 1))
            }
            scan(ITALIC, SpanStyle(fontStyle = FontStyle.Italic)) { m ->
                val v = m.value
                listOf(0..0, (v.length - 1)..(v.length - 1))
            }
        } else {
            // 光标行：只给定界符淡色，内容不动
            listOf(BOLD, STRIKE, ITALIC, CODE).forEach { regex ->
                regex.findAll(text).forEach { m ->
                    val v = m.value
                    val w = if (regex == BOLD || regex == STRIKE) 2 else 1
                    val abs = start + m.range.first
                    out += abs..(abs + w - 1) to SpanStyle(color = markerColor)
                    out += (abs + v.length - w)..(abs + v.length - 1) to SpanStyle(color = markerColor)
                }
            }
            LINK.findAll(text).forEach { m ->
                val abs = start + m.range.first
                out += abs..abs to SpanStyle(color = markerColor)
                out += (abs + m.value.indexOf("]("))..(abs + m.value.indexOf("](") + 1) to
                    SpanStyle(color = markerColor)
                out += (start + m.range.last)..(start + m.range.last) to SpanStyle(color = markerColor)
            }
        }
        return out
    }

    private fun linePrefixEnd(line: String): Int {
        HEADING.find(line)?.let { return it.value.length }
        ULIST.find(line)?.let { return it.value.length }
        OLIST.find(line)?.let { return it.value.length }
        QUOTE.find(line)?.let { return it.value.length }
        return 0
    }

    private class LiveMarkdownTransformation(private val plan: Plan) : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText {
            val source = text.text
            val mapping = LiveMarkdownMapping(plan.edits)
            val rendered = buildAnnotatedString {
                var read = 0
                plan.edits.forEach { edit ->
                    if (edit.start > read) append(source.substring(read, edit.start))
                    append(edit.replacement)
                    read = edit.end
                }
                if (read < source.length) append(source.substring(read))
                plan.styles.forEach { span ->
                    val s = mapping.originalToTransformed(span.start)
                    val e = mapping.originalToTransformed(span.end)
                    if (e > s && s >= 0 && e <= length) addStyle(span.style, s, e)
                }
            }
            return TransformedText(rendered, mapping)
        }
    }

    /** 有序、互不重叠的替换编辑 → 双向偏移映射。 */
    private class LiveMarkdownMapping(edits: List<Edit>) : OffsetMapping {
        // transStart/transLen 预先摊平，避免每次查询重算
        private val starts = IntArray(edits.size)
        private val origEnds = IntArray(edits.size)
        private val transStarts = IntArray(edits.size)
        private val transLens = IntArray(edits.size)
        init {
            var acc = 0
            edits.forEachIndexed { i, e ->
                starts[i] = e.start
                origEnds[i] = e.end
                transStarts[i] = e.start - acc
                transLens[i] = e.replacement.length
                acc += (e.end - e.start) - e.replacement.length
            }
        }
        override fun originalToTransformed(offset: Int): Int {
            var acc = 0
            for (i in starts.indices) {
                if (offset <= starts[i]) return offset - acc
                if (offset <= origEnds[i]) return transStarts[i] + transLens[i]
                acc += (origEnds[i] - starts[i]) - transLens[i]
            }
            return offset - acc
        }
        override fun transformedToOriginal(offset: Int): Int {
            var acc = 0
            for (i in starts.indices) {
                val ts = transStarts[i]
                val te = ts + transLens[i]
                if (offset < ts) return offset + acc
                if (offset <= te) {
                    // 落在替换区：隐藏段映射回段首，替换文本按比例贴回原区间
                    if (origEnds[i] == starts[i]) return starts[i]
                    val frac = if (transLens[i] == 0) 0f else (offset - ts).toFloat() / transLens[i]
                    return starts[i] + ((origEnds[i] - starts[i]) * frac).toInt()
                }
                acc += (origEnds[i] - starts[i]) - transLens[i]
            }
            return offset + acc
        }
    }
}
