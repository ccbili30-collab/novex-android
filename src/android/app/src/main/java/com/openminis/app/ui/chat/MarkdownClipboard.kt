package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板三味助手，镜像 iOS 聊天消息上下文菜单动作
 * （`SelectableMarkdownTextView` → `copyMarkdown` / `copyRichText`）
 * （血统清剿 P3.7 就地真重写；三个拷贝入口、行级/行内剥离次序与 HTML
 * 形态为行为冻结面。原文件的行内码占位符写作裸 NUL 字节——上游导入损
 * 坏，本轮改用 charArrayOf 显式构造，行为逐字节等价、源码洁净）。
 *
 * iOS 以 RTF 导出富文本（`UIPasteboard.setData(rtfData, forPasteboardType:
 * "public.rtf")`）。Android 粘贴板的等价物是 [ClipData.newHtmlText]——
 * 明文与 HTML 双载荷，Notes、Gmail、Docs 在「带格式粘贴」时都吃 HTML。
 * 我们在本地把 markdown 渲成一个轻量 HTML 子集，不引解析器依赖。
 *
 * 三种口味：
 *   - [copyPlain]    — 剥掉语法、可读的纯文本（默认复制）
 *   - [copyMarkdown] — 原始 markdown 源逐字
 *   - [copyRichText] — 明文+HTML 双载荷，供带格式粘贴
 */
object MarkdownClipboard {

    // 行级形态正则（口径冻结）。
    private val FENCE_LINE = Regex("^(```|~~~)(.*)$")
    private val HEADING_LINE = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET_LINE = Regex("^([-*+])\\s+(.*)$")
    private val ORDERED_LINE = Regex("^(\\d+)\\.\\s+(.*)$")
    private val RULE_LINE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val TABLE_SEPARATOR_LINE = Regex("^\\|?\\s*:?-{3,}.*$")

    // 行内形态正则（口径冻结；替换顺序即行为）。
    private val IMAGE_SPAN = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val LINK_SPAN = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val CODE_SPAN = Regex("`([^`]+)`")
    private val BOLD_ITALIC_STAR = Regex("\\*\\*\\*(.+?)\\*\\*\\*")
    private val BOLD_ITALIC_UNDER = Regex("___(.+?)___")
    private val BOLD_STAR = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_UNDER = Regex("__(.+?)__")
    private val ITALIC_STAR = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+)\\*")
    private val ITALIC_UNDER = Regex("(?<!_)_(?!_)([^_\\n]+)_")
    private val STRIKE = Regex("~~(.+?)~~")
    private val IMAGE_SPAN_HTML = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
    private val LINK_SPAN_HTML = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val STRIKE_HTML = Regex("~~(.+?)~~")

    /** 行内代码占位标记：NUL 包夹的 CODE，正文明文不可能出现。 */
    private val CODE_MARK: String = String(charArrayOf(0.toChar(), 'C', 'O', 'D', 'E', 0.toChar()))

    fun copyPlain(context: Context, markdown: String, label: String = "Message") {
        val plain = markdownToPlainText(markdown)
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, plain))
    }

    fun copyMarkdown(context: Context, markdown: String, label: String = "Markdown") {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, markdown))
    }

    fun copyRichText(context: Context, markdown: String, label: String = "Rich Text") {
        val plain = markdownToPlainText(markdown)
        val html = markdownToHtml(markdown)
        clipboard(context).setPrimaryClip(ClipData.newHtmlText(label, plain, html))
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * 剥掉 markdown 语法、保住可见的阅读顺序。目标：
     *   - 强调/加粗/删除线标记剥掉、内容保留
     *   - 行内码 / 围栏码解开包装（内容逐字保留）
     *   - 链接 `[text](url)` → `text`（丢 URL，对齐 iOS Copy 行为）
     *   - 图片 `![alt](url)` → `alt`
     *   - 标题 `#`、引用 `>`、无序 `- * +` 与有序 `1.` 剥掉
     *   - 表格压平为 ` | ` 分隔行（对不渲 markdown 的粘贴目标够用）
     */
    fun markdownToPlainText(markdown: String): String {
        val out = StringBuilder()
        var inFence = false
        for (line in markdown.lines()) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> inFence = !inFence
                inFence -> out.append(line).append('\n')
                HEADING_LINE.find(trimmed) != null ->
                    out.append(stripInline(HEADING_LINE.find(trimmed)!!.groupValues[2])).append('\n')
                trimmed.startsWith(">") ->
                    out.append(stripInline(trimmed.removePrefix(">").trimStart())).append('\n')
                BULLET_LINE.find(trimmed) != null ->
                    out.append("• ").append(stripInline(BULLET_LINE.find(trimmed)!!.groupValues[2])).append('\n')
                ORDERED_LINE.find(trimmed) != null -> {
                    val hit = ORDERED_LINE.find(trimmed)!!
                    out.append(hit.groupValues[1]).append(". ")
                        .append(stripInline(hit.groupValues[2])).append('\n')
                }
                RULE_LINE.matches(trimmed) -> out.append('\n')
                // 表格分隔行（|---|---|）：整行丢弃。
                TABLE_SEPARATOR_LINE.matches(trimmed) && trimmed.contains('-') -> Unit
                else -> out.append(stripInline(line)).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * 「带格式粘贴」用的最小 markdown → HTML。覆盖聊天实际会产出的语法。
     * 不是完整 CommonMark 渲染器——靠读侧的宽容（Gmail/Notes/Docs 都能吃
     * 稀疏 HTML）。
     */
    fun markdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        sb.append("<html><body>")
        var inFence = false
        var fenceLang: String? = null
        val fenceBuf = StringBuilder()
        var openList: String? = null // "ul" | "ol"

        fun closeList() {
            openList?.let { sb.append("</").append(it).append(">") }
            openList = null
        }
        fun openListAs(tag: String) {
            if (openList != tag) {
                closeList()
                sb.append("<").append(tag).append(">")
                openList = tag
            }
        }
        fun emitFence() {
            sb.append("<pre><code")
            fenceLang?.let { sb.append(" class=\"language-").append(escapeHtml(it)).append("\"") }
            sb.append(">").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
            fenceBuf.clear()
        }

        for (line in markdown.lines()) {
            val trimmed = line.trimStart()
            val fenceHit = FENCE_LINE.find(trimmed)
            when {
                fenceHit != null && !inFence -> {
                    closeList()
                    inFence = true
                    fenceLang = fenceHit.groupValues[2].trim().ifEmpty { null }
                    fenceBuf.clear()
                }
                fenceHit != null -> {
                    inFence = false
                    emitFence()
                }
                inFence -> fenceBuf.append(line).append('\n')
                // 空行：关段落上下文。
                trimmed.isEmpty() -> closeList()
                // 标题。
                HEADING_LINE.find(trimmed) != null -> {
                    closeList()
                    val hit = HEADING_LINE.find(trimmed)!!
                    val level = hit.groupValues[1].length
                    sb.append("<h").append(level).append(">")
                        .append(inlineToHtml(hit.groupValues[2]))
                        .append("</h").append(level).append(">")
                }
                // 引用块。
                trimmed.startsWith(">") -> {
                    closeList()
                    sb.append("<blockquote>")
                        .append(inlineToHtml(trimmed.removePrefix(">").trimStart()))
                        .append("</blockquote>")
                }
                // 水平线。
                RULE_LINE.matches(trimmed) -> {
                    closeList(); sb.append("<hr/>")
                }
                // 无序列表。
                BULLET_LINE.find(trimmed) != null -> {
                    openListAs("ul")
                    sb.append("<li>").append(inlineToHtml(BULLET_LINE.find(trimmed)!!.groupValues[2])).append("</li>")
                }
                // 有序列表。
                ORDERED_LINE.find(trimmed) != null -> {
                    openListAs("ol")
                    sb.append("<li>").append(inlineToHtml(ORDERED_LINE.find(trimmed)!!.groupValues[2])).append("</li>")
                }
                // 普通段落行。
                else -> {
                    closeList()
                    sb.append("<p>").append(inlineToHtml(line)).append("</p>")
                }
            }
        }
        closeList()
        if (inFence && fenceBuf.isNotEmpty()) {
            sb.append("<pre><code>").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** 剥行内 markdown 标记，返回可读文本。 */
    private fun stripInline(s: String): String =
        IMAGE_SPAN.replace(s, "$1")
            .let { LINK_SPAN.replace(it, "$1") }
            .let { CODE_SPAN.replace(it, "$1") }
            // 粗斜/删除线——次序要紧：最长的定界符先剥。
            .let { BOLD_ITALIC_STAR.replace(it, "$1") }
            .let { BOLD_ITALIC_UNDER.replace(it, "$1") }
            .let { BOLD_STAR.replace(it, "$1") }
            .let { BOLD_UNDER.replace(it, "$1") }
            .let { ITALIC_STAR.replace(it, "$1") }
            .let { ITALIC_UNDER.replace(it, "$1") }
            .let { STRIKE.replace(it, "$1") }

    /** 行内 markdown → HTML，非 markdown 字符做转义。 */
    private fun inlineToHtml(s: String): String {
        // 先摘出代码 span，内容不被再转义/再处理。
        val stashedCodes = mutableListOf<String>()
        var work = CODE_SPAN.replace(s) { hit ->
            stashedCodes.add(hit.groupValues[1])
            "$CODE_MARK${stashedCodes.size - 1}$CODE_MARK"
        }
        work = escapeHtml(work)
        // 图片：![alt](url)
        work = IMAGE_SPAN_HTML.replace(work) { hit ->
            "<img alt=\"${hit.groupValues[1]}\" src=\"${hit.groupValues[2]}\"/>"
        }
        // 链接：[text](url)
        work = LINK_SPAN_HTML.replace(work) { hit ->
            "<a href=\"${hit.groupValues[2]}\">${hit.groupValues[1]}</a>"
        }
        // 粗斜 ***x*** / ___x___
        work = BOLD_ITALIC_STAR.replace(work, "<strong><em>$1</em></strong>")
        work = BOLD_ITALIC_UNDER.replace(work, "<strong><em>$1</em></strong>")
        // 粗 **x**
        work = BOLD_STAR.replace(work, "<strong>$1</strong>")
        work = BOLD_UNDER.replace(work, "<strong>$1</strong>")
        // 斜 *x* / _x_
        work = ITALIC_STAR.replace(work, "<em>$1</em>")
        work = ITALIC_UNDER.replace(work, "<em>$1</em>")
        // 删除线 ~~x~~
        work = STRIKE_HTML.replace(work, "<del>$1</del>")
        // 还原代码 span（内部做 HTML 转义）。
        work = Regex("$CODE_MARK(\\d+)$CODE_MARK").replace(work) { hit ->
            "<code>${escapeHtml(stashedCodes[hit.groupValues[1].toInt()])}</code>"
        }
        return work
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
