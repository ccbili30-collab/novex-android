package com.openminis.app.data.attachments

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentTextExtractorTest {
    @Test
    fun `docx paragraphs and table text become readable markdown`() {
        val file = makeZip(
            "word/document.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="urn:test"><w:body>
                  <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>北境档案</w:t></w:r></w:p>
                  <w:p><w:r><w:t>霜港仍在戒严。</w:t></w:r></w:p>
                  <w:tbl><w:tr><w:tc><w:p><w:r><w:t>人物</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>埃莉诺</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>
            """.trimIndent(),
        )
        val result = DocumentTextExtractor.extract(null, file, null, "setting.docx")
        assertNotNull(result)
        assertTrue(result!!.text.contains("# 北境档案"))
        assertTrue(result.text.contains("霜港仍在戒严"))
        assertTrue(result.text.contains("埃莉诺"))
    }

    @Test
    fun `xlsx shared strings become readable rows`() {
        val file = makeZip(
            "xl/sharedStrings.xml" to """
                <sst xmlns="urn:test"><si><t>姓名</t></si><si><t>埃莉诺</t></si></sst>
            """.trimIndent(),
            "xl/workbook.xml" to """
                <workbook xmlns="urn:test"><sheets><sheet name="角色"/></sheets></workbook>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="urn:test"><sheetData><row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row></sheetData></worksheet>
            """.trimIndent(),
        )
        val result = DocumentTextExtractor.extract(null, file, null, "characters.xlsx")
        assertNotNull(result)
        assertTrue(result!!.text.contains("## 角色"))
        assertTrue(result.text.contains("姓名\t埃莉诺"))
    }

    @Test
    fun `legacy binary office files are not claimed as supported`() {
        assertFalse(DocumentTextExtractor.supports("legacy.doc", "application/msword"))
        assertFalse(DocumentTextExtractor.supports("legacy.xls", "application/vnd.ms-excel"))
    }

    @Test
    fun `image only docx explicitly asks for OCR or a vision model`() {
        val file = fixture("docx/microsoft-word-image-only.docx")

        val result = DocumentTextExtractor.extract(null, file, null, "scan.docx")

        assertNotNull(result)
        assertTrue(result!!.text.contains("OCR"))
        assertTrue(result.text.contains("视觉模型"))
    }

    @Test
    fun `libreoffice comments are part of extracted visible document context`() {
        val file = fixture("docx/libreoffice-comment.docx")

        val result = DocumentTextExtractor.extract(null, file, null, "review.docx")

        assertNotNull(result)
        assertTrue(result!!.text.contains("This is the first line"))
    }

    @Test
    fun `google docs export is parsed by the primary reader`() {
        val result = extractFixture("google-docs-sample.docx")

        assertTrue(result.text.contains("The Canons of Rhetoric"))
        assertTrue(result.text.contains("Correctness of Style"))
        assertTrue(result.extractionEngine.contains("poi-on-android"))
    }

    @Test
    fun `official WPS template produces an explicit empty document message`() {
        val result = extractFixture("wps-office-official-template.docx")

        assertTrue(result.text.contains("没有可提取的可见正文"))
        assertTrue(result.extractionEngine.contains("poi-on-android"))
    }

    @Test
    fun `word headers footers footnotes endnotes and hyperlinks are extracted`() {
        val headerFooter = extractFixture("microsoft-word-header-footer-notes.docx")
        val footnotes = extractFixture("microsoft-word-footnotes.docx")
        val endnotes = extractFixture("microsoft-word-endnotes.docx")
        val hyperlink = extractFixture("microsoft-word-hyperlink.docx")

        assertTrue(headerFooter.text.contains("I am some simple header text here"))
        assertTrue(headerFooter.text.contains("Footer Middle"))
        assertTrue(footnotes.text.contains("snoska"))
        assertTrue(endnotes.text.contains("XXX"))
        assertTrue(hyperlink.text.contains("http://poi.apache.org/"))
    }

    @Test
    fun `word lists tables and text boxes are extracted`() {
        val list = extractFixture("microsoft-word-numbered-lists.docx")
        val table = extractFixture("microsoft-word-table.docx")
        val textBoxes = extractFixture("microsoft-word-textboxes.docx")

        assertTrue(list.text.contains("Entry #2, with children"))
        assertTrue(list.text.contains("2-a"))
        assertTrue(table.text.contains("Loren"))
        assertTrue(table.text.contains("Ipsum"))
        assertTrue(textBoxes.text.contains("Floating text box"))
        assertTrue(textBoxes.text.contains("An ellipse with text inside"))
    }

    @Test
    fun `word tracked changes use the visible accepted text`() {
        val result = extractFixture("microsoft-word-revisions.docx")

        assertTrue(result.text.contains("This is a filler sentence."))
        assertTrue(result.text.contains("Will this sentence be duplicated ADDED STUFF?"))
    }

    @Test
    fun `corrupt docx exposes the real exception type and parsing stage`() {
        val corrupt = kotlin.io.path.createTempFile(suffix = ".docx").toFile().apply {
            writeText("this is not an OOXML package")
            deleteOnExit()
        }
        val failure = try {
            DocumentTextExtractor.extract(null, corrupt, null, "broken.docx")
            fail("Expected extraction failure")
            error("unreachable")
        } catch (expected: DocumentTextExtractor.ExtractionException) {
            expected
        }

        val diagnostic = documentExtractionDiagnostic("broken.docx", corrupt.length(), failure)

        assertTrue(diagnostic.exceptionType.contains("ZipException"))
        assertTrue(diagnostic.stage.contains("降级"))
        assertTrue(diagnostic.userMessage.contains("ZipException"))
        assertTrue(diagnostic.logMessage.contains("file=broken.docx"))
        assertTrue(diagnostic.logMessage.contains("size=${corrupt.length()}"))
        assertTrue(diagnostic.logMessage.contains("exception="))
        assertTrue(diagnostic.logMessage.contains("stage="))
    }

    private fun makeZip(vararg entries: Pair<String, String>): File {
        val file = kotlin.io.path.createTempFile(suffix = ".zip").toFile().apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun fixture(path: String): File = File(
        requireNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture: $path" }.toURI(),
    )

    private fun extractFixture(name: String): DocumentTextExtractor.Result = requireNotNull(
        DocumentTextExtractor.extract(null, fixture("docx/$name"), null, name),
    )
}
