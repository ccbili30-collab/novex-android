package com.openminis.app.novex.domain

/** A derived navigation index; inferred headings never mutate the source snapshot. */
data class NovexDocumentOutlineEntry(
    val blockId: String,
    val title: String,
    val level: Int,
    val headingPath: List<String>,
    val firstBlock: Int,
    val lastBlock: Int,
    val inferred: Boolean,
)

object NovexDocumentOutline {
    private val chapterTitle = Regex("^第[零〇一二三四五六七八九十百千万两0-9０-９]+[章节卷部篇](?:[\\s·•:：、.．—–-]+.{0,100})?$")

    fun entries(snapshot: NovexDocumentSnapshot): List<NovexDocumentOutlineEntry> {
        val explicit = snapshot.blocks.withIndex().filter { it.value.kind == NovexDocumentBlockKind.HEADING }
        val inferred = explicit.isEmpty()
        val headings = if (!inferred) explicit else snapshot.blocks.withIndex().filter { (_, block) ->
            val text = block.text.trim()
            block.kind == NovexDocumentBlockKind.PARAGRAPH && text.length <= 120 &&
                !text.contains('\n') && chapterTitle.matches(text)
        }
        return headings.mapIndexed { index, (position, block) ->
            val level = if (inferred) 1 else requireNotNull(block.headingLevel)
            val next = headings.drop(index + 1).firstOrNull {
                inferred || requireNotNull(it.value.headingLevel) <= level
            }?.index ?: snapshot.blocks.size
            NovexDocumentOutlineEntry(
                blockId = block.id, title = block.text.trim(), level = level,
                headingPath = if (inferred) listOf(block.text.trim()) else block.headingPath,
                firstBlock = position + 1, lastBlock = next, inferred = inferred,
            )
        }
    }
}
