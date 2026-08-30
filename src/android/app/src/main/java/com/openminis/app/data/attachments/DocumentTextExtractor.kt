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
 * This intentionally uses small format-specific readers instead of a large
 * office suite dependency. OOXML and EPUB are ZIP containers; PDF is handled
 * by the Android PDFBox port. Legacy binary Office files remain unsupported.
 */
object DocumentTextExtractor {
    const val MAX_EXTRACTED_CHARS = 2_000_000

    data class Result(
        val text: String,
        val formatLabel: String,
        val truncated: Boolean,
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
            "docx" -> extractDocx(file) to "Word 文档"
            "xlsx" -> extractXlsx(file) to "Excel 工作簿"
            "pptx" -> extractPptx(file) to "PowerPoint 演示文稿"
            "pdf" -> extractPdf(context, file) to "PDF 文档"
            "epub" -> extractEpub(file) to "EPUB 电子书"
            "rtf" -> extractRtf(file.readText()) to "RTF 文档"
            else -> file.readText() to "文本文件"
        }
        val normalized = raw.first.replace("\u0000", "").trim()
        if (normalized.isBlank()) return null
        val truncated = normalized.length > MAX_EXTRACTED_CHARS
        val body = if (truncated) normalized.take(MAX_EXTRACTED_CHARS) else normalized
        return Result(
            text = buildString {
                append("# 从 ").append(originalName).append(" 提取的内容\n\n")
                append("> 格式：").append(raw.second).append("。此文件由 Novex 在本地生成，原文件未被修改。\n\n")
                append(body)
                if (truncated) append("\n\n> 内容过长，已截断至 ${MAX_EXTRACTED_CHARS} 个字符。")
            },
            formatLabel = raw.second,
            truncated = truncated,
        )
    }

    private fun extractDocx(file: File): String = ZipFile(file).use { zip ->
        val names = buildList {
            add("word/document.xml")
            zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("word/(header|footer)\\d+\\.xml")) || it == "word/footnotes.xml" || it == "word/endnotes.xml" }
                .sorted().forEach(::add)
        }
        names.mapNotNull { name -> zip.getEntry(name)?.let { parseWordXml(zip.readText(it)) } }
            .filter(String::isNotBlank).joinToString("\n\n")
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
