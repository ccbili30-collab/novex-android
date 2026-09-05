package com.openminis.app.data.attachments

import com.openminis.app.novex.domain.InMemoryNovexDocumentSnapshotCache
import com.openminis.app.novex.domain.NovexDocumentBlockKind
import com.openminis.app.novex.domain.NovexDocumentStatus
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocumentSnapshotExtractorTest {
    @Test
    fun realExistingDocxExtractorFeedsTheStructuredSnapshotAdapter() {
        val original = makeZip(
            "word/document.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="urn:test"><w:body>
                  <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>北境档案</w:t></w:r></w:p>
                  <w:p><w:r><w:t>霜港仍在戒严。</w:t></w:r></w:p>
                  <w:tbl><w:tr><w:tc><w:p><w:r><w:t>人物</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>埃莉诺</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>
            """.trimIndent(),
        )
        val adapter = NovexDocumentSnapshotExtractor(InMemoryNovexDocumentSnapshotCache())

        val snapshot = requireNotNull(adapter.extract(null, original, null, "setting.docx"))

        assertEquals(NovexDocumentStatus.READY, snapshot.status)
        assertTrue(snapshot.blocks.any { it.kind == NovexDocumentBlockKind.HEADING && it.text == "北境档案" })
        assertTrue(snapshot.blocks.any { it.text.contains("霜港仍在戒严") })
        assertTrue(snapshot.blocks.any { it.text.contains("埃莉诺") })
    }

    @Test
    fun compatibilityAdapterUsesBodyOnlyAndLeavesTheOriginalFileUntouched() {
        val original = temporaryFile("setting.docx", "immutable original bytes")
        val before = original.readBytes()
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                DocumentTextExtractor.Result(
                    text = "# 从 setting.docx 提取的内容\n\n# 北境档案\n\n霜港仍在戒严。",
                    contentText = "# 北境档案\n\n霜港仍在戒严。",
                    formatLabel = "Word 文档",
                    truncated = false,
                )
            },
        )

        val snapshot = requireNotNull(adapter.extract(null, original, null, "setting.docx"))

        assertEquals(before.toList(), original.readBytes().toList())
        assertEquals(
            listOf(NovexDocumentBlockKind.HEADING, NovexDocumentBlockKind.PARAGRAPH),
            snapshot.blocks.map { it.kind },
        )
        assertEquals("北境档案", snapshot.blocks.first().text)
        assertTrue(snapshot.blocks.none { it.text.contains("从 setting.docx 提取") })
    }

    @Test
    fun unchangedHashAndParserVersionReuseTheSnapshotWithoutRunningTheLegacyExtractorAgain() {
        val original = temporaryFile("setting.docx", "same bytes")
        val cache = InMemoryNovexDocumentSnapshotCache()
        var calls = 0
        val extractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
            calls += 1
            DocumentTextExtractor.Result("正文", "Word 文档", false, contentText = "正文")
        }
        val firstAdapter = NovexDocumentSnapshotExtractor(cache, extractor, parserVersion = "compat-v1")
        val upgradedAdapter = NovexDocumentSnapshotExtractor(cache, extractor, parserVersion = "compat-v2")

        val first = requireNotNull(firstAdapter.extract(null, original, null, "setting.docx"))
        val cached = requireNotNull(firstAdapter.extract(null, original, null, "renamed.docx"))
        val reparsed = requireNotNull(upgradedAdapter.extract(null, original, null, "setting.docx"))

        assertEquals(2, calls)
        assertEquals(first.blocks.map { it.id }, cached.blocks.map { it.id })
        assertEquals("renamed.docx", cached.title)
        assertEquals(first.blocks.map { it.id }, reparsed.blocks.map { it.id })
    }

    @Test
    fun legacyDocReturnsAnExplicitUnsupportedSnapshotWithoutCallingTheOldExtractor() {
        val original = temporaryFile("legacy.doc", "legacy bytes")
        var calls = 0
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                calls += 1
                error("旧版文档不应进入现有解析器")
            },
        )

        val snapshot = requireNotNull(
            adapter.extract(null, original, "application/msword", "legacy.doc"),
        )

        assertEquals(0, calls)
        assertEquals(NovexDocumentStatus.UNSUPPORTED, snapshot.status)
        assertTrue(snapshot.blocks.isEmpty())
        assertTrue(snapshot.warnings.any { it.code == "document.legacy_doc_unsupported" })
    }

    @Test
    fun imageOnlyAndDamagedDocumentsReturnRecoverableStatesInsteadOfFakeSuccess() {
        val original = temporaryFile("scan.docx", "scan bytes")
        val ocrAdapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                DocumentTextExtractor.Result(
                    text = "该文档需要 OCR",
                    contentText = "",
                    formatLabel = "Word 文档",
                    truncated = false,
                    requiresOcr = true,
                )
            },
        )
        val damagedAdapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                throw DocumentTextExtractor.ExtractionException("DOCX 降级解析", IllegalStateException("broken"))
            },
        )

        val scan = requireNotNull(ocrAdapter.extract(null, original, null, "scan.docx"))
        val damaged = requireNotNull(damagedAdapter.extract(null, original, null, "scan.docx"))

        assertEquals(NovexDocumentStatus.OCR_REQUIRED, scan.status)
        assertTrue(scan.blocks.isEmpty())
        assertEquals(NovexDocumentStatus.DAMAGED, damaged.status)
        assertTrue(damaged.warnings.any { it.code == "document.parse_failed" })
    }

    @Test
    fun genuinelyEmptyDocumentDoesNotTurnTheCompatibilityExplanationIntoBodyText() {
        val original = temporaryFile("empty.docx", "empty package placeholder")
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                DocumentTextExtractor.Result(
                    text = "该文档没有可提取的可见正文。",
                    contentText = "",
                    formatLabel = "Word 文档",
                    truncated = false,
                    emptyDocument = true,
                )
            },
        )

        val snapshot = requireNotNull(adapter.extract(null, original, null, "empty.docx"))

        assertEquals(NovexDocumentStatus.EMPTY, snapshot.status)
        assertTrue(snapshot.blocks.isEmpty())
        assertTrue(snapshot.warnings.any { it.code == "document.empty" })
    }

    @Test
    fun differentFileContentsProduceDifferentStableDocumentReferences() {
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, file, _, _ ->
                DocumentTextExtractor.Result(file.readText(), "文本文件", false, contentText = file.readText())
            },
        )

        val first = requireNotNull(adapter.extract(null, temporaryFile("a.txt", "甲"), null, "a.txt"))
        val second = requireNotNull(adapter.extract(null, temporaryFile("b.txt", "乙"), null, "b.txt"))

        assertNotEquals(first.ref, second.ref)
        assertNotEquals(first.sha256, second.sha256)
    }

    private fun temporaryFile(name: String, content: String): File {
        val directory = kotlin.io.path.createTempDirectory("novex-document-test").toFile().apply {
            deleteOnExit()
        }
        return File(directory, name).apply {
            writeText(content)
            deleteOnExit()
        }
    }

    private fun makeZip(vararg entries: Pair<String, String>): File {
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
}
