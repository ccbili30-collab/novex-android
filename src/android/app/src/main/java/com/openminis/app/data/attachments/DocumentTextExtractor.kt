package com.openminis.app.data.attachments

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Converts common authoring formats into a plain Markdown sidecar that the
 * agent can read with file_read. The original upload remains untouched.
 *
 * DOCX uses the Android-shaded Apache POI reader first and retains the small
 * OOXML ZIP/XML reader only as a recovery path. Other OOXML and EPUB formats
 * use format-specific readers; PDF is handled by the Android PDFBox port.
 * Legacy binary Office files remain unsupported.
 */
object DocumentTextExtractor {
    const val MAX_EXTRACTED_CHARS = 2_000_000

    class ExtractionException(
        val stage: String,
        cause: Throwable,
    ) : RuntimeException("Document extraction failed during $stage", cause)

    data class Result(
        val text: String,
        val formatLabel: String,
        val truncated: Boolean,
        val extractionEngine: String = "built-in",
        val primaryFailureType: String? = null,
    )

    private data class RawResult(
        val text: String,
        val formatLabel: String,
        val extractionEngine: String,
        val primaryFailureType: String? = null,
        val hasPictures: Boolean = false,
    )

    fun supports(fileName: String, mimeType: String?): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("docx", "xlsx", "pptx", "pdf", "epub", "rtf", "txt", "md", "markdown", "csv", "json", "xml", "yaml", "yml") ||
            mimeType.orEmpty().lowercase().let { mime ->
                mime.startsWith("text/") || mime.contains("wordprocessingml") ||
                    mime.contains("spreadsheetml") || mime.contains("presentationml") ||
                    mime.contains("application/pdf") || mime.contains("application/epub+zip") ||
                    mime.contains("application/rtf") || mime.contains("application/json")
            }
    }

    fun extract(context: Context?, file: File, mimeType: String?, originalName: String): Result? {
        if (!supports(originalName, mimeType)) return null
        val ext = originalName.substringAfterLast('.', "").lowercase()
        val raw = when (ext) {
            "docx" -> extractDocx(file)
            "xlsx" -> RawResult(extractXlsx(file), "Excel 工作簿", "built-in")
            "pptx" -> RawResult(extractPptx(file), "PowerPoint 演示文稿", "built-in")
            "pdf" -> RawResult(extractPdf(context, file), "PDF 文档", "PDFBox")
            "epub" -> RawResult(extractEpub(file), "EPUB 电子书", "built-in")
            "rtf" -> RawResult(extractRtf(file.readText()), "RTF 文档", "built-in")
            else -> RawResult(file.readText(), "文本文件", "built-in")
        }
        val normalized = raw.text.replace("\u0000", "").trim().ifBlank {
            when {
                ext == "docx" && raw.hasPictures ->
                    "该文档没有可提取的文字，内容可能仅由图片或扫描页组成。需要 OCR（光学字符识别）或视觉模型才能读取。"
                ext == "docx" ->
                    "该文档没有可提取的可见正文。若内容是图片或扫描页，需要 OCR（光学字符识别）或视觉模型。"
                else -> return null
            }
        }
        val truncated = normalized.length > MAX_EXTRACTED_CHARS
        val body = if (truncated) normalized.take(MAX_EXTRACTED_CHARS) else normalized
        return Result(
            text = buildString {
                append("# 从 ").append(originalName).append(" 提取的内容\n\n")
                append("> 格式：").append(raw.formatLabel).append("。此文件由 Novex 在本地解析，原文件未被修改。\n\n")
                append(body)
                if (truncated) append("\n\n> 内容过长，已截断至 ${MAX_EXTRACTED_CHARS} 个字符。")
            },
            formatLabel = raw.formatLabel,
            truncated = truncated,
            extractionEngine = raw.extractionEngine,
            primaryFailureType = raw.primaryFailureType,
        )
    }

    private fun extractDocx(file: File): RawResult {
        val primary = runCatching { DocxPoiTextExtractor.extract(file) }
        primary.getOrNull()?.let { extraction ->
            val supplemental = extractDocxSupplementalText(file)
            return RawResult(
                text = mergeUniqueText(extraction.text, supplemental),
                formatLabel = "Word 文档",
                extractionEngine = "poi-on-android",
                hasPictures = extraction.hasPictures || docxHasPictures(file),
            )
        }

        val primaryFailure = requireNotNull(primary.exceptionOrNull())
        return try {
            RawResult(
                text = extractDocxLegacy(file),
                formatLabel = "Word 文档",
                extractionEngine = "legacy-zip-xml-fallback",
                primaryFailureType = primaryFailure.javaClass.name,
                hasPictures = docxHasPictures(file),
            )
        } catch (fallbackFailure: Throwable) {
            fallbackFailure.addSuppressed(primaryFailure)
            throw ExtractionException("DOCX 降级 ZIP/XML 解析", fallbackFailure)
        }
    }

    private fun extractDocxLegacy(file: File): String = ZipFile(file).use { zip ->
        val names = buildList {
            add("word/document.xml")
            zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("word/(header|footer)\\d+\\.xml")) || it == "word/footnotes.xml" || it == "word/endnotes.xml" }
                .sorted().forEach(::add)
        }
        names.mapNotNull { name -> zip.getEntry(name)?.let { parseWordXml(zip.readText(it)) } }
            .filter(String::isNotBlank).joinToString("\n\n")
    }

    /**
     * POI is the primary parser. Raw OOXML is consulted only to supplement
     * two constructs that XWPFWordExtractor can omit on some producer files:
     * drawing text boxes and visible inserted revision text. Deleted revision
     * text uses w:delText and is intentionally excluded.
     */
    private fun extractDocxSupplementalText(file: File): String = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return@use ""
            val doc = parseXml(zip.readText(entry)) ?: return@use ""
            buildList {
                val textBoxes = doc.getElementsByTagNameNS("*", "txbxContent")
                for (i in 0 until textBoxes.length) {
                    add(collectText(textBoxes.item(i), setOf("t"), preserveTabsAndBreaks = true).trim())
                }
                val paragraphs = doc.getElementsByTagNameNS("*", "p")
                for (i in 0 until paragraphs.length) {
                    val paragraph = paragraphs.item(i) as? Element ?: continue
                    val hasVisibleRevision = paragraph.getElementsByTagNameNS("*", "ins").length > 0 ||
                        paragraph.getElementsByTagNameNS("*", "moveTo").length > 0
                    if (hasVisibleRevision) add(collectAcceptedRevisionText(paragraph).trim())
                }
            }
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("\n")
        }
    }.getOrDefault("")

    private fun docxHasPictures(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { !it.isDirectory && it.name.startsWith("word/media/") }
        }
    }.getOrDefault(false)

    private fun mergeUniqueText(primary: String, supplemental: String): String {
        val extra = supplemental.trim()
        if (extra.isEmpty() || primary.contains(extra)) return primary
        val missingLines = extra.lineSequence().map(String::trim)
            .filter(String::isNotBlank)
            .filterNot(primary::contains)
            .distinct()
            .toList()
        if (missingLines.isEmpty()) return primary
        return primary.trimEnd() + "\n" + missingLines.joinToString("\n")
    }

    private fun collectAcceptedRevisionText(root: Node): String {
        val out = StringBuilder()
        fun walk(node: Node) {
            val local = node.localName ?: node.nodeName.substringAfter(':')
            if (local == "del" || local == "moveFrom") return
            if (local == "t") {
                out.append(node.textContent)
                return
            }
            if (local == "tab") out.append('\t')
            if (local == "br" || local == "cr") out.append('\n')
            val children = node.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }
        walk(root)
        return out.toString()
    }

    private fun parseWordXml(xml: String): String {
        val doc = parseXml(xml) ?: return ""
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        return buildList {
            for (i in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(i) as? Element ?: continue
                val text = collectText(paragraph, setOf("t"), preserveTabsAndBreaks = true).trim()
                if (text.isBlank()) continue
                val styleNodes = paragraph.getElementsByTagNameNS("*", "pStyle")
                val style = if (styleNodes.length > 0) {
                    (styleNodes.item(0) as? Element)?.attributes?.let { attrs ->
                        (0 until attrs.length).map { attrs.item(it).nodeValue }.firstOrNull()
                    }
                } else null
                val heading = Regex("(?:Heading|标题)([1-6])", RegexOption.IGNORE_CASE).find(style.orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                val isList = paragraph.getElementsByTagNameNS("*", "numPr").length > 0
                add(when {
                    heading != null -> "${"#".repeat(heading)} $text"
                    isList -> "- $text"
                    else -> text
                })
            }
        }.joinToString("\n\n")
    }

    private fun extractXlsx(file: File): String = ZipFile(file).use { zip ->
        val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val doc = parseXml(zip.readText(entry))
            val items = doc?.getElementsByTagNameNS("*", "si")
            if (items == null) emptyList() else (0 until items.length).map { index ->
                collectText(items.item(index), setOf("t"))
            }
        }.orEmpty()
        val sheetNames = zip.getEntry("xl/workbook.xml")?.let { entry ->
            val doc = parseXml(zip.readText(entry))
            val sheets = doc?.getElementsByTagNameNS("*", "sheet")
            if (sheets == null) emptyList() else (0 until sheets.length).map { index ->
                (sheets.item(index) as? Element)?.getAttribute("name").orEmpty().ifBlank { "工作表 ${index + 1}" }
            }
        }.orEmpty()
        val sheets = zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }.toList()
        sheets.mapIndexed { sheetIndex, path ->
            val doc = parseXml(zip.readText(zip.getEntry(path)))
            val rows = doc?.getElementsByTagNameNS("*", "row")
            val lines = if (rows == null) emptyList() else (0 until rows.length).mapNotNull { rowIndex ->
                val row = rows.item(rowIndex) as? Element ?: return@mapNotNull null
                val cells = row.getElementsByTagNameNS("*", "c")
                val values = (0 until cells.length).map { cellIndex ->
                    val cell = cells.item(cellIndex) as? Element
                    val type = cell?.getAttribute("t")
                    val inline = cell?.let { collectText(it, setOf("t")) }.orEmpty()
                    val value = cell?.getElementsByTagNameNS("*", "v")?.item(0)?.textContent.orEmpty()
                    when {
                        type == "s" -> shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                        type == "inlineStr" -> inline
                        else -> value.ifBlank { inline }
                    }
                }
                values.joinToString("\t").takeIf(String::isNotBlank)
            }
            "## ${sheetNames.getOrNull(sheetIndex) ?: "工作表 ${sheetIndex + 1}"}\n\n${lines.joinToString("\n")}".trim()
        }.joinToString("\n\n")
    }

    private fun extractPptx(file: File): String = ZipFile(file).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
            .mapIndexed { index, path ->
                val doc = parseXml(zip.readText(zip.getEntry(path)))
                val nodes = doc?.getElementsByTagNameNS("*", "t")
                val lines = if (nodes == null) emptyList() else (0 until nodes.length)
                    .map { nodes.item(it).textContent.trim() }.filter(String::isNotBlank)
                "## 第 ${index + 1} 页\n\n${lines.joinToString("\n")}".trim()
            }.joinToString("\n\n")
    }

    private fun extractPdf(context: Context?, file: File): String {
        requireNotNull(context) { "PDF extraction requires an Android context" }
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(file).use { document -> PDFTextStripper().getText(document) }
    }

    private fun extractEpub(file: File): String = ZipFile(file).use { zip ->
        val paths = zip.entries().asSequence().map { it.name }
            .filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }
            .filterNot { it.contains("nav", true) || it.contains("toc", true) }
            .sorted().toList()
        paths.mapNotNull { path ->
            val raw = zip.readText(zip.getEntry(path))
            val text = parseXml(raw)?.documentElement?.let { collectText(it, setOf("h1", "h2", "h3", "h4", "p", "li"), blockElements = true) }
                ?: stripHtml(raw)
            text.trim().takeIf(String::isNotBlank)
        }.joinToString("\n\n")
    }

    private fun extractRtf(raw: String): String = raw
        .replace(Regex("\\\\'(\\p{XDigit}{2})")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString().orEmpty()
        }
        .replace(Regex("\\\\(par|line)\\b ?"), "\n")
        .replace(Regex("\\\\tab\\b ?"), "\t")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        .replace(Regex("[{}]"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun stripHtml(raw: String): String = raw
        .replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), "")
        .replace(Regex("(?i)</?(p|div|h[1-6]|li|br|section|article)[^>]*>"), "\n")
        .replace(Regex("(?s)<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\n{3,}"), "\n\n")

    private fun parseXml(xml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        factory.newDocumentBuilder().parse(xml.byteInputStream())
    }.getOrNull()

    private fun collectText(
        root: Node,
        acceptedLocalNames: Set<String>,
        preserveTabsAndBreaks: Boolean = false,
        blockElements: Boolean = false,
    ): String {
        val out = StringBuilder()
        fun walk(node: Node) {
            val local = node.localName ?: node.nodeName.substringAfter(':')
            if (node.nodeType == Node.ELEMENT_NODE && local in acceptedLocalNames) {
                if (blockElements && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                if (node.childNodes.length == 1 && node.firstChild?.nodeType == Node.TEXT_NODE) {
                    out.append(node.textContent)
                    if (blockElements) out.append('\n')
                    return
                }
            }
            if (preserveTabsAndBreaks && local == "tab") out.append('\t')
            if (preserveTabsAndBreaks && (local == "br" || local == "cr")) out.append('\n')
            val children = node.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }
        walk(root)
        return out.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    private fun ZipFile.readText(entry: java.util.zip.ZipEntry): String =
        getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
