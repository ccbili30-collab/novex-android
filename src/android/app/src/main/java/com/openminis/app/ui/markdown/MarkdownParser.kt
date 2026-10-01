package com.openminis.app.ui.markdown

/**
 * 轻量 markdown 解析器：原文 → 块节点表（血统清剿 P3.7 就地真重写；
 * Block 形状、全部正则、数学抽取语义与 MdParser 日志行为契约冻结面）。
 * 支持：标题、围栏代码块、引用块、无序/有序/任务列表、分隔线、表格、
 * 段落。行内解析另行处理。
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

    /**
     * 顶层入口：抽数学 → 对占位符替换后的文本跑标准块解析 → 跑数学还原趟
     * （单一数学段升 MathBlock）。行内占位符留在段落文本里，行内趟经
     * KaTeX 渲染。对齐 iOS MarkdownMathExtractor.extract → cmark → restore。
     */
    fun parseWithMath(markdown: String): ParseResult {
        val (cleaned, spans) = extractMath(markdown)
        val rawBlocks = parse(cleaned)
        val restored = restoreMath(rawBlocks, spans)
        return ParseResult(restored, spans)
    }

    fun parse(markdown: String): List<Block> {
        android.util.Log.d(LOG_TAG, "parse() len=${markdown.length} preview=${markdown.take(160).replace("\n", "\\n")}")
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        var cursor = 0
        while (cursor < lines.size) {
            val consumed = consumeBlockAt(lines, cursor, blocks)
            if (consumed > cursor) {
                cursor = consumed
            } else {
                // 兜底前进，防死循环（理论不可达：空行分支必 +1）。
                cursor++
            }
        }
        return blocks
    }

    /**
     * 在 [start] 处按优先级尝试各类块，产出的块追加进 [sink]，返回消费后的
     * 行游标。优先级即匹配序：围栏 → 分隔线 → 标题 → 引用 → 表格 →
     * 无序 → 有序 → 独立媒体行 → 空行 → 段落。
     */
    private fun consumeBlockAt(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val line = lines[start]

        // 围栏代码块。
        if (line.trimStart().startsWith("```")) {
            return consumeFence(lines, start, sink)
        }

        // 分隔线。
        if (line.matches(THEMATIC_BREAK)) {
            sink.add(Block.ThematicBreak)
            return start + 1
        }

        // ATX 标题。
        ATX_HEADING.find(line)?.let { hit ->
            val level = hit.groupValues[1].length
            val title = hit.groupValues[2].trimEnd().removeSuffix("#").trimEnd()
            sink.add(Block.Heading(level, title))
            return start + 1
        }

        // 引用块。
        if (isBlockquoteLine(line)) {
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
        BULLET_ITEM.find(line)?.let {
            return consumeBulletList(lines, start, sink)
        }

        // 有序列表（标记位数 ≤2，见 NUMBERED_ITEM 注释）。
        NUMBERED_ITEM.find(line)?.let { hit ->
            val startNumber = hit.groupValues[1].toIntOrNull() ?: 1
            val items = mutableListOf<ListItem>()
            var i = start
            while (i < lines.size) {
                val nm = NUMBERED_ITEM.find(lines[i]) ?: break
                items.add(ListItem(nm.groupValues[2]))
                i++
            }
            sink.add(Block.NumberedList(startNumber, items))
            return i
        }

        // 独占一行的图片/视频/音频：
        //   ![alt](minis://workspace/clip.mp4)
        // 按扩展名路由，媒体渲染器好挑对的预览。
        standaloneImageRegex.find(line)?.let { hit ->
            val alt = hit.groupValues[1]
            val url = hit.groupValues[2]
            val block = mediaBlockFor(alt, url)
            android.util.Log.d(LOG_TAG, "media match: alt=\"$alt\" url=$url -> ${block::class.simpleName}")
            sink.add(block)
            return start + 1
        }
        if (line.contains("![") && line.contains("](")) {
            android.util.Log.d(LOG_TAG, "image-like line did NOT match standalone regex: ${line.take(160)}")
        }

        // 空行——跳过。
        if (line.isBlank()) return start + 1

        // 段落：收集连续非空、且不像其他块开头的行。
        return consumeParagraph(lines, start, sink)
    }

    private fun consumeFence(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val opener = lines[start].trimStart()
        val language = opener.removePrefix("```").trim()
        val body = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size) {
            val inner = lines[i]
            if (inner.trimStart().startsWith("```") && inner.trim() == "```") {
                i++
                break
            }
            body.add(inner)
            i++
        }
        sink.add(Block.CodeBlock(language, body.joinToString("\n")))
        return i
    }

    private fun isBlockquoteLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("> ") || t == ">"
    }

    private fun consumeBlockquote(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val quoted = mutableListOf<String>()
        var i = start
        while (i < lines.size && isBlockquoteLine(lines[i])) {
            val stripped = lines[i].trimStart()
            quoted.add(if (stripped == ">") "" else stripped.removePrefix("> "))
            i++
        }
        sink.add(Block.Blockquote(parse(quoted.joinToString("\n"))))
        return i
    }

    private fun consumeBulletList(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val items = mutableListOf<ListItem>()
        var i = start
        while (i < lines.size) {
            val hit = BULLET_ITEM.find(lines[i]) ?: break
            items.add(parseListItem(hit.groupValues[1]))
            i++
            // 收集缩进续行。
            while (i < lines.size && lines[i].startsWith("  ") && !BULLET_ITEM_PREFIX.matches(lines[i])) {
                items[items.lastIndex] = items.last().copy(
                    content = items.last().content + "\n" + lines[i].trimStart(),
                )
                i++
            }
        }
        sink.add(Block.BulletList(items))
        return i
    }

    private fun consumeParagraph(lines: List<String>, start: Int, sink: MutableList<Block>): Int {
        val paraLines = mutableListOf(lines[start])
        var i = start + 1
        while (i < lines.size) {
            val next = lines[i]
            val breaksParagraph = next.isBlank() ||
                next.trimStart().startsWith("```") ||
                next.trimStart().startsWith("# ") ||
                next.trimStart().startsWith("> ") ||
                BULLET_PARA_BREAK.containsMatchIn(next) ||
                // 与有序表标记口径保持同步（≤2 位数字）。
                NUMBERED_PARA_BREAK.containsMatchIn(next) ||
                next.matches(THEMATIC_BREAK) ||
                standaloneImageRegex.containsMatchIn(next)
            if (breaksParagraph) break
            paraLines.add(next)
            i++
        }
        sink.add(Block.Paragraph(paraLines.joinToString("\n")))
        return i
    }

    private fun parseListItem(content: String): ListItem =
        when {
            // 任务列表：[x] 或 [ ]。
            content.startsWith("[x] ") || content.startsWith("[X] ") -> ListItem(content.substring(4), checked = true)
            content.startsWith("[ ] ") -> ListItem(content.substring(4), checked = false)
            else -> ListItem(content)
        }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        return trimmed.split('|')
            .filter { it.isNotBlank() }
            .all { it.trim().matches(SEPARATOR_CELL) }
    }

    private fun parseTable(lines: List<String>, startIdx: Int): Pair<Block.Table, Int>? {
        val headers = splitTableRow(lines[startIdx])
        val separatorCells = splitTableRow(lines[startIdx + 1])
        if (headers.isEmpty() || separatorCells.isEmpty()) return null

        val alignments = separatorCells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(':') && trimmed.endsWith(':') -> Alignment.CENTER
                trimmed.endsWith(':') -> Alignment.RIGHT
                else -> Alignment.LEFT
            }
        }

        val rows = mutableListOf<List<String>>()
        var i = startIdx + 2
        while (i < lines.size) {
            val row = lines[i]
            if (row.isBlank() || !row.contains('|')) break
            rows.add(splitTableRow(row))
            i++
        }
        return Block.Table(headers, alignments, rows) to i
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        // [T-minis-url-fullwidth-pipe-android] 快路：只有 markdown 链接的
        // URL 里带裸 ASCII 竖线（如 `[f](minis://ns/a|b.png)）时才需要保护
        // `|` 不被切——模型（或手写行）可能在 URL 里放出未编码的 `|`，朴
        // 素 `split('|')` 会把链接腰斩。应用自己生成的 minis:// URL 已把
        // `|` 百分号编码成 %7C，这里纯属纵深防御。**不含** `](` 链接的行
        // 保持原始 `split('|')` 逐字不变——括号感知的走查否则会吞掉普通文
        // 本单元格里不平衡 `(` 之后的 `|`（如 `| a (note | b |`）。全角
        // `｜`（U+FF5C）两条路都不切——`split('|')` 只认 U+007C。
        if (!trimmed.contains("](")) {
            return trimmed.split('|').map { it.trim() }
        }
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var parenDepth = 0
        for (ch in trimmed) {
            if (ch == '(' && parenDepth == 0) parenDepth++
            else if (ch == ')' && parenDepth > 0) parenDepth--
            if (ch == '|' && parenDepth == 0) {
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
        val decoded = runCatching { java.net.URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in nativeVideoExts -> Block.Video(alt, url)
            in nativeAudioExts -> Block.Audio(alt, url)
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

    private fun extractMath(markdown: String): Pair<String, List<MathSpan>> {
        val spans = mutableListOf<MathSpan>()
        val out = StringBuilder()
        val chars = markdown
        val n = chars.length
        var i = 0

        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        // [T-android-latex-code-mask] 一次性预计算：哪些下标落在围栏或行
        // 内代码内——闭 `$`/`$$` 永远无法跨代码边界配对（issue #117 缺陷
        // 三）。
        val codeMask = buildCodeMask(chars)

        fun atLineStart(idx: Int): Boolean = idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        /** 命中即登记一个 span 并把占位符写给 out；未命中返回 false。 */
        fun capture(latex: String, display: Boolean, advanceTo: Int): Boolean {
            spans.add(MathSpan(makePlaceholder(spans.size), latex, display))
            out.append(spans.last().placeholder)
            i = advanceTo
            return true
        }

        while (i < n) {
            // 行首探测开栏。
            if (!inFence && atLineStart(i) && openFenceAt(chars, i) != null) {
                val (fc, fl) = openFenceAt(chars, i)!!
                inFence = true
                fenceChar = fc
                fenceLen = fl
                i = copyThroughEndOfLine(chars, i, out)
                continue
            }

            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar && backtickRun(chars, i) >= fenceLen) {
                    inFence = false
                    i = copyThroughEndOfLine(chars, i, out)
                    continue
                }
                out.append(chars[i]); i++
                continue
            }

            // 行内代码 span——逐字拷贝，美元符号原样在内。
            if (chars[i] == '`') {
                i = copyInlineCodeRun(chars, i, out)
                continue
            }

            // \$ —— 转义美元逐字保留。
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '$') {
                out.append(chars[i]); out.append(chars[i + 1])
                i += 2
                continue
            }

            // \[ … \] —— 展示数学。
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '[') {
                val close = findClose(chars, i + 2, "\\]")
                if (close != null &&
                    capture(stripBlockquoteMarkers(chars.substring(i + 2, close)), display = true, advanceTo = close + 2)
                ) continue
            }

            // \( … \) —— 行内数学。
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '(') {
                val close = findClose(chars, i + 2, "\\)")
                if (close != null &&
                    capture(chars.substring(i + 2, close), display = false, advanceTo = close + 2)
                ) continue
            }

            // $$ … $$ —— 展示数学。
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] == '$') {
                val close = findDoubleDollar(chars, i + 2, codeMask)
                // [T-android-latex-code-mask] 闭符必须在代码外（上面的
                // codeMask），且多行正文仍得像条公式——否则一个未闭合的
                // `$$` 会吞掉整段散文。
                if (close != null && isPlausibleDisplayBody(chars.substring(i + 2, close))) {
                    if (capture(stripBlockquoteMarkers(chars.substring(i + 2, close)), display = true, advanceTo = close + 2)) continue
                }
            }

            // $ … $ —— 行内数学（带货币跳过启发）。
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] != '$' && chars[i + 1] != ' ') {
                val close = findSingleDollar(chars, i + 1, codeMask)
                if (close != null) {
                    val latex = chars.substring(i + 1, close)
                    if (looksLikeMath(latex)) {
                        if (capture(latex, display = false, advanceTo = close + 1)) continue
                    }
                }
            }

            out.append(chars[i])
            i++
        }

        return out.toString() to spans
    }

    private fun makePlaceholder(idx: Int): String = "${ORC}MATH$idx$ORC"

    /** 行首的 ```` ``` ````/`~~~` 开栏探测：返回 (栏字符, 栏长) 或 null。 */
    private fun openFenceAt(chars: String, at: Int): Pair<Char, Int>? {
        val c = chars[at]
        if (c != '`' && c != '~') return null
        val run = backtickRun(chars, at)
        return if (run >= 3) c to run else null
    }

    private fun backtickRun(chars: String, at: Int): Int {
        var len = 0
        val c = chars[at]
        while (at + len < chars.length && chars[at + len] == c) len++
        return len
    }

    /** 把 [from] 起到行尾（含换行）拷进 [sink]，返回新游标。 */
    private fun copyThroughEndOfLine(chars: String, from: Int, sink: StringBuilder): Int {
        var i = from
        while (i < chars.length && chars[i] != '\n') {
            sink.append(chars[i]); i++
        }
        if (i < chars.length) {
            sink.append(chars[i]); i++
        }
        return i
    }

    /** 拷贝一个行内代码跑段（含定界反引号）；返回新游标。 */
    private fun copyInlineCodeRun(chars: String, from: Int, sink: StringBuilder): Int {
        val n = chars.length
        val run = backtickRun(chars, from)
        val afterRun = from + run
        var k = afterRun
        while (k <= n - run) {
            var match = 0
            while (k + match < n && chars[k + match] == '`') match++
            if (match == run) {
                for (idx in from until k + match) sink.append(chars[idx])
                return k + match
            }
            k += if (match > 0) match else 1
        }
        // 无配对闭跑：把开跑逐字倒出来。
        for (idx in from until afterRun) sink.append(chars[idx])
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
        if (!latex.contains('\n')) return latex

        // 开头的 `$$` / `\[` 已被调用方吃掉：首段是标记行的尾巴、自身不带
        // 标记——只校验并剥离第 2…n 行。
        val lines = latex.split("\n").toMutableList()
        var sawMarker = false
        for (idx in 1 until lines.size) {
            val line = lines[idx]
            var cursor = 0
            while (cursor < line.length && line[cursor] == ' ' && cursor < 3) cursor++
            if (cursor >= line.length) continue // 空行：中性
            if (line[cursor] != '>') return latex // 有一条素行——不是引用
            sawMarker = true
            cursor++
            if (cursor < line.length && line[cursor] == ' ') cursor++
            lines[idx] = line.substring(cursor)
        }
        if (!sawMarker) return latex
        return lines.joinToString("\n")
    }

    private fun findClose(chars: String, from: Int, close: String): Int? {
        val n = chars.length
        var i = from
        while (i <= n - close.length) {
            var match = true
            for (j in close.indices) {
                if (chars[i + j] != close[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
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
     * 主循环。掩码内的闭符一律拒绝。
     */
    private fun buildCodeMask(chars: String): BooleanArray {
        val n = chars.length
        val mask = BooleanArray(n)
        var i = 0
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        fun atLineStart(idx: Int): Boolean =
            idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) {
                        fl++; j++
                    }
                    if (fl >= 3) {
                        inFence = true; fenceChar = c; fenceLen = fl
                        while (i < n && chars[i] != '\n') {
                            mask[i] = true; i++
                        }
                        if (i < n) {
                            mask[i] = true; i++
                        }
                        continue
                    }
                }
            }
            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) {
                        fl++; j++
                    }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') {
                            mask[i] = true; i++
                        }
                        if (i < n) {
                            mask[i] = true; i++
                        }
                        continue
                    }
                }
                mask[i] = true; i++
                continue
            }
            // 行内代码 span：`…` / ``…``——与主循环逐字拷贝时同一条跑段配
            // 对规则。
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') {
                    run++; j++
                }
                var k = j
                var closed = -1
                while (k < n) {
                    if (chars[k] == '`') {
                        var r2 = 0
                        var m = k
                        while (m < n && chars[m] == '`') {
                            r2++; m++
                        }
                        if (r2 == run) {
                            closed = m; break
                        }
                        k = m
                    } else k++
                }
                val end = if (closed > 0) closed else j
                for (idx in i until end) mask[idx] = true
                i = end
                continue
            }
            i++
        }
        return mask
    }

    /**
     * [T-android-latex-code-mask] 多行 `$$` 正文还得像条公式——真没闭合
     * 的 `$$` 即便闭符在代码外也吞不了散文。单行 `$$…$$` 无条件接受，普
     * 通展示数学不受影响。对齐 iOS。
     */
    private fun isPlausibleDisplayBody(body: String): Boolean {
        if (!body.contains('\n')) return true
        // 空行 = 段落断——是散文，不是一条公式。
        if (Regex("\\n[ \\t]*\\n").containsMatchIn(body)) return false
        // 惯例块形态里闭 `$$` 独占一行，即正文以换行（可带缩进）收尾——
        // 这一个信号已足够强；这里再要求 LaTeX 字形会把无字形但合法的数
        // 学（如 "$$\n1 + 2 = 3\n$$"）错贬成纯文本。
        if (Regex("\\n[ \\t]*$").containsMatchIn(body)) return true
        // 否则闭符在行中——散文里散落定界符的形态：要求一个 LaTeX 味字形。
        return body.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }
    }

    private fun findDoubleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n - 1) {
            if (chars[i] == '$' && chars[i + 1] == '$' &&
                (codeMask == null || !codeMask[i])
            ) return i
            i++
        }
        return null
    }

    private fun findSingleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n) {
            if (chars[i] == '\\' && i + 1 < n) {
                i += 2; continue
            }
            if (chars[i] == '$' && (i == 0 || chars[i - 1] != ' ') &&
                (codeMask == null || !codeMask[i])
            ) return i
            if (chars[i] == '\n') return null
            i++
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
        val mathChars = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')
        for (c in content) {
            if (c in mathChars) return true
        }
        // 货币启发：`$5`、`$5.99`、`$1,000` 都是数字样——跳过。
        if (content[0].isDigit()) return false
        // 两端带空白多半是散落美元，不是数学。
        if (content.first().isWhitespace() || content.last().isWhitespace()) return false
        // 短的 ASCII 标识符样内容（`$c$`、`$x$`、`$pi$`，≤30 字符）几乎必
        // 是变量引用。
        if (content.length <= 30 && content.all { it.isLetterOrDigit() }) return true
        return content.length > 2
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

    private fun restoreBlock(block: Block, map: Map<String, MathSpan>): List<Block> =
        when (block) {
            is Block.Paragraph -> restoreParagraph(block.content, map)
            is Block.Heading -> listOf(block) // 标题保留行内占位符
            is Block.Blockquote -> listOf(Block.Blockquote(block.blocks.flatMap { restoreBlock(it, map) }))
            is Block.BulletList,
            is Block.NumberedList,
            is Block.Table,
            is Block.CodeBlock,
            is Block.MathBlock,
            is Block.ThematicBreak,
            is Block.Image,
            is Block.Video,
            is Block.Audio -> listOf(block)
        }

    /**
     * 拆分含数学占位符的段落。每个展示式占位符截断段落、独立产出一个
     * [Block.MathBlock]；行内占位符嵌在原处以便行内趟经 KaTeX 渲染。整段
     * 只含单个展示式占位符时收拢成一个 MathBlock，不留残段。
     */
    private fun restoreParagraph(content: String, map: Map<String, MathSpan>): List<Block> {
        if (!content.contains(ORC)) return listOf(Block.Paragraph(content))

        val out = mutableListOf<Block>()
        val buf = StringBuilder()
        var lastEnd = 0
        for (hit in MATH_PLACEHOLDER_SCAN.findAll(content)) {
            val span = map[hit.value] ?: continue
            // 占位符之前的文本先接上。
            if (hit.range.first > lastEnd) {
                buf.append(content, lastEnd, hit.range.first)
            }
            if (span.isBlock) {
                // 已累积文本冲成段落，再产数学块。
                if (buf.isNotBlank()) {
                    out.add(Block.Paragraph(buf.toString().trimEnd()))
                }
                buf.clear()
                out.add(Block.MathBlock(span.latex))
            } else {
                // 行内占位符留流——行内趟渲染。
                buf.append(hit.value)
            }
            lastEnd = hit.range.last + 1
        }
        if (lastEnd < content.length) buf.append(content, lastEnd, content.length)
        if (buf.isNotBlank()) out.add(Block.Paragraph(buf.toString().trimEnd()))
        // 空段落（只剩空白）——整体丢弃。
        return if (out.isEmpty()) listOf(Block.Paragraph(content)) else out
    }
}
