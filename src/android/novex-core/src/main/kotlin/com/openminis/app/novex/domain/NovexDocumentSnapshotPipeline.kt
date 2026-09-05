package com.openminis.app.novex.domain

data class NovexDocumentDescriptor(
    val ref: NovexResourceRef,
    val sha256: String,
    val title: String,
    val format: NovexDocumentFormat,
    val parserVersion: String,
) {
    init {
        require(ref.value.startsWith("novex://documents/")) { "文档描述必须使用文档引用" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "文档校验值必须是 SHA-256" }
        require(title.isNotBlank()) { "文档标题不能为空" }
        require(parserVersion.isNotBlank()) { "解析器版本不能为空" }
    }
}

data class NovexCompatibilityDocument(
    val text: String,
    val status: NovexDocumentStatus = NovexDocumentStatus.READY,
    val warnings: List<NovexDocumentWarning> = emptyList(),
)

data class NovexDocumentSnapshotCacheKey(
    val sha256: String,
    val parserVersion: String,
)

interface NovexDocumentSnapshotCache {
    fun find(key: NovexDocumentSnapshotCacheKey): NovexDocumentSnapshot?
    fun store(key: NovexDocumentSnapshotCacheKey, snapshot: NovexDocumentSnapshot)
}

class InMemoryNovexDocumentSnapshotCache : NovexDocumentSnapshotCache {
    private val snapshots = linkedMapOf<NovexDocumentSnapshotCacheKey, NovexDocumentSnapshot>()

    @Synchronized
    override fun find(key: NovexDocumentSnapshotCacheKey): NovexDocumentSnapshot? = snapshots[key]

    @Synchronized
    override fun store(key: NovexDocumentSnapshotCacheKey, snapshot: NovexDocumentSnapshot) {
        snapshots[key] = snapshot
    }
}

/**
 * Turns the current flat compatibility extractors into the structured Novex document contract.
 * The extractor itself remains outside this module, so Android and future streaming parsers can
 * share the same cache, block identity and status rules.
 */
class NovexDocumentSnapshotPipeline(
    private val cache: NovexDocumentSnapshotCache,
) {
    fun resolve(
        descriptor: NovexDocumentDescriptor,
        extract: () -> NovexCompatibilityDocument,
    ): NovexDocumentSnapshot {
        val key = descriptor.cacheKey()
        cache.find(key)?.let { cached ->
            return cached.copy(ref = descriptor.ref, title = descriptor.title)
        }
        return build(descriptor, extract()).also { snapshot -> cache.store(key, snapshot) }
    }

    fun build(
        descriptor: NovexDocumentDescriptor,
        document: NovexCompatibilityDocument,
    ): NovexDocumentSnapshot = NovexDocumentSnapshot(
        ref = descriptor.ref,
        sha256 = descriptor.sha256.lowercase(),
        parserVersion = descriptor.parserVersion,
        title = descriptor.title,
        format = descriptor.format,
        status = document.status,
        blocks = CompatibilityTextBlockParser.parse(descriptor.sha256, document.text),
        warnings = document.warnings,
    )

    fun resolveStructured(
        descriptor: NovexDocumentDescriptor,
        extract: () -> NovexStructuredDocument,
    ): NovexDocumentSnapshot {
        val key = descriptor.cacheKey()
        cache.find(key)?.let { cached ->
            return cached.copy(ref = descriptor.ref, title = descriptor.title)
        }
        return buildStructured(descriptor, extract()).also { snapshot -> cache.store(key, snapshot) }
    }

    fun buildStructured(
        descriptor: NovexDocumentDescriptor,
        document: NovexStructuredDocument,
    ): NovexDocumentSnapshot = NovexDocumentSnapshot(
        ref = descriptor.ref,
        sha256 = descriptor.sha256.lowercase(),
        parserVersion = descriptor.parserVersion,
        title = descriptor.title,
        format = descriptor.format,
        status = document.status,
        blocks = document.blocks.mapIndexed { order, block ->
            NovexDocumentBlock(
                id = NovexDocumentBlockId.from(descriptor.sha256, block.source),
                kind = block.kind,
                order = order,
                text = block.text,
                headingPath = block.headingPath,
                headingLevel = block.headingLevel,
                source = block.source,
                mediaRef = block.mediaRef,
            )
        },
        warnings = document.warnings,
    )

    private fun NovexDocumentDescriptor.cacheKey() = NovexDocumentSnapshotCacheKey(
        sha256 = sha256.lowercase(),
        parserVersion = parserVersion,
    )
}

private object CompatibilityTextBlockParser {
    private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
    private val listPattern = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(.+)$")

    fun parse(documentSha256: String, raw: String): List<NovexDocumentBlock> {
        if (raw.isBlank()) return emptyList()
        val blocks = mutableListOf<NovexDocumentBlock>()
        val headings = mutableListOf<String>()
        val paragraph = mutableListOf<SourceLine>()
        val table = mutableListOf<SourceLine>()

        fun add(kind: NovexDocumentBlockKind, text: String, line: Int, level: Int? = null) {
            val order = blocks.size
            val source = NovexDocumentSourceAnchor(
                part = "compatibility-text",
                ordinal = order,
                detail = "line:${line + 1}",
            )
            blocks += NovexDocumentBlock(
                id = NovexDocumentBlockId.from(documentSha256, source),
                kind = kind,
                order = order,
                text = text,
                headingPath = headings.toList(),
                headingLevel = level,
                source = source,
            )
        }

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            add(
                kind = NovexDocumentBlockKind.PARAGRAPH,
                text = paragraph.joinToString("\n") { it.text.trim() },
                line = paragraph.first().index,
            )
            paragraph.clear()
        }

        fun flushTable() {
            if (table.isEmpty()) return
            add(
                kind = NovexDocumentBlockKind.TABLE,
                text = table.joinToString("\n") { it.text.trim() },
                line = table.first().index,
            )
            table.clear()
        }

        raw.replace("\u0000", "").lineSequence().forEachIndexed { index, sourceText ->
            val text = sourceText.trimEnd()
            val heading = headingPattern.matchEntire(text.trim())
            val list = listPattern.matchEntire(text)
            when {
                text.isBlank() -> {
                    flushParagraph()
                    flushTable()
                }
                heading != null -> {
                    flushParagraph()
                    flushTable()
                    val level = heading.groupValues[1].length
                    val title = heading.groupValues[2].trim()
                    while (headings.size >= level) headings.removeLast()
                    headings += title
                    add(NovexDocumentBlockKind.HEADING, title, index, level)
                }
                list != null -> {
                    flushParagraph()
                    flushTable()
                    add(NovexDocumentBlockKind.LIST_ITEM, list.groupValues[1].trim(), index)
                }
                isTableLine(text) -> {
                    flushParagraph()
                    table += SourceLine(index, text)
                }
                else -> {
                    flushTable()
                    paragraph += SourceLine(index, text)
                }
            }
        }
        flushParagraph()
        flushTable()
        return blocks
    }

    private fun isTableLine(value: String): Boolean {
        if ('\t' in value) return true
        val trimmed = value.trim()
        return trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.count { it == '|' } >= 2
    }

    private data class SourceLine(val index: Int, val text: String)
}
