package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocumentToolsTest {
    private val documentRef = NovexResourceRef("novex://documents/sha256-world")
    private val snapshot = NovexDocumentSnapshot(
        ref = documentRef,
        sha256 = "a".repeat(64),
        parserVersion = "fixture-v1",
        title = "云岚书院资料",
        format = NovexDocumentFormat.DOCX,
        status = NovexDocumentStatus.READY,
        blocks = listOf(
            block(kind = NovexDocumentBlockKind.HEADING, text = "第一章", ordinal = 0, headingPath = listOf("第一章"), level = 1),
            block(kind = NovexDocumentBlockKind.PARAGRAPH, text = "不可泄漏的正文甲。", ordinal = 1, headingPath = listOf("第一章")),
            block(kind = NovexDocumentBlockKind.HEADING, text = "第二章", ordinal = 2, headingPath = listOf("第二章"), level = 1),
            block(kind = NovexDocumentBlockKind.PARAGRAPH, text = "不可泄漏的正文乙。", ordinal = 3, headingPath = listOf("第二章")),
            block(kind = NovexDocumentBlockKind.PARAGRAPH, text = "附录不含查询词。", ordinal = 4, headingPath = listOf("附录")),
        ),
    )
    private val tools = NovexDocumentTools(NovexDocumentSnapshotStore { ref ->
        snapshot.takeIf { it.ref == ref }
    })

    @Test
    fun sameSourceAnchorProducesAStableBlockId() {
        val anchor = NovexDocumentSourceAnchor(part = "word/document.xml", ordinal = 7)

        val first = NovexDocumentBlockId.from(snapshot.sha256, anchor)
        val second = NovexDocumentBlockId.from(snapshot.sha256, anchor)
        val different = NovexDocumentBlockId.from(
            snapshot.sha256,
            anchor.copy(ordinal = 8),
        )

        assertEquals(first, second)
        assertNotEquals(first, different)
    }

    @Test
    fun inspectReturnsAnOutlineWithoutLeakingDocumentBody() {
        val result = tools.documentInspect(
            NovexDocumentInspectRequest(documentRef = documentRef, includeOutline = true),
        )

        val encoded = result.toJson()
        val data = JSONObject(encoded).getJSONObject("data")

        assertTrue(result.ok)
        assertEquals(5, data.getInt("block_count"))
        assertEquals(2, data.getJSONArray("outline").length())
        assertFalse(encoded.contains("不可泄漏的正文甲"))
        assertFalse(encoded.contains("不可泄漏的正文乙"))
    }

    @Test
    fun inspectCapsAPathologicalOutlineAndReportsThatItWasTruncated() {
        val largeSnapshot = snapshot.copy(
            blocks = (0 until 120).map { ordinal ->
                block(
                    kind = NovexDocumentBlockKind.HEADING,
                    text = "章节 $ordinal",
                    ordinal = ordinal,
                    headingPath = listOf("章节 $ordinal"),
                    level = 1,
                )
            },
        )
        val largeTools = NovexDocumentTools(NovexDocumentSnapshotStore { largeSnapshot })

        val result = largeTools.documentInspect(
            NovexDocumentInspectRequest(documentRef = documentRef, maxOutlineItems = 40),
        )
        val data = JSONObject(result.toJson()).getJSONObject("data")

        assertEquals(40, data.getJSONArray("outline").length())
        assertTrue(data.getBoolean("outline_truncated"))
    }

    @Test
    fun readCursorContinuesWithoutRepeatingOrSkippingBlocks() {
        val first = tools.documentRead(
            NovexDocumentReadRequest(documentRef = documentRef, maxBlocks = 3),
        )
        val firstData = JSONObject(first.toJson()).getJSONObject("data")
        val second = tools.documentRead(
            NovexDocumentReadRequest(
                documentRef = documentRef,
                cursor = firstData.getString("next_cursor"),
                maxBlocks = 2,
            ),
        )
        val secondData = JSONObject(second.toJson()).getJSONObject("data")
        val ids = buildList {
            for (array in listOf(firstData.getJSONArray("blocks"), secondData.getJSONArray("blocks"))) {
                repeat(array.length()) { index -> add(array.getJSONObject(index).getString("id")) }
            }
        }

        assertEquals(snapshot.blocks.map { it.id }, ids)
        assertFalse(secondData.has("next_cursor"))
    }

    @Test
    fun filteredReadCursorContinuesTheSameQueryInsteadOfFallingBackToFullDocument() {
        val first = tools.documentRead(
            NovexDocumentReadRequest(
                documentRef = documentRef,
                query = "不可泄漏",
                maxBlocks = 1,
            ),
        )
        val firstData = JSONObject(first.toJson()).getJSONObject("data")
        val second = tools.documentRead(
            NovexDocumentReadRequest(
                documentRef = documentRef,
                cursor = firstData.getString("next_cursor"),
                maxBlocks = 2,
            ),
        )
        val secondBlocks = JSONObject(second.toJson()).getJSONObject("data").getJSONArray("blocks")

        assertEquals(1, secondBlocks.length())
        assertEquals("不可泄漏的正文乙。", secondBlocks.getJSONObject(0).getString("text"))
    }

    @Test
    fun missingDocumentReturnsAStableFailureCode() {
        val result = tools.documentInspect(
            NovexDocumentInspectRequest(
                documentRef = NovexResourceRef("novex://documents/missing"),
            ),
        )

        assertFalse(result.ok)
        assertEquals("document.not_found", result.code)
        assertEquals(NovexToolSideEffect.NONE, result.sideEffect)
    }

    private fun block(
        kind: NovexDocumentBlockKind,
        text: String,
        ordinal: Int,
        headingPath: List<String>,
        level: Int? = null,
    ): NovexDocumentBlock {
        val anchor = NovexDocumentSourceAnchor("word/document.xml", ordinal)
        return NovexDocumentBlock(
            id = NovexDocumentBlockId.from("a".repeat(64), anchor),
            kind = kind,
            order = ordinal,
            text = text,
            headingPath = headingPath,
            headingLevel = level,
            source = anchor,
        )
    }
}
