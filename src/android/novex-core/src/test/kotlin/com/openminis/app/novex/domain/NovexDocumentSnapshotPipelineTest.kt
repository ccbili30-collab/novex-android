package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocumentSnapshotPipelineTest {
    private val sha = "c".repeat(64)
    private val descriptor = NovexDocumentDescriptor(
        ref = NovexResourceRef("novex://documents/$sha"),
        sha256 = sha,
        title = "北境档案.docx",
        format = NovexDocumentFormat.DOCX,
        parserVersion = "compat-docx-v1",
    )

    @Test
    fun compatibilityTextBecomesOrderedSemanticBlocksWithStableSourceAnchors() {
        val pipeline = NovexDocumentSnapshotPipeline(InMemoryNovexDocumentSnapshotCache())
        val text = """
            # 北境档案

            霜港仍在戒严。
            城门只在正午开启。

            - 埃莉诺
            - 塞勒斯

            姓名	身份
            埃莉诺	守夜人

            ## 密令

            不得向议会泄漏。
        """.trimIndent()

        val snapshot = pipeline.resolve(descriptor) {
            NovexCompatibilityDocument(text = text)
        }

        assertEquals(
            listOf(
                NovexDocumentBlockKind.HEADING,
                NovexDocumentBlockKind.PARAGRAPH,
                NovexDocumentBlockKind.LIST_ITEM,
                NovexDocumentBlockKind.LIST_ITEM,
                NovexDocumentBlockKind.TABLE,
                NovexDocumentBlockKind.HEADING,
                NovexDocumentBlockKind.PARAGRAPH,
            ),
            snapshot.blocks.map { it.kind },
        )
        assertEquals("霜港仍在戒严。\n城门只在正午开启。", snapshot.blocks[1].text)
        assertEquals(listOf("北境档案"), snapshot.blocks[4].headingPath)
        assertEquals(listOf("北境档案", "密令"), snapshot.blocks.last().headingPath)
        assertEquals(snapshot.blocks.indices.toList(), snapshot.blocks.map { it.order })

        val repeated = pipeline.build(descriptor, NovexCompatibilityDocument(text = text))
        assertEquals(snapshot.blocks.map { it.id }, repeated.blocks.map { it.id })
        assertTrue(snapshot.blocks.all { it.source.part == "compatibility-text" })
    }

    @Test
    fun cacheUsesFileHashAndParserVersionAndDoesNotReparseAnUnchangedDocument() {
        val cache = InMemoryNovexDocumentSnapshotCache()
        val pipeline = NovexDocumentSnapshotPipeline(cache)
        var extractionCount = 0
        fun resolve(input: NovexDocumentDescriptor) = pipeline.resolve(input) {
            extractionCount += 1
            NovexCompatibilityDocument("正文 $extractionCount")
        }

        val first = resolve(descriptor)
        val cached = resolve(descriptor.copy(title = "显示名称可以变化.docx"))
        val newParser = resolve(descriptor.copy(parserVersion = "compat-docx-v2"))
        val newContent = resolve(
            descriptor.copy(
                ref = NovexResourceRef("novex://documents/${"d".repeat(64)}"),
                sha256 = "d".repeat(64),
            ),
        )

        assertEquals(3, extractionCount)
        assertEquals(first.blocks.map { it.id }, cached.blocks.map { it.id })
        assertEquals("显示名称可以变化.docx", cached.title)
        assertEquals(first.blocks.map { it.id }, newParser.blocks.map { it.id })
        assertNotEquals(first.blocks.map { it.id }, newContent.blocks.map { it.id })
    }

    @Test
    fun unsupportedLegacyDocAndImageOnlyDocumentsExposeExplicitStatesWithoutFakeBodyText() {
        val pipeline = NovexDocumentSnapshotPipeline(InMemoryNovexDocumentSnapshotCache())
        val legacy = pipeline.build(
            descriptor.copy(format = NovexDocumentFormat.DOC),
            NovexCompatibilityDocument(
                text = "",
                status = NovexDocumentStatus.UNSUPPORTED,
                warnings = listOf(
                    NovexDocumentWarning("document.legacy_doc_unsupported", "旧版 Word 文档尚未支持"),
                ),
            ),
        )
        val scan = pipeline.build(
            descriptor,
            NovexCompatibilityDocument(
                text = "",
                status = NovexDocumentStatus.OCR_REQUIRED,
                warnings = listOf(
                    NovexDocumentWarning("document.ocr_required", "文档只有图片，需要光学字符识别"),
                ),
            ),
        )

        assertEquals(NovexDocumentStatus.UNSUPPORTED, legacy.status)
        assertEquals(NovexDocumentStatus.OCR_REQUIRED, scan.status)
        assertTrue(legacy.blocks.isEmpty())
        assertTrue(scan.blocks.isEmpty())
    }

    @Test
    fun skippedHeadingLevelsNeverInventSourceContent() {
        val pipeline = NovexDocumentSnapshotPipeline(InMemoryNovexDocumentSnapshotCache())

        val snapshot = pipeline.build(
            descriptor,
            NovexCompatibilityDocument(
                text = """
                    ### 直接出现的三级标题

                    原文没有一级或二级标题。
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf("直接出现的三级标题"),
            snapshot.blocks.first().headingPath,
        )
        assertTrue(snapshot.blocks.none { block ->
            block.headingPath.any { heading -> heading.startsWith("未命名层级") }
        })
    }
}
