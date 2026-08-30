package com.openminis.app.data.attachments

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
}
