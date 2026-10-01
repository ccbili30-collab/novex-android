package com.openminis.app.diagnostics

import java.util.concurrent.atomic.AtomicReference

/**
 * [T-android-content-perf-diag] 大内容渲染性能的结构指纹（血统清剿 P3.7 就地
 * 真重写；Summary 字段与 asLogFields 输出、正则表均为冻结面）。
 *
 * 给一段消息正文算一个廉价的结构摘要（长度/行数/段落/表格/代码块/数学标记），
 * 用来把渲染卡顿定位到「哪一种内容结构」，而绝不落正文本身——零隐私泄漏、
 * 零性能负担。消费方：
 *  - ChatScreen 冷开摘要（每条大消息一行）；
 *  - LargeContentGuard（流式降级与收尾 markdown 换装两个时点）；
 *  - HangDetector（经 [currentRenderLogFields] 把正在渲染的消息指纹挂到每条
 *    JankDiag 卡顿采样上，Matcher/Pattern 栈一查日志就能对上「这条消息、这
 *    个结构」）。
 */
/** 短于此长度的消息不可能驱动渲染卡顿，跳过摘要。 */
const val CONTENT_DIAG_MIN_CHARS = 5_000

object ContentDiag {

    data class Summary(
        val chars: Int,
        val lines: Int,
        val paragraphs: Int,
        val tableCount: Int,
        val codeBlockCount: Int,
        val mathCount: Int,
    ) {
        val hasTable: Boolean get() = tableCount > 0
        val hasCodeBlock: Boolean get() = codeBlockCount > 0
        val hasMath: Boolean get() = mathCount > 0

        /** 紧凑可 grep 的 key=value 片段（不带 session/idx 前缀，格式冻结）。 */
        fun asLogFields(): String =
            "chars=$chars lines=$lines paragraphs=$paragraphs " +
                "hasTable=$hasTable tableCount=$tableCount " +
                "hasCodeBlock=$hasCodeBlock codeBlockCount=$codeBlockCount " +
                "hasMath=$hasMath mathCount=$mathCount"
    }

    // -- 结构标记正则（表意口径冻结） --------------------------------------
    // 围栏代码块开/闭行（``` 或 ~~~），行首。
    private val FENCE_MARKER = Regex("""(?m)^\s{0,3}(```|~~~)""")
    // 表格分隔行：| --- | :---: | --- |（把竖线行升格为真表格的承重行）。
    private val TABLE_SEPARATOR = Regex("""(?m)^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    // 展示数学：$$…$$ 或 \[…\]（按块计，$$ 计对数）。
    private val DISPLAY_MATH_DOLLARS = Regex("""\$\$""")
    private val DISPLAY_MATH_SQUARE = Regex("""\\\[""")
    // 行内数学：\(…\)（单 $…$ 不计入——与 $$ 的重叠无法廉价区分，宁缺勿重）。
    private val INLINE_MATH_PAREN = Regex("""\\\(""")

    /**
     * 计算结构摘要：对文本做常数次 O(n) 正则扫描。冷开路径可直接调用，但
     * 调用方仍应先按尺寸阈值（[CONTENT_DIAG_MIN_CHARS]）把小正文筛掉。
     */
    fun summarize(text: String): Summary {
        if (text.isEmpty()) return Summary(0, 0, 0, 0, 0, 0)
        val mathMarks = countMathBlocks(text)
        return Summary(
            chars = text.length,
            lines = text.count { it == '\n' } + 1,
            // 段落：一或多个空行分隔的非空段。
            paragraphs = text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }.coerceAtLeast(1),
            tableCount = TABLE_SEPARATOR.findAll(text).count(),
            // 围栏标记数配对折半，未闭合的尾围栏向上取整。
            codeBlockCount = (FENCE_MARKER.findAll(text).count() + 1) / 2,
            mathCount = mathMarks,
        )
    }

    private fun countMathBlocks(text: String): Int {
        val displayByDollar = DISPLAY_MATH_DOLLARS.findAll(text).count() / 2
        val displayByBracket = DISPLAY_MATH_SQUARE.findAll(text).count()
        val inlineByParen = INLINE_MATH_PAREN.findAll(text).count()
        return displayByDollar + displayByBracket + inlineByParen
    }

    // ─── 供 HangDetector 对时的「当前渲染」指纹 ──────────────────────────

    data class RenderMarker(val sessionId: String, val messageId: String, val summary: Summary)

    private val rendering = AtomicReference<RenderMarker?>(null)

    /** 渲染路径开始渲染大消息体时登记。 */
    fun setCurrentRender(sessionId: String, messageId: String, summary: Summary) {
        rendering.set(RenderMarker(sessionId, messageId, summary))
    }

    /** 大块渲染完成 / 离开组合时清除（只清自己那条，防误删后继登记）。 */
    fun clearCurrentRender(messageId: String) {
        val marker = rendering.get() ?: return
        if (marker.messageId == messageId) rendering.set(null)
    }

    /** HangDetector 用：屏上消息的可 grep 片段，无登记则空串。 */
    fun currentRenderLogFields(): String {
        val marker = rendering.get() ?: return ""
        return " renderSession=${marker.sessionId} renderMsg=${marker.messageId} ${marker.summary.asLogFields()}"
    }
}
