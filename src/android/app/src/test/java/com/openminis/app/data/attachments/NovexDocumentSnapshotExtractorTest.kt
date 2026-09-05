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
    fun streamingDocxPathProducesStructuredBlocksWithoutCallingTheLegacyExtractor() {
        val original = makeZip(
            "word/styles.xml" to """
                <w:styles xmlns:w="urn:test"><w:style w:type="paragraph" w:styleId="Heading1"/></w:styles>
            """.trimIndent(),
            "word/document.xml" to """
                <w:document xmlns:w="urn:test"><w:body>
                  <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>北境档案</w:t></w:r></w:p>
                  <w:tbl><w:tr><w:tc><w:p><w:r><w:t>人物</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>埃莉诺</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>
            """.trimIndent(),
        )
        var legacyCalls = 0
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                legacyCalls += 1
                error("结构化解析成功时不应调用旧解析器")
            },
        )

        val snapshot = requireNotNull(adapter.extract(null, original, null, "setting.docx"))

        assertEquals(0, legacyCalls)
        assertEquals(
            listOf(NovexDocumentBlockKind.HEADING, NovexDocumentBlockKind.TABLE),
            snapshot.blocks.map { it.kind },
        )
        assertEquals("人物\t埃莉诺", snapshot.blocks.last().text)
        assertTrue(snapshot.parserVersion.startsWith("docx-streaming-"))
    }

    @Test
    fun streamingDocxPathReadsTheExistingProducerFixturesWithoutPoiFallback() {
        val expectations = mapOf(
            "google-docs-sample.docx" to listOf("The Canons of Rhetoric", "Correctness of Style"),
            "libreoffice-comment.docx" to listOf("This is the first line"),
            "microsoft-word-header-footer-notes.docx" to listOf("I am some simple header text here", "Footer Middle"),
            "microsoft-word-footnotes.docx" to listOf("snoska"),
            "microsoft-word-endnotes.docx" to listOf("XXX"),
            "microsoft-word-numbered-lists.docx" to listOf("Entry #2, with children", "2-a"),
            "microsoft-word-table.docx" to listOf("Loren", "Ipsum"),
            "microsoft-word-textboxes.docx" to listOf("Floating text box", "An ellipse with text inside"),
            "microsoft-word-revisions.docx" to listOf("This is a filler sentence.", "Will this sentence be duplicated ADDED STUFF?"),
        )
        var legacyCalls = 0
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                legacyCalls += 1
                error("现有真实夹具不应退回 POI")
            },
        )

        expectations.forEach { (name, expectedTexts) ->
            val snapshot = requireNotNull(adapter.extract(null, fixture(name), null, name))
            val text = snapshot.blocks.joinToString("\n") { it.text }
            expectedTexts.forEach { expected ->
                assertTrue("$name 缺少：$expected", text.contains(expected))
            }
        }

        assertEquals(0, legacyCalls)
    }

    @Test
    fun streamingDocxPathKeepsImageOnlyFixtureAsAnOcrReadySnapshot() {
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                error("图片型真实夹具不应退回 POI")
            },
        )

        val snapshot = requireNotNull(adapter.extract(
            null,
            fixture("microsoft-word-image-only.docx"),
            null,
            "scan.docx",
        ))

        assertEquals(NovexDocumentStatus.OCR_REQUIRED, snapshot.status)
        assertTrue(snapshot.blocks.any { it.kind == NovexDocumentBlockKind.IMAGE && it.mediaRef != null })
        assertTrue(snapshot.warnings.any { it.code == "document.ocr_required" })
    }

    @Test
    fun streamingDocxPathKeepsALargeStructuredDocumentWithoutFlatTextTruncation() {
        val payload = "这是用于长文压力、结构顺序和完整读取验证的正文内容。".repeat(4)
        val paragraphs = (1..10_000).joinToString("") { index ->
            "<w:p><w:r><w:t>第 $index 段：$payload</w:t></w:r></w:p>"
        }
        val original = makeZip(
            "word/document.xml" to """
                <w:document xmlns:w="urn:test"><w:body>$paragraphs</w:body></w:document>
            """.trimIndent(),
        )
        val adapter = NovexDocumentSnapshotExtractor(
            cache = InMemoryNovexDocumentSnapshotCache(),
            legacyExtractor = NovexLegacyDocumentExtractor { _, _, _, _ ->
                error("长文结构化解析不应调用旧解析器")
            },
        )

        val snapshot = requireNotNull(adapter.extract(null, original, null, "long.docx"))

        assertEquals(10_000, snapshot.blocks.size)
        assertEquals("第 1 段：$payload", snapshot.blocks.first().text)
        assertEquals("第 10000 段：$payload", snapshot.blocks.last().text)
        assertTrue(snapshot.blocks.sumOf { it.text.length } > 1_000_000)
        assertEquals(NovexDocumentStatus.READY, snapshot.status)
    }

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

    private fun fixture(name: String): File = File(
        requireNotNull(javaClass.classLoader?.getResource("docx/$name")) { "Missing fixture: $name" }.toURI(),
    )
}
