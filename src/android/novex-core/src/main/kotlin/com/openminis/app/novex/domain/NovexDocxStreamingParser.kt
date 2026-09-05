package com.openminis.app.novex.domain

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

data class NovexDocxPackageLimits(
    val maxEntries: Int = 2_048,
    val maxEntryBytes: Long = 64L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 256L * 1024L * 1024L,
    val maxBlocks: Int = 250_000,
) {
    init {
        require(maxEntries > 0) { "文档压缩包条目上限必须大于零" }
        require(maxEntryBytes > 0) { "文档压缩包单项上限必须大于零" }
        require(maxTotalUncompressedBytes >= maxEntryBytes) { "文档压缩包总上限不能小于单项上限" }
        require(maxBlocks > 0) { "文档内容块上限必须大于零" }
    }
}

class NovexDocxParseException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Dependency-free streaming OOXML reader. It builds only Novex blocks, never a Word object graph.
 * Apache POI remains an adapter-level fallback while this parser earns fixture coverage.
 */
class NovexDocxStreamingParser(
    private val limits: NovexDocxPackageLimits = NovexDocxPackageLimits(),
) {
    fun parse(file: File, documentSha256: String): NovexStructuredDocument {
        require(documentSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "文档校验值必须是 SHA-256" }
        return try {
            ZipFile(file).use { zip -> parsePackage(zip, documentSha256.lowercase()) }
        } catch (failure: NovexDocxParseException) {
            throw failure
        } catch (failure: Exception) {
            throw NovexDocxParseException(
                code = "document.invalid_docx",
                message = "无法读取新版 Word 文档压缩包",
                cause = failure,
            )
        }
    }

    private fun parsePackage(zip: ZipFile, sha256: String): NovexStructuredDocument {
        val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
        validatePackage(entries)
        val documentEntry = zip.getEntry(DOCUMENT_PART)
            ?: throw NovexDocxParseException("document.missing_main_part", "新版 Word 文档缺少正文部件")
        val styles = zip.getEntry(STYLES_PART)?.let { entry ->
            parseStyles(zip.openBounded(entry))
        }.orEmpty()
        val relationships = zip.getEntry(RELATIONSHIPS_PART)?.let { entry ->
            parseRelationships(zip.openBounded(entry))
        }.orEmpty()
        val blocks = mutableListOf<NovexStructuredDocumentBlock>()
        val headings = mutableListOf<String>()
        blocks += parseContentPart(
            input = zip.openBounded(documentEntry),
            part = DOCUMENT_PART,
            sha256 = sha256,
            styles = styles,
            relationships = relationships,
            headings = headings,
            notePart = false,
        )
        val auxiliaryParts = entries.asSequence().map(ZipEntry::getName)
            .filter { name ->
                name.matches(Regex("word/(header|footer)\\d+\\.xml")) ||
                    name in setOf("word/footnotes.xml", "word/endnotes.xml", "word/comments.xml")
            }
            .sorted()
            .toList()
        auxiliaryParts.forEach { part ->
            val notePart = part.endsWith("notes.xml") || part.endsWith("comments.xml")
            val partRelationships = zip.getEntry(relationshipsPart(part))?.let { entry ->
                parseRelationships(zip.openBounded(entry))
            }.orEmpty()
            blocks += parseContentPart(
                input = zip.openBounded(requireNotNull(zip.getEntry(part))),
                part = part,
                sha256 = sha256,
                styles = styles,
                relationships = partRelationships,
                headings = mutableListOf(),
                notePart = notePart,
            )
        }
        if (blocks.size > limits.maxBlocks) {
            throw NovexDocxParseException("document.block_limit_exceeded", "新版 Word 文档内容块超过安全上限")
        }
        val hasReadableText = blocks.any { block ->
            block.kind !in setOf(NovexDocumentBlockKind.IMAGE, NovexDocumentBlockKind.PAGE_BREAK) &&
                block.text.isNotBlank()
        }
        val hasImages = blocks.any { it.kind == NovexDocumentBlockKind.IMAGE }
        val status = when {
            hasReadableText -> NovexDocumentStatus.READY
            hasImages -> NovexDocumentStatus.OCR_REQUIRED
            else -> NovexDocumentStatus.EMPTY
        }
        val warnings = buildList {
            if (status == NovexDocumentStatus.OCR_REQUIRED) {
                add(NovexDocumentWarning("document.ocr_required", "文档只有图片，需要光学字符识别或视觉模型"))
            }
            if (status == NovexDocumentStatus.EMPTY) {
                add(NovexDocumentWarning("document.empty", "文档没有可提取的可见正文"))
            }
        }
        return NovexStructuredDocument(status = status, blocks = blocks, warnings = warnings)
    }

    private fun validatePackage(entries: List<ZipEntry>) {
        if (entries.size > limits.maxEntries) {
            throw NovexDocxParseException("document.package_too_large", "新版 Word 文档压缩包条目过多")
        }
        var total = 0L
        entries.forEach { entry ->
            val path = entry.name.replace('\\', '/')
            if (path.startsWith('/') || path.split('/').any { it == ".." }) {
                throw NovexDocxParseException("document.unsafe_package_path", "新版 Word 文档包含不安全路径")
            }
            val size = entry.size
            if (size > limits.maxEntryBytes) {
                throw NovexDocxParseException("document.package_too_large", "新版 Word 文档压缩包单项过大")
            }
            if (size > 0) {
                total = safeAdd(total, size)
                if (total > limits.maxTotalUncompressedBytes) {
                    throw NovexDocxParseException("document.package_too_large", "新版 Word 文档解压后过大")
                }
            }
        }
    }

    private fun ZipFile.openBounded(entry: ZipEntry): InputStream =
        BoundedInputStream(getInputStream(entry), limits.maxEntryBytes)

    private fun parseStyles(input: InputStream): Map<String, Int> {
        val styles = linkedMapOf<String, Int>()
        var styleId: String? = null
        var paragraphStyle = false
        parseXml(input, object : SafeHandler() {
            override fun onStart(name: String, attributes: Attributes) {
                when (name) {
                    "style" -> {
                        paragraphStyle = attributes.value("type") == "paragraph"
                        styleId = attributes.value("styleId")
                        if (paragraphStyle) headingLevel(styleId)?.let { styles[requireNotNull(styleId)] = it }
                    }
                    "name" -> if (paragraphStyle) {
                        val id = styleId
                        val level = headingLevel(attributes.value("val"))
                        if (id != null && level != null) styles[id] = level
                    }
                }
            }

            override fun onEnd(name: String) {
                if (name == "style") {
                    styleId = null
                    paragraphStyle = false
                }
            }
        })
        return styles
    }

    private fun parseRelationships(input: InputStream): Map<String, String> {
        val relationships = linkedMapOf<String, String>()
        parseXml(input, object : SafeHandler() {
            override fun onStart(name: String, attributes: Attributes) {
                if (name != "Relationship") return
                val id = attributes.value("Id") ?: return
                val target = attributes.value("Target") ?: return
                if (attributes.value("TargetMode").equals("External", ignoreCase = true)) return
                resolveWordPart(target)?.let { relationships[id] = it }
            }
        })
        return relationships
    }

    private fun parseContentPart(
        input: InputStream,
        part: String,
        sha256: String,
        styles: Map<String, Int>,
        relationships: Map<String, String>,
        headings: MutableList<String>,
        notePart: Boolean,
    ): List<NovexStructuredDocumentBlock> {
        val output = mutableListOf<NovexStructuredDocumentBlock>()
        val paragraphs = ArrayDeque<ParagraphState>()
        var table: TableState? = null
        var suppressDepth = 0
        var noteId: Long? = null
        var skipCurrentNote = false
        var ordinal = 0

        fun source(detail: String? = null) = NovexDocumentSourceAnchor(
            part = part,
            ordinal = ordinal++,
            detail = detail,
        )

        fun addBlock(
            kind: NovexDocumentBlockKind,
            text: String,
            headingLevel: Int? = null,
            source: NovexDocumentSourceAnchor = source(),
            mediaRef: NovexResourceRef? = null,
        ) {
            if (output.size >= limits.maxBlocks) {
                throw NovexDocxParseException("document.block_limit_exceeded", "新版 Word 文档内容块超过安全上限")
            }
            val normalized = text.replace("\u0000", "").trim()
            if (normalized.isEmpty() && kind !in setOf(
                    NovexDocumentBlockKind.PAGE_BREAK,
                    NovexDocumentBlockKind.IMAGE,
                )) return
            val blockHeadings = if (kind == NovexDocumentBlockKind.HEADING) {
                val level = requireNotNull(headingLevel)
                while (headings.size >= level) headings.removeLast()
                while (headings.size < level - 1) headings += "未命名层级"
                headings += normalized
                headings.toList()
            } else {
                headings.toList()
            }
            output += NovexStructuredDocumentBlock(
                kind = kind,
                text = normalized,
                headingPath = blockHeadings,
                headingLevel = headingLevel,
                source = source,
                mediaRef = mediaRef,
            )
        }

        parseXml(input, object : SafeHandler() {
            override fun onStart(name: String, attributes: Attributes) {
                if (name in setOf("del", "moveFrom")) suppressDepth += 1
                if (suppressDepth > 0) return
                when (name) {
                    "footnote", "endnote", "comment" -> {
                        noteId = attributes.value("id")?.toLongOrNull()
                        skipCurrentNote = noteId != null && requireNotNull(noteId) < 0
                    }
                    "p" -> paragraphs.addLast(ParagraphState())
                    "pStyle" -> paragraphs.peekLast()?.styleId = attributes.value("val")
                    "numPr", "numId" -> paragraphs.peekLast()?.isList = true
                    "tbl" -> if (table == null) table = TableState()
                    "tr" -> table?.startRow()
                    "tc" -> table?.startCell()
                    "tab" -> appendText("\t", paragraphs, table)
                    "br" -> {
                        if (attributes.value("type") == "page") paragraphs.peekLast()?.pageBreaks =
                            (paragraphs.peekLast()?.pageBreaks ?: 0) + 1
                        else appendText("\n", paragraphs, table)
                    }
                    "lastRenderedPageBreak" -> paragraphs.peekLast()?.pageBreaks =
                        (paragraphs.peekLast()?.pageBreaks ?: 0) + 1
                    "blip" -> attributes.value("embed")?.let { paragraphs.peekLast()?.imageIds?.add(it) }
                    "imagedata" -> attributes.value("id")?.let { paragraphs.peekLast()?.imageIds?.add(it) }
                }
            }

            override fun onText(text: String) {
                if (suppressDepth == 0 && !skipCurrentNote) appendText(text, paragraphs, table)
            }

            override fun onEnd(name: String) {
                if (name in setOf("del", "moveFrom")) {
                    suppressDepth = (suppressDepth - 1).coerceAtLeast(0)
                    return
                }
                if (suppressDepth > 0) return
                when (name) {
                    "tc" -> table?.finishCell()
                    "tr" -> table?.finishRow()
                    "tbl" -> table?.let { state ->
                        addBlock(NovexDocumentBlockKind.TABLE, state.render())
                        table = null
                    }
                    "p" -> if (paragraphs.isNotEmpty()) {
                        val paragraph = paragraphs.removeLast()
                        if (table == null && !skipCurrentNote) {
                            val level = styles[paragraph.styleId] ?: headingLevel(paragraph.styleId)
                            val kind = when {
                                notePart -> NovexDocumentBlockKind.NOTE
                                level != null -> NovexDocumentBlockKind.HEADING
                                paragraph.isList -> NovexDocumentBlockKind.LIST_ITEM
                                else -> NovexDocumentBlockKind.PARAGRAPH
                            }
                            addBlock(kind, paragraph.text.toString(), level)
                            repeat(paragraph.pageBreaks) { addBlock(NovexDocumentBlockKind.PAGE_BREAK, "") }
                            paragraph.imageIds.distinct().forEach { relationshipId ->
                                val mediaPath = relationships[relationshipId] ?: return@forEach
                                val mediaRef = NovexResourceRef(
                                    "novex://document-media/$sha256/${stableToken(mediaPath)}",
                                )
                                addBlock(
                                    kind = NovexDocumentBlockKind.IMAGE,
                                    text = mediaPath.substringAfterLast('/'),
                                    source = source(mediaPath),
                                    mediaRef = mediaRef,
                                )
                            }
                        }
                    }
                    "footnote", "endnote", "comment" -> {
                        noteId = null
                        skipCurrentNote = false
                    }
                }
            }
        })
        return output
    }

    private fun parseXml(input: InputStream, handler: SafeHandler) {
        input.use {
            try {
                val factory = SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                    runCatching { isXIncludeAware = false }
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                }
                val reader = factory.newSAXParser().xmlReader
                reader.contentHandler = handler
                reader.entityResolver = handler
                reader.errorHandler = handler
                runCatching { reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler) }
                reader.parse(InputSource(it))
            } catch (failure: NovexDocxParseException) {
                throw failure
            } catch (failure: Exception) {
                val detail = failure.causeChain().joinToString(" ") { cause ->
                    "${cause.javaClass.simpleName} ${cause.message.orEmpty()}"
                }.lowercase()
                val unsafe = "doctype" in detail || "entity" in detail || "external" in detail
                throw NovexDocxParseException(
                    code = if (unsafe) "document.unsafe_xml" else "document.invalid_xml",
                    message = if (unsafe) "文档包含不安全的 XML 声明" else "文档 XML 无法解析",
                    cause = failure,
                )
            }
        }
    }

    private open class SafeHandler : DefaultHandler2() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            onStart(localName.normalizedName(qName), attributes)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            onEnd(localName.normalizedName(qName))
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (length > 0) onText(String(ch, start, length))
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("External entity is disabled")

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("DOCTYPE is disabled")
        }

        open fun onStart(name: String, attributes: Attributes) = Unit
        open fun onText(text: String) = Unit
        open fun onEnd(name: String) = Unit
    }

    private class ParagraphState {
        val text = StringBuilder()
        var styleId: String? = null
        var isList: Boolean = false
        var pageBreaks: Int = 0
        val imageIds = mutableListOf<String>()
    }

    private class TableState {
        private val rows = mutableListOf<List<String>>()
        private var row: MutableList<String>? = null
        private var cell: StringBuilder? = null

        fun startRow() {
            row = mutableListOf()
        }

        fun startCell() {
            cell = StringBuilder()
        }

        fun append(value: String) {
            cell?.append(value)
        }

        fun finishCell() {
            row?.add(cell?.toString().orEmpty().replace(Regex("\\s+"), " ").trim())
            cell = null
        }

        fun finishRow() {
            row?.let(rows::add)
            row = null
        }

        fun render(): String = rows.joinToString("\n") { it.joinToString("\t") }.trim()
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) record(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) record(read.toLong())
            return read
        }

        private fun record(bytes: Long) {
            count = safeAdd(count, bytes)
            if (count > maxBytes) {
                throw NovexDocxParseException("document.package_too_large", "新版 Word 文档压缩包单项过大")
            }
        }

        override fun close() = delegate.close()
    }

    private companion object {
        const val DOCUMENT_PART = "word/document.xml"
        const val STYLES_PART = "word/styles.xml"
        const val RELATIONSHIPS_PART = "word/_rels/document.xml.rels"

        fun appendText(
            value: String,
            paragraphs: ArrayDeque<ParagraphState>,
            table: TableState?,
        ) {
            paragraphs.peekLast()?.text?.append(value)
            table?.append(value)
        }

        fun Attributes.value(localName: String): String? {
            for (index in 0 until length) {
                val local = getLocalName(index).ifBlank { getQName(index).substringAfter(':') }
                if (local == localName) return getValue(index)
            }
            return null
        }

        fun String?.normalizedName(fallback: String?): String =
            this?.takeIf(String::isNotBlank) ?: fallback.orEmpty().substringAfter(':')

        fun headingLevel(value: String?): Int? = value?.let {
            Regex("(?:heading|标题)\\s*([1-6])", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun resolveWordPart(target: String): String? {
            val normalized = if (target.startsWith('/')) target.removePrefix("/") else "word/$target"
            val parts = ArrayDeque<String>()
            normalized.replace('\\', '/').split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                    else -> parts.addLast(segment)
                }
            }
            return parts.joinToString("/").takeIf { it.startsWith("word/media/") }
        }

        fun relationshipsPart(part: String): String {
            val directory = part.substringBeforeLast('/', "")
            val fileName = part.substringAfterLast('/')
            return "$directory/_rels/$fileName.rels"
        }

        fun stableToken(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun safeAdd(left: Long, right: Long): Long {
            if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
            return left + right
        }

        fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
    }
}
