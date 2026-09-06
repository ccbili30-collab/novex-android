package com.openminis.app.novex.domain

/** A read receipt, not a mutation of the source block. Offsets are UTF-16, end exclusive. */
data class NovexLearningReadRange(
    val documentRef: NovexResourceRef,
    val blockId: String,
    val start: Int,
    val end: Int,
) {
    init {
        require(blockId.isNotBlank() && start >= 0 && end >= start) { "学习片段范围无效" }
    }
}

data class NovexLearningBatch(
    val blocks: List<NovexDocumentBlock>,
    val ranges: List<NovexLearningReadRange>,
)

/** Shared, deterministic source splitting. No source file is rewritten and no text is dropped. */
object NovexLearningBatchPlanner {
    const val DEFAULT_MAX_BLOCKS = 5000
    const val DEFAULT_MAX_CHARS = 24_000

    fun plan(
        documentRef: NovexResourceRef,
        blocks: List<NovexDocumentBlock>,
        maxBlocks: Int = DEFAULT_MAX_BLOCKS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        readRanges: List<NovexLearningReadRange> = emptyList(),
    ): List<NovexLearningBatch> {
        require(maxBlocks > 0 && maxChars >= 2)
        val covered = readRanges.filter { it.documentRef == documentRef }.groupBy { it.blockId }
        val result = mutableListOf<NovexLearningBatch>()
        var content = mutableListOf<NovexDocumentBlock>()
        var ranges = mutableListOf<NovexLearningReadRange>()
        var chars = 0
        fun flush() {
            if (content.isNotEmpty()) result += NovexLearningBatch(content, ranges)
            content = mutableListOf()
            ranges = mutableListOf()
            chars = 0
        }
        for (block in blocks) {
            val unseen = missing(block.text.length, covered[block.id].orEmpty())
            for ((start, end) in unseen) {
                var position = start
                do {
                    if (content.size >= maxBlocks || maxChars - chars < 2) flush()
                    val next = splitEnd(block.text, position, end, maxChars - chars)
                    content += block.copy(text = block.text.substring(position, next))
                    ranges += NovexLearningReadRange(documentRef, block.id, position, next)
                    chars += next - position
                    position = next
                    if (position < end) flush()
                } while (position < end)
            }
        }
        flush()
        return result
    }

    fun fullyCovered(length: Int, ranges: List<NovexLearningReadRange>): Boolean =
        missing(length, ranges).isEmpty()

    private fun missing(length: Int, ranges: List<NovexLearningReadRange>): List<Pair<Int, Int>> {
        if (length == 0) return if (ranges.any { it.start == 0 && it.end == 0 }) emptyList() else listOf(0 to 0)
        var position = 0
        val result = mutableListOf<Pair<Int, Int>>()
        ranges.sortedBy { it.start }.forEach { range ->
            val start = range.start.coerceAtMost(length)
            if (start > position) result += position to start
            position = maxOf(position, range.end.coerceAtMost(length))
        }
        if (position < length) result += position to length
        return result
    }

    private fun splitEnd(text: String, start: Int, end: Int, budget: Int): Int {
        var next = minOf(end.toLong(), start.toLong() + budget).toInt()
        if (next < end) {
            // Prefer a nearby paragraph boundary without creating tiny tail batches.
            val newline = text.lastIndexOf('\n', next - 1)
            if (newline >= start + budget * 3 / 4) next = newline + 1
            if (next > start && text[next - 1].isHighSurrogate() && text[next].isLowSurrogate()) next--
        }
        return next
    }
}
