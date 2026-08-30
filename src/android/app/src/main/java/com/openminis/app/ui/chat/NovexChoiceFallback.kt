package com.openminis.app.ui.chat

/**
 * Conservative client-side fallback for models that describe a choice but
 * forget to call `present_choices` (呈现选项).
 *
 * This intentionally accepts only a short, consecutive numbered/circled list
 * near an explicit selection question. Ordinary world-building lists and
 * attribute inventories therefore stay normal prose.
 */
internal object NovexChoiceFallback {
    private val numbered = Regex("^\\s*(?:([1-9]|1[0-2])[.、)]|([①②③④⑤⑥⑦⑧⑨⑩⑪⑫]))\\s*(.{1,80}?)\\s*$")
    private val selectionCue = Regex(
        "(?:请选择|选择(?:一个|其中|你的|想要|起点|时代|身份|资质|风格)|" +
            "选一个|从哪里开始|你想.{0,12}(?:开始|选择)|确认或改|任选其一|挑一个)",
    )

    fun extract(markdown: String): List<String> {
        if (markdown.isBlank()) return emptyList()
        val lines = markdown.lines()
        val groups = mutableListOf<Pair<IntRange, List<String>>>()
        var start = -1
        var values = mutableListOf<String>()

        fun flush(endExclusive: Int) {
            if (start >= 0 && values.size >= 2) {
                groups += (start until endExclusive) to values.toList()
            }
            start = -1
            values = mutableListOf()
        }

        lines.forEachIndexed { index, line ->
            val match = numbered.matchEntire(line)
            if (match == null) {
                flush(index)
            } else {
                if (start < 0) start = index
                val value = match.groupValues[3]
                    .trim()
                    .removeSuffix("。")
                    .removeSuffix("；")
                    .trim()
                if (value.isNotEmpty()) values += value
            }
        }
        flush(lines.size)

        return groups.asReversed().firstNotNullOfOrNull { (range, candidates) ->
            if (candidates.size !in 2..12 || candidates.distinct().size != candidates.size) {
                return@firstNotNullOfOrNull null
            }
            val contextStart = (range.first - 4).coerceAtLeast(0)
            val contextEnd = (range.last + 4).coerceAtMost(lines.lastIndex)
            val context = lines.subList(contextStart, contextEnd + 1).joinToString("\n")
            candidates.takeIf { selectionCue.containsMatchIn(context) }
        } ?: emptyList()
    }
}
