package com.openminis.app.novex.domain

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocxStreamingParserTest {
    private val sha256 = "a".repeat(64)

    @Test
    fun preservesHeadingParagraphListTablePageBreakAndImageOrder() {
        val file = docx(
            "word/styles.xml" to xml("""
                <w:styles xmlns:w="urn:w">
                  <w:style w:type="paragraph" w:styleId="TitleOne">
                    <w:name w:val="heading 1"/>
                  </w:style>
                </w:styles>
            """),
            "word/_rels/document.xml.rels" to xml("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId5" Type="image" Target="media/map.png"/>
                </Relationships>
            """),
            "word/document.xml" to xml("""
                <w:document xmlns:w="urn:w" xmlns:a="urn:a" xmlns:r="urn:r"><w:body>
                  <w:p><w:pPr><w:pStyle w:val="TitleOne"/></w:pPr><w:r><w:t>北境档案</w:t></w:r></w:p>
                  <w:p><w:r><w:t>霜港仍在戒严。</w:t></w:r></w:p>
                  <w:p><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>第一项</w:t></w:r></w:p>
                  <w:tbl><w:tr><w:tc><w:p><w:r><w:t>人物</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>埃莉诺</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                  <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                  <w:p><w:r><w:drawing><a:blip r:embed="rId5"/></w:drawing></w:r></w:p>
                </w:body></w:document>
            """),
            "word/media/map.png" to "image-bytes",
        )

        val result = NovexDocxStreamingParser().parse(file, sha256)

        assertEquals(NovexDocumentStatus.READY, result.status)
        assertEquals(
            listOf(
                NovexDocumentBlockKind.HEADING,
                NovexDocumentBlockKind.PARAGRAPH,
                NovexDocumentBlockKind.LIST_ITEM,
                NovexDocumentBlockKind.TABLE,
                NovexDocumentBlockKind.PAGE_BREAK,
                NovexDocumentBlockKind.IMAGE,
            ),
            result.blocks.map { it.kind },
        )
        assertEquals(listOf("北境档案"), result.blocks[1].headingPath)
        assertEquals("人物\t埃莉诺", result.blocks[3].text)
        assertTrue(result.blocks.last().mediaRef?.value?.startsWith("novex://document-media/") == true)
        assertEquals("word/media/map.png", result.blocks.last().source.detail)
    }

    @Test
    fun includesAcceptedRevisionsAndTextBoxesButExcludesDeletedText() {
        val file = docx(
            "word/document.xml" to xml("""
                <w:document xmlns:w="urn:w" xmlns:v="urn:v"><w:body>
                  <w:p><w:r><w:t>保留</w:t></w:r><w:ins><w:r><w:t>新增</w:t></w:r></w:ins><w:del><w:r><w:delText>删除</w:delText></w:r></w:del></w:p>
                  <w:p><w:r><w:pict><v:textbox><w:txbxContent><w:p><w:r><w:t>浮动文本框</w:t></w:r></w:p></w:txbxContent></v:textbox></w:pict></w:r></w:p>
                </w:body></w:document>
            """),
        )

        val result = NovexDocxStreamingParser().parse(file, sha256)

        assertTrue(result.blocks.any { it.text == "保留新增" })
        assertTrue(result.blocks.any { it.text == "浮动文本框" })
        assertFalse(result.blocks.any { "删除" in it.text })
    }

    @Test
    fun emitsNotesAndHeadersWithTheirOriginalPartAnchors() {
        val file = docx(
            "word/document.xml" to xml("""
                <w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>
            """),
            "word/header1.xml" to xml("""
                <w:hdr xmlns:w="urn:w"><w:p><w:r><w:t>页眉</w:t></w:r></w:p></w:hdr>
            """),
            "word/footnotes.xml" to xml("""
                <w:footnotes xmlns:w="urn:w"><w:footnote w:id="2"><w:p><w:r><w:t>脚注说明</w:t></w:r></w:p></w:footnote></w:footnotes>
            """),
        )

        val result = NovexDocxStreamingParser().parse(file, sha256)

        assertTrue(result.blocks.any { it.text == "页眉" && it.source.part == "word/header1.xml" })
        assertTrue(result.blocks.any {
            it.kind == NovexDocumentBlockKind.NOTE &&
                it.text == "脚注说明" &&
                it.source.part == "word/footnotes.xml"
        })
    }

    @Test
    fun rejectsDoctypeAndOversizedPackageMetadataBeforeParsingXml() {
        val unsafe = docx(
            "word/document.xml" to """<!DOCTYPE x [<!ENTITY leak SYSTEM "file:///etc/passwd">]><w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>&leak;</w:t></w:r></w:p></w:body></w:document>""",
        )
        val oversized = docx(
            "word/document.xml" to xml("""
                <w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>
            """),
            "word/extra.xml" to "<extra/>",
        )

        val unsafeFailure = runCatching { NovexDocxStreamingParser().parse(unsafe, sha256) }.exceptionOrNull()
        val limitedFailure = runCatching {
            NovexDocxStreamingParser(
                limits = NovexDocxPackageLimits(maxEntries = 1),
            ).parse(oversized, sha256)
        }.exceptionOrNull()

        assertTrue(unsafeFailure is NovexDocxParseException)
        assertEquals("document.unsafe_xml", (unsafeFailure as NovexDocxParseException).code)
        assertTrue(limitedFailure is NovexDocxParseException)
        assertEquals("document.package_too_large", (limitedFailure as NovexDocxParseException).code)
    }

    private fun docx(vararg entries: Pair<String, String>): File {
        val file = kotlin.io.path.createTempFile(suffix = ".docx").toFile().apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun xml(body: String): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>${body.trimIndent()}"
}
