package com.openminis.app.ui.markdown

/**
 * 轻量 markdown 解析器：原文 → 块节点表（4.0 闭源收尾轮整体重组；Block
 * 形状、全部正则、数学抽取语义与 MdParser 日志行为契约冻结面）。
 * 支持：标题、围栏代码块、引用块、无序/有序/任务列表、分隔线、表格、
 * 段落。行内解析另行处理。
 *
 * 本版组织方式（与前身直译版刻意不同）：围栏状态抽成 [FenceState] 小状态
 * 机，主抽取循环与代码掩码预计算共用一套开/闭栏判定；闭定界符搜索统一改
 * startsWith 探针式；数学还原趟由「when 分派 + 段落扫描缓冲」重组为
 * 「直通分支前置 + 段落重建器」。
 */
object MarkdownParser {

    sealed class Block {
        data class Heading(val level: Int, val content: String) : Block()
        data class Paragraph(val content: String) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Blockquote(val blocks: List<Block>) : Block()
        data class BulletList(val items: List<ListItem>) : Block()
        data class NumberedList(val startNumber: Int, val items: List<ListItem>) : Block()
        data class Table(val headers: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) : Block()
        data object ThematicBreak : Block()

        /**
         * 展示模式 LaTeX。来源有二：从 `$$ … $$` / `\[ … \]` 抽取，或由
         * 「只含单个行内数学占位的段落」升级。mhchem（\ce{…}、\pu{…}）经
         * 内置 assets/katex/mhchem.min.js 扩展支持。
         */
        data class MathBlock(val latex: String) : Block()

        /**
         * 从 `![alt](url)` 抽出的媒体块——仅当该行只有这一个图片语法节点。
         * 按扩展名路由，对齐 iOS SelectableMarkdownView
         * （nativeImageExts / nativeVideoExts / nativeAudioExts）。
         */
        data class Image(val alt: String, val url: String) : Block()
        data class Video(val alt: String, val url: String) : Block()
        data class Audio(val alt: String, val url: String) : Block()
    }

    /**
     * 一条抽取出的数学式，以块解析前插入 markdown 流的唯一占位符标识。
     * AST 建好后按 id 回查：展示式还原成 MathBlock，行内式留在文本里由
     * 行内趟渲染。
     */
    data class MathSpan(val placeholder: String, val latex: String, val isBlock: Boolean)

    /**
     * 与 [MarkdownText] 共享的容器：行内解析器也能把占位符解析回原 LaTeX。
     * 经组合把整表 spans 随解析块一起传递。
     */
    data class ParseResult(val blocks: List<Block>, val mathSpans: List<MathSpan>)

    data class ListItem(val content: String, val checked: Boolean? = null)

    enum class Alignment { LEFT, CENTER, RIGHT }

    /** Object Replacement Character（U+FFFC）——数学占位符的哨兵字符。 */
    private const val ORC = '￼'

    private const val LOG_TAG = "MdParser"

    // ── 正则表（冻结面：口径即行为） ────────────────────────────────────
    private val THEMATIC_BREAK = Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1(\\s*\\1)*\\s*$")
    private val ATX_HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET_ITEM = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val BULLET_ITEM_PREFIX = Regex("^\\s{0,3}[-*+]\\s")
    private val BULLET_PARA_BREAK = Regex("^\\s{0,3}[-*+]\\s+")
    // 有序表标记位数钳在 2：防止「2020. 年的…」这类以年份/大数开头的段落
    // 被误判成第 2020 项（用户报告）。真实列表很少超过 99 项；CommonMark
    // 自己放到 9 位，我们刻意更紧。
    private val NUMBERED_ITEM = Regex("^\\s{0,3}(\\d{1,2})[.)\\s]\\s*(.*)$")
    private val NUMBERED_PARA_BREAK = Regex("^\\s{0,3}\\d{1,2}[.)\\s]\\s")
    private val SEPARATOR_CELL = Regex("^:?-+:?$")
    private val MATH_PLACEHOLDER_SCAN = Regex("$ORC" + "MATH(\\d+)" + "$ORC")

    private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
    private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

    /** 整行只含一个 `![alt](url)` 节点的形态。 */
    private val standaloneImageRegex = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

    /** isPlausibleDisplayBody 的两条正文形态判据（预编译，逐次调用不再重建）。 */
    private val BODY_HAS_BLANK_LINE = Regex("\\n[ \\t]*\\n")
    private val BODY_ENDS_ON_OWN_LINE = Regex("\\n[ \\t]*$")

    /** looksLikeMath 的「LaTeX 味」字形表（冻结面：口径即行为）。 */
    private val MATH_FLAVOR_CHARS = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')

    /**
     * 顶层入口：抽数学 → 对占位符替换后的文本跑标准块解析 → 跑数学还原趟
     * （单一数学段升 MathBlock）。行内占位符留在段落文本里，行内趟经
     * KaTeX 渲染。对齐 iOS MarkdownMathExtractor.extract → cmark → restore。
     */
    fun parseWithMath(markdown: String): ParseResult {
        val (stripped, spans) = extractMath(markdown)
        return ParseResult(restoreMath(parse(stripped), spans), spans)
    }

    fun parse(markdown: String): List<Block> {
        android.util.Log.d(LOG_TAG, "parse() len=${markdown.length} preview=${markdown.take(160).replace("\n", "\\n")}")
        val src = markdown.lines()
        val blocks = mutableListOf<Block>()
        var row = 0
        while (row < src.size) {
            // 消费者必须前进；防御兜底至少 +1，防死循环（理论不可达）。
            row = maxOf(consumeBlockAt(src, row, blocks), row + 1)
        }
        return blocks
    }

    /**
     * 在 [start] 处按优先级尝试各类块，产出的块追加进 [sink]，返回消费后的
     * 行游标。优先级即匹配序：围栏 → 分隔线 → 标题 → 引用 → 表格 →
     * 无序 → 有序 → 独立媒体行 → 空行 → 段落。
     */
    private fun consumeBlockAt(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val cur = lines[start]

        // 围栏代码块。
        if (cur.trimStart().startsWith("```")) {
            return consumeFence(lines, start, sink)
        }

        // 分隔线。
        if (cur.matches(THEMATIC_BREAK)) {
            sink.add(Block.ThematicBreak)
            return start + 1
        }

        // ATX 标题。
        ATX_HEADING.find(cur)?.let { hit ->
            val level = hit.groupValues[1].length
            val title = hit.groupValues[2].trimEnd().removeSuffix("#").trimEnd()
            sink.add(Block.Heading(level, title))
            return start + 1
        }

        // 引用块。
        if (isBlockquoteLine(cur)) {
            return consumeBlockquote(lines, start, sink)
        }

        // 表格（至少要表头 + 分隔行）。
        if (start + 1 < lines.size && isTableSeparator(lines[start + 1])) {
            parseTable(lines, start)?.let { (table, next) ->
                sink.add(table)
                return next
            }
        }

        // 无序列表（-、*、+）。
        BULLET_ITEM.find(cur)?.let {
            return consumeBulletList(lines, start, sink)
        }

        // 有序列表（标记位数 ≤2，见 NUMBERED_ITEM 注释）。
        if (NUMBERED_ITEM.containsMatchIn(cur)) {
            return consumeNumberedList(lines, start, sink)
        }

        // 独占一行的图片/视频/音频：
        //   ![alt](minis://workspace/clip.mp4)
        // 按扩展名路由，媒体渲染器好挑对的预览。
        standaloneImageRegex.find(cur)?.let { hit ->
            val alt = hit.groupValues[1]
            val url = hit.groupValues[2]
            val block = mediaBlockFor(alt, url)
            android.util.Log.d(LOG_TAG, "media match: alt=\"$alt\" url=$url -> ${block::class.simpleName}")
            sink.add(block)
            return start + 1
        }
        if (cur.contains("![") && cur.contains("](")) {
            android.util.Log.d(LOG_TAG, "image-like line did NOT match standalone regex: ${cur.take(160)}")
        }

        // 空行——跳过。
        if (cur.isBlank()) return start + 1

        // 段落：收集连续非空、且不像其他块开头的行。
        return consumeParagraph(lines, start, sink)
    }

    private fun consumeFence(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val fenceLine = lines[start].trimStart()
        val lang = fenceLine.removePrefix("```").trim()
        val fenceBody = mutableListOf<String>()
        var row = start + 1
        while (row < lines.size) {
            val raw = lines[row]
            val closesHere = raw.trimStart().startsWith("```") && raw.trim() == "```"
            if (closesHere) {
                row++
                break
            }
            fenceBody += raw
            row++
        }
        sink += Block.CodeBlock(lang, fenceBody.joinToString("\n"))
        return row
    }

    private fun isBlockquoteLine(line: String): Boolean {
        val bare = line.trimStart()
        return bare == ">" || bare.startsWith("> ")
    }

    private fun consumeBlockquote(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val inner = mutableListOf<String>()
        var row = start
        while (row < lines.size && isBlockquoteLine(lines[row])) {
            val bare = lines[row].trimStart()
            inner += if (bare == ">") "" else bare.removePrefix("> ")
            row++
        }
        sink += Block.Blockquote(parse(inner.joinToString("\n")))
        return row
    }

    private fun consumeBulletList(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val items = ArrayList<ListItem>()
        var row = start
        while (row < lines.size) {
            val hit = BULLET_ITEM.find(lines[row]) ?: break
            var text = hit.groupValues[1]
            row++
            // 吸收不是新条目的缩进续行到同一条目。
            while (row < lines.size && lines[row].startsWith("  ") && !BULLET_ITEM_PREFIX.matches(lines[row])) {
                text += "\n" + lines[row].trimStart()
                row++
            }
            items += parseListItem(text)
        }
        sink += Block.BulletList(items)
        return row
    }

    private fun consumeNumberedList(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val startNumber = NUMBERED_ITEM.find(lines[start])?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val items = ArrayList<ListItem>()
        var row = start
        while (row < lines.size) {
            val hit = NUMBERED_ITEM.find(lines[row]) ?: break
            items += ListItem(hit.groupValues[2])
            row++
        }
        sink += Block.NumberedList(startNumber, items)
        return row
    }

    private fun consumeParagraph(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val paraLines = mutableListOf(lines[start])
        var row = start + 1
        while (row < lines.size && !endsParagraph(lines[row])) {
            paraLines += lines[row]
            row++
        }
        sink += Block.Paragraph(paraLines.joinToString("\n"))
        return row
    }

    /** 段落收集的停止判据：空行、后续块的开头形态或媒体独行。 */
    private fun endsParagraph(candidate: String): Boolean = candidate.isBlank() ||
        candidate.trimStart().startsWith("```") ||
        candidate.trimStart().startsWith("# ") ||
        candidate.trimStart().startsWith("> ") ||
        BULLET_PARA_BREAK.containsMatchIn(candidate) ||
        // 与有序表标记口径保持同步（≤2 位数字）。
        NUMBERED_PARA_BREAK.containsMatchIn(candidate) ||
        candidate.matches(THEMATIC_BREAK) ||
        standaloneImageRegex.containsMatchIn(candidate)

    private fun parseListItem(content: String): ListItem = when {
        // 任务列表：[ ] 未勾 / [x]、[X] 已勾。
        content.startsWith("[ ] ") -> ListItem(content.substring(4), checked = false)
        content.startsWith("[x] ") || content.startsWith("[X] ") -> ListItem(content.substring(4), checked = true)
        else -> ListItem(content)
    }

    private fun isTableSeparator(line: String): Boolean {
        val bare = line.trim()
        if ('-' !in bare) return false
        return bare.split('|')
            .filter { it.isNotBlank() }
            .all { it.trim().matches(SEPARATOR_CELL) }
    }

    private fun parseTable(lines: List<String>, headAt: Int): Pair<Block.Table, Int>? {
        val headerCells = splitTableRow(lines[headAt])
        val separatorCells = splitTableRow(lines[headAt + 1])
        if (headerCells.isEmpty() || separatorCells.isEmpty()) return null

        val alignments = separatorCells.map(::alignmentOf)

        val rows = mutableListOf<List<String>>()
        var row = headAt + 2
        while (row < lines.size) {
            val line = lines[row]
            if (line.isBlank() || '|' !in line) break
            rows += splitTableRow(line)
            row++
        }
        return Block.Table(headerCells, alignments, rows) to row
    }

    private fun alignmentOf(cell: String): Alignment {
        val spec = cell.trim()
        return when {
            spec.startsWith(':') && spec.endsWith(':') -> Alignment.CENTER
            spec.endsWith(':') -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
    }

    private fun splitTableRow(line: String): List<String> {
        val bare = line.trim().removePrefix("|").removeSuffix("|")
        // [T-minis-url-fullwidth-pipe-android] 快路：只有 markdown 链接的
        // URL 里带裸 ASCII 竖线（如 `[f](minis://ns/a|b.png)）时才需要保护
        // `|` 不被切——模型（或手写行）可能在 URL 里放出未编码的 `|`，朴
        // 素 `split('|')` 会把链接腰斩。应用自己生成的 minis:// URL 已把
        // `|` 百分号编码成 %7C，这里纯属纵深防御。**不含** `](` 链接的行
        // 保持原始 `split('|')` 逐字不变——括号感知的走查否则会吞掉普通文
        // 本单元格里不平衡 `(` 之后的 `|`（如 `| a (note | b |`）。全角
        // `｜`（U+FF5C）两条路都不切——`split('|')` 只认 U+007C。
        if ("](" !in bare) {
            return bare.split('|').map(String::trim)
        }
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var depth = 0
        for (ch in bare) {
            if (ch == '(' && depth == 0) depth++
            else if (ch == ')' && depth > 0) depth--
            if (ch == '|' && depth == 0) {
                cells.add(cell.toString().trim())
                cell.clear()
            } else {
                cell.append(ch)
            }
        }
        cells.add(cell.toString().trim())
        return cells
    }

    /** 按扩展名给媒体 URL 分类。只从**最后一个路径段**取扩展名——文件名带
     *  `#` 或 `?`（字面或百分号编码）也能拿到真扩展。两种形态都处理：
     *  未编码（`foo#China.mp4`）与已编码（`foo%23China.mp4`）。 */
    private fun mediaBlockFor(alt: String, url: String): Block {
        val lastSegment = url.substringAfterLast('/')
        val decodedName = runCatching { java.net.URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
        val ext = decodedName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in nativeAudioExts -> Block.Audio(alt, url)
            in nativeVideoExts -> Block.Video(alt, url)
            else -> Block.Image(alt, url)
        }
    }

    // ─── 数学抽取 ──────────────────────────────────────────────────────────
    //
    // 逐字符走原文，吞四种数学定界符：
    //
    //   $$ … $$    展示数学
    //   \[ … \]    展示数学（LaTeX 式）
    //   $ … $      行内数学
    //   \( … \)    行内数学（LaTeX 式）
    //
    // 围栏代码块与行内代码 span 逐字跳过——里面的定界符原样存活（聊天记
    // 录常贴原始 markdown 源）。每处命中替换成形如 ￼ MATH<n> ￼ 的哨兵，
    // 占位符不可能与正常 Markdown 语法相撞（￼ = Object Replacement
    // Character，用户散文里永不出现）。逐字对齐 iOS
    // MarkdownMathExtractor.extract。

    /** 围栏状态机：主抽取循环与 [buildCodeMask] 共用同一套开/闭栏判定。 */
    private class FenceState {
        var marker = '`'
        var runLength = 0

        val open: Boolean get() = runLength > 0

        fun enter(opener: Pair<Char, Int>) {
            marker = opener.first
            runLength = opener.second
        }

        fun close() {
            runLength = 0
        }

        /** 行首同字符、跑长不小于开栏跑 → 闭栏。 */
        fun closesAt(src: String, at: Int): Boolean =
            open && atLineStart(src, at) && src[at] == marker && backtickRun(src, at) >= runLength
    }

    private fun atLineStart(src: String, idx: Int): Boolean =
        idx == 0 || src[idx - 1] == '\n' || src[idx - 1] == '\r'

    private fun extractMath(markdown: String): Pair<String, List<MathSpan>> {
        val src = markdown
        val len = src.length
        val found = mutableListOf<MathSpan>()
        val buf = StringBuilder()
        var cursor = 0
        val fence = FenceState()

        // [T-android-latex-code-mask] 一次性预计算：哪些下标落在围栏或行
        // 内代码内——闭 `$`/`$$` 永远无法跨代码边界配对（issue #117 缺陷
        // 三）。
        val codeMask = buildCodeMask(src)

        /** 命中即登记 span、占位符入 buf，并把游标推到 [advanceTo]。 */
        fun capture(latex: String, display: Boolean, advanceTo: Int) {
            found += MathSpan(makePlaceholder(found.size), latex, display)
            buf.append(found.last().placeholder)
            cursor = advanceTo
        }

        /** 各定界符探测失败时的共同回落：定界字符逐字入 buf、前进一步。 */
        fun fallback(c: Char) {
            buf.append(c)
            cursor++
        }

        while (cursor < len) {
            val cur = src[cursor]
            val peek: Char? = if (cursor + 1 < len) src[cursor + 1] else null
            when {
                // 行首探测开栏。
                !fence.open && atLineStart(src, cursor) && openFenceAt(src, cursor) != null -> {
                    fence.enter(openFenceAt(src, cursor)!!)
                    cursor = copyThroughEndOfLine(src, cursor, buf)
                }
                fence.open -> {
                    if (fence.closesAt(src, cursor)) {
                        fence.close()
                        cursor = copyThroughEndOfLine(src, cursor, buf)
                    } else {
                        fallback(cur)
                    }
                }
                // 行内代码 span——逐字拷贝，美元符号原样在内。
                cur == '`' -> cursor = copyInlineCodeRun(src, cursor, buf)
                // \$ —— 转义美元逐字保留。
                cur == '\\' && peek == '$' -> {
                    buf.append(cur).append(peek)
                    cursor += 2
                }
                // \[ … \] —— 展示数学。
                cur == '\\' && peek == '[' -> {
                    val close = findClose(src, cursor + 2, "\\]")
                    if (close == null) {
                        fallback(cur)
                    } else {
                        capture(stripBlockquoteMarkers(src.substring(cursor + 2, close)), display = true, advanceTo = close + 2)
                    }
                }
                // \( … \) —— 行内数学。
                cur == '\\' && peek == '(' -> {
                    val close = findClose(src, cursor + 2, "\\)")
                    if (close == null) {
                        fallback(cur)
                    } else {
                        capture(src.substring(cursor + 2, close), display = false, advanceTo = close + 2)
                    }
                }
                // $$ … $$ —— 展示数学。闭符必须在代码外（codeMask），且多
                // 行正文仍得像条公式——否则一个未闭合的 `$$` 会吞掉整段散
                // 文。[T-android-latex-code-mask]
                cur == '$' && peek == '$' -> {
                    val close = findDoubleDollar(src, cursor + 2, codeMask)
                    if (close != null && isPlausibleDisplayBody(src.substring(cursor + 2, close))) {
                        capture(stripBlockquoteMarkers(src.substring(cursor + 2, close)), display = true, advanceTo = close + 2)
                    } else {
                        fallback(cur)
                    }
                }
                // $ … $ —— 行内数学（带货币跳过启发）。
                cur == '$' && peek != null && peek != '$' && peek != ' ' -> {
                    val latex = findSingleDollar(src, cursor + 1, codeMask)
                        ?.let { close -> src.substring(cursor + 1, close) }
                        ?.takeIf(::looksLikeMath)
                    if (latex == null) {
                        fallback(cur)
                    } else {
                        // 前进越过闭合 $：起点 + latex + 两个定界符。
                        capture(latex, display = false, advanceTo = cursor + 2 + latex.length)
                    }
                }
                else -> fallback(cur)
            }
        }

        return buf.toString() to found
    }

    private fun makePlaceholder(idx: Int): String = "${ORC}MATH$idx$ORC"

    /** 行首的 ```` ``` ````/`~~~` 开栏探测：返回 (栏字符, 栏长) 或 null。 */
    private fun openFenceAt(src: String, at: Int): Pair<Char, Int>? {
        val c = src[at]
        if (c != '`' && c != '~') return null
        val run = backtickRun(src, at)
        return if (run >= 3) c to run else null
    }

    private fun backtickRun(src: String, at: Int): Int {
        var len = 0
        val c = src[at]
        while (at + len < src.length && src[at + len] == c) len++
        return len
    }

    /** 把 [from] 起到行尾（含换行）拷进 [sink]，返回新游标。 */
    private fun copyThroughEndOfLine(src: String, from: Int, sink: StringBuilder): Int {
        var i = from
        while (i < src.length && src[i] != '\n') {
            sink.append(src[i]); i++
        }
        if (i < src.length) {
            sink.append(src[i]); i++
        }
        return i
    }

    /** 拷贝一个行内代码跑段（含定界反引号）；返回新游标。 */
    private fun copyInlineCodeRun(src: String, from: Int, sink: StringBuilder): Int {
        val n = src.length
        val run = backtickRun(src, from)
        val afterRun = from + run
        var k = afterRun
        while (k <= n - run) {
            var match = 0
            while (k + match < n && src[k + match] == '`') match++
            if (match == run) {
                for (idx in from until k + match) sink.append(src[idx])
                return k + match
            }
            k += if (match > 0) match else 1
        }
        // 无配对闭跑：把开跑逐字倒出来。
        for (idx in from until afterRun) sink.append(src[idx])
        return afterRun
    }

    /**
     * 从多行数学正文里剥引用标记。数学在块解析**之前**抽取，写在引用块里
     * 的展示式会把续行的 `> ` 标记拖进 LaTeX：
     *
     *     > $$
     *     > E = mc^2
     *     > $$
     *
     * 曾产出 `"\n> E = mc^2\n> "` 并渲染出字面 `>`。只有当首行之后**每一
     * 条**非空行都带 `^ {0,3}> ?` 标记才剥——那证明这段真在引用里，同时保
     * 护行首以 `>` 开头的合法公式内容（比较式、矩阵行）。对齐 iOS
     * MarkdownMathExtractor.stripBlockquoteMarkers。
     */
    private fun stripBlockquoteMarkers(latex: String): String {
        val lines = latex.split("\n")
        if (lines.size == 1) return latex

        // 开头的 `$$` / `\[` 已被调用方吃掉：首段是标记行的尾巴、自身不带
        // 标记——只校验并剥离第 2…n 行。
        val unquoted = ArrayList<String>(lines.size)
        var anyMarker = false
        for ((idx, raw) in lines.withIndex()) {
            if (idx == 0) {
                unquoted += raw
                continue
            }
            val body = markerStripped(raw) ?: return latex
            if (body.length < raw.length) anyMarker = true
            unquoted += body
        }
        if (!anyMarker) return latex
        return unquoted.joinToString("\n")
    }

    /**
     * 剥掉单行的引用标记，返回剩余正文。纯空白行原样返回（中性）；素行
     * （剥不到标记）返回 null——那不是引用内容。
     */
    private fun markerStripped(raw: String): String? {
        var at = 0
        while (at < raw.length && raw[at] == ' ' && at < 3) at++
        if (at == raw.length) return raw
        if (raw[at] != '>') return null
        var rest = raw.substring(at + 1)
        if (rest.startsWith(" ")) rest = rest.drop(1)
        return rest
    }

    /** 闭定界符探针：从 [from] 起逐位试 [close] 前缀，命中返回下标。 */
    private fun findClose(src: String, from: Int, close: String): Int? {
        var probe = from
        while (probe <= src.length - close.length) {
            if (src.startsWith(close, probe)) return probe
            probe++
        }
        return null
    }

    /**
     * [T-android-latex-code-mask]（issue #117 缺陷三，iOS bce7e2ed）
     *
     * 主 [extractMath] 循环决定公式可以在哪里**开**时会跳过围栏与行内代
     * 码，但闭定界符的搜索是盲扫。于是散文里一个裸 `$$`（模型忘了闭合，
     * 或文本只是在*讲解* LaTeX）与后面 ``` 围栏里的一个 `$$` 配了对，把两
     * 者之间的每一段都吞了进去——连同围栏自己的开行。渲染器接着把落单的
     * 闭 ``` 当成**新**围栏，把余文全变成代码块：即「一整节凭空消失」的
     * 症状。
     *
     * 这里在每次解析前一次性预计算哪些下标落在围栏或行内代码里，规则镜像
     * 主循环（同一 [FenceState]）。掩码内的闭符一律拒绝。
     */
    private fun buildCodeMask(src: String): BooleanArray {
        val mask = BooleanArray(src.length)
        var cursor = 0
        val fence = FenceState()

        /** 把 [from] 到行尾（含换行）标进掩码，返回新游标。 */
        fun maskRestOfLine(from: Int): Int {
            var k = from
            while (k < src.length && src[k] != '\n') {
                mask[k] = true; k++
            }
            if (k < src.length) {
                mask[k] = true; k++
            }
            return k
        }

        while (cursor < src.length) {
            val opener = if (!fence.open && atLineStart(src, cursor)) openFenceAt(src, cursor) else null
            when {
                opener != null -> {
                    fence.enter(opener)
                    cursor = maskRestOfLine(cursor)
                }
                fence.closesAt(src, cursor) -> {
                    fence.close()
                    cursor = maskRestOfLine(cursor)
                }
                fence.open -> {
                    mask[cursor] = true; cursor++
                }
                // 行内代码 span：`…` / ``…``——与主循环逐字拷贝时同一条跑
                // 段配对规则。
                src[cursor] == '`' -> cursor = maskInlineCodeRun(src, cursor, mask)
                else -> cursor++
            }
        }
        return mask
    }

    /** 掩码一个行内代码跑段（含定界反引号，无闭跑则只掩开跑）；返回新游标。 */
    private fun maskInlineCodeRun(src: String, from: Int, mask: BooleanArray): Int {
        val n = src.length
        val run = backtickRun(src, from)
        var k = from + run
        var closedAt = -1
        while (k < n) {
            if (src[k] == '`') {
                val r2 = backtickRun(src, k)
                if (r2 == run) {
                    closedAt = k + r2
                    break
                }
                k += r2
            } else {
                k++
            }
        }
        val end = if (closedAt > 0) closedAt else from + run
        for (idx in from until end) mask[idx] = true
        return end
    }

    /**
     * [T-android-latex-code-mask] 多行 `$$` 正文还得像条公式——真没闭合
     * 的 `$$` 即便闭符在代码外也吞不了散文。单行 `$$…$$` 无条件接受，普
     * 通展示数学不受影响。对齐 iOS。
     */
    private fun isPlausibleDisplayBody(body: String): Boolean {
        if (!body.contains('\n')) return true
        // 空行 = 段落断——是散文，不是一条公式。
        if (BODY_HAS_BLANK_LINE.containsMatchIn(body)) return false
        // 惯例块形态里闭 `$$` 独占一行，即正文以换行（可带缩进）收尾——
        // 这一个信号已足够强；这里再要求 LaTeX 字形会把无字形但合法的数
        // 学（如 "$$\n1 + 2 = 3\n$$"）错贬成纯文本。
        if (BODY_ENDS_ON_OWN_LINE.containsMatchIn(body)) return true
        // 否则闭符在行中——散文里散落定界符的形态：要求一个 LaTeX 味字形。
        val texGlyph = body.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }
        return texGlyph
    }

    private fun findDoubleDollar(src: String, from: Int, codeMask: BooleanArray?): Int? {
        var probe = from
        while (probe < src.length - 1) {
            if (src[probe] == '$' && src[probe + 1] == '$' && codeMask?.get(probe) != true) {
                return probe
            }
            probe++
        }
        return null
    }

    private fun findSingleDollar(src: String, from: Int, codeMask: BooleanArray?): Int? {
        var probe = from
        while (probe < src.length) {
            val c = src[probe]
            if (c == '\\') {
                probe += 2
                continue
            }
            if (c == '\n') return null
            if (c == '$' && (probe == 0 || src[probe - 1] != ' ') && codeMask?.get(probe) != true) {
                return probe
            }
            probe++
        }
        return null
    }

    /**
     * 货币跳过启发：$5 / $10.99 该保持纯文本。`$ … $` 的内容只有在带数
     * 学味字符（`\\^_{}` / 常见大符号 Unicode）或长于 2 字符（`x+y` 过、
     * 裸数字 `5` 不过）时才算数学。对齐 iOS。
     */
    private fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty()) return false
        // T208 B 层：旧启发会拒掉单字母数学如 `$c$`、`$i$`——`length > 2`
        // 返回 false，美元包裹的字面文本就漏进了渲染输出。以下任一即算
        // 数学：
        //   - 明显的 LaTeX 字形（`\`、`^`、`_`、`{`、`}`、积分号…）
        //   - 无空白、首字符非数字的短字母数字内容（`$5` 类货币被首字数
        //     字滤掉；`$5.99` 进一步被点号规则滤掉）
        // 对齐 iOS MarkdownMathExtractor.looksLikeMath。
        if (content.any { it in MATH_FLAVOR_CHARS }) return true
        // 货币启发：`$5`、`$5.99`、`$1,000` 都是数字样——跳过。
        if (content.first().isDigit()) return false
        // 两端带空白多半是散落美元，不是数学。
        if (content[0].isWhitespace() || content.last().isWhitespace()) return false
        // 短的 ASCII 标识符样内容（`$c$`、`$x$`、`$pi$`，≤30 字符）几乎必
        // 是变量引用。
        if (content.length <= 30 && content.all(Char::isLetterOrDigit)) return true
        return content.length >= 3
    }

    /**
     * 走一遍 AST 替换数学占位符。展示式占位符把所在段落升级（或替换）为
     * [Block.MathBlock]；行内占位符留在段落文本里，行内解析器查
     * [MathSpan] 表渲染。
     */
    private fun restoreMath(blocks: List<Block>, spans: List<MathSpan>): List<Block> {
        if (spans.isEmpty()) return blocks
        val byPlaceholder = spans.associateBy { it.placeholder }
        return blocks.flatMap { restoreBlock(it, byPlaceholder) }
    }

    private fun restoreBlock(block: Block, map: Map<String, MathSpan>): List<Block> = when (block) {
        // 直通块：无需占位符改写。
        is Block.Heading, is Block.BulletList, is Block.NumberedList, is Block.Table,
        is Block.CodeBlock, is Block.MathBlock, is Block.ThematicBreak,
        is Block.Image, is Block.Video, is Block.Audio -> listOf(block)
        is Block.Blockquote -> listOf(Block.Blockquote(block.blocks.flatMap { restoreBlock(it, map) }))
        is Block.Paragraph -> restoreParagraph(block.content, map)
    }

    /**
     * 拆分含数学占位符的段落。每个展示式占位符截断段落、独立产出一个
     * [Block.MathBlock]；行内占位符嵌在原处以便行内趟经 KaTeX 渲染。整段
     * 只含单个展示式占位符时收拢成一个 MathBlock，不留残段。
     */
    private fun restoreParagraph(content: String, map: Map<String, MathSpan>): List<Block> {
        if (!content.contains(ORC)) return listOf(Block.Paragraph(content))

        val pieces = mutableListOf<Block>()
        val carry = StringBuilder()
        var copiedUpTo = 0
        for (hit in MATH_PLACEHOLDER_SCAN.findAll(content)) {
            val span = map[hit.value] ?: continue
            // 占位符之前的文本先接上（start==end 时 append 为空操作）。
            carry.append(content, copiedUpTo, hit.range.first)
            if (span.isBlock) {
                // 已累积文本冲成段落，再产数学块。
                carry.toString().trimEnd().takeIf { it.isNotBlank() }?.let { pieces += Block.Paragraph(it) }
                carry.setLength(0)
                pieces += Block.MathBlock(span.latex)
            } else {
                // 行内占位符留流——行内趟渲染。
                carry.append(hit.value)
            }
            copiedUpTo = hit.range.last + 1
        }
        carry.append(content, copiedUpTo, content.length)
        carry.toString().trimEnd().takeIf { it.isNotBlank() }?.let { pieces += Block.Paragraph(it) }
        // 空段落（只剩空白）——整体丢弃。
        return pieces.ifEmpty { listOf(Block.Paragraph(content)) }
    }
}
