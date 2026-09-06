package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocumentToolRouterTest {
    private val sha = "e".repeat(64)
    private val ref = NovexResourceRef("novex://documents/$sha")
    private val snapshot = NovexDocumentSnapshot(
        ref = ref,
        sha256 = sha,
        parserVersion = "fixture-v1",
        title = "世界资料.docx",
        format = NovexDocumentFormat.DOCX,
        status = NovexDocumentStatus.READY,
        blocks = listOf(
            block(NovexDocumentBlockKind.HEADING, "世界规则", 0, level = 1),
            block(NovexDocumentBlockKind.PARAGRAPH, "魔法会消耗以太。", 1),
        ),
        warnings = listOf(NovexDocumentWarning("document.fixture", "测试警告")),
    )
    private val router = NovexDocumentToolRouter(
        NovexDocumentTools(NovexDocumentSnapshotStore { requested -> snapshot.takeIf { it.ref == requested } }),
    )

    @Test
    fun shortLinesAreReadAsBudgetedPassagesRatherThanTwentyLinePages() {
        val lines = (0 until 1154).map { if (it % 4 == 0) "↓" else "第 $it 条：玩家不是世界的中心。" }
        val fragmented = snapshot.copy(blocks = lines.mapIndexed { index, text ->
            block(NovexDocumentBlockKind.PARAGRAPH, text, index).copy(mediaRef = null)
        })
        val reader = NovexDocumentToolRouter(NovexDocumentTools(NovexDocumentSnapshotStore { fragmented }))
        val result = reader.execute("document_read", JSONObject().put("document_ref", ref.value).toString())
        val data = JSONObject(result.toJson()).getJSONObject("data")
        assertFalse(data.getBoolean("truncated"))
        val passages = data.getJSONArray("passages")
        val text = (0 until passages.length()).joinToString("\n") { passages.getJSONObject(it).getString("text") }
        assertEquals(lines.joinToString("\n"), text)
        assertTrue("Source metadata must not cost more than the source", result.toJson().length < text.length * 2)
        assertEquals(1154, data.getInt("source_blocks_read"))
    }

    @Test
    fun explicitPositionAndReturnedCursorReadExactlyTheRequestedRange() {
        val many = snapshot.copy(blocks = (0 until 160).map {
            block(NovexDocumentBlockKind.PARAGRAPH, "资料第 $it 块", it)
        })
        val reader = NovexDocumentToolRouter(NovexDocumentTools(NovexDocumentSnapshotStore { many }))
        val first = reader.execute("document_read", JSONObject().put("document_ref", ref.value)
            .put("first_block", 100).put("last_block", 104).put("max_blocks", 2).put("view", "blocks").toString())
        val firstData = JSONObject(first.toJson()).getJSONObject("data")
        assertEquals("资料第 99 块", firstData.getJSONArray("blocks").getJSONObject(0).getString("text"))
        assertEquals(102, firstData.getJSONObject("next_position").getInt("block"))
        val rest = reader.execute("document_read", JSONObject().put("document_ref", ref.value)
            .put("cursor", firstData.getString("next_cursor")).put("view", "blocks").toString())
        val restData = JSONObject(rest.toJson()).getJSONObject("data")
        assertEquals(3, restData.getJSONArray("blocks").length())
        assertEquals("资料第 103 块", restData.getJSONArray("blocks").getJSONObject(2).getString("text"))
        assertFalse(restData.getBoolean("truncated"))
    }

    @Test
    fun cursorAndLocatorFailuresExplainHowToRecoverWithoutGuessingACursor() {
        val badCursor = router.execute("document_read", JSONObject().put("document_ref", ref.value)
            .put("cursor", "broken").toString())
        assertEquals("document.invalid_cursor", badCursor.code)
        assertTrue(badCursor.toJson().contains("next_cursor"))
        assertTrue(badCursor.toJson().contains("first_block"))
        val conflict = router.execute("document_read", JSONObject().put("document_ref", ref.value)
            .put("cursor", "broken").put("query", "规则").toString())
        assertEquals("tool.invalid_arguments", conflict.code)
        assertTrue(conflict.summary.contains("query"))
        assertTrue(conflict.summary.contains("cursor"))
    }

    @Test
    fun compactReadAcrossLongBlocksKeepsEveryCharacterAndSkipsNoSelectedSource() {
        val many = snapshot.copy(blocks = (0 until 7).map {
            block(NovexDocumentBlockKind.PARAGRAPH, "资料-$it-" + "内容".repeat(80), it).copy(mediaRef = null)
        })
        val reader = NovexDocumentToolRouter(NovexDocumentTools(NovexDocumentSnapshotStore { many }))
        val reconstructed = mutableMapOf<Int, StringBuilder>()
        var cursor: String? = null
        var calls = 0
        do {
            val args = JSONObject().put("document_ref", ref.value).put("max_chars", 70)
            if (cursor != null) args.put("cursor", cursor)
            val data = JSONObject(reader.execute("document_read", args.toString()).toJson()).getJSONObject("data")
            val passages = data.getJSONArray("passages")
            repeat(passages.length()) {
                val passage = passages.getJSONObject(it)
                val firstBlock = passage.getInt("first_block")
                val parts = passage.getString("text").split('\n')
                assertEquals(passage.getInt("last_block") - firstBlock + 1, parts.size)
                parts.forEachIndexed { index, part ->
                    reconstructed.getOrPut(firstBlock + index) { StringBuilder() }.append(part)
                }
            }
            cursor = data.optString("next_cursor").ifEmpty { null }
            assertTrue(++calls < 100)
        } while (cursor != null)
        assertEquals(many.blocks.map { it.text }, reconstructed.toSortedMap().values.map { it.toString() })
    }

    @Test
    fun unstyledChapterTitlesProvideTraceableRangesWithoutRewritingTheSource() {
        val original = snapshot.copy(blocks = listOf(
            block(NovexDocumentBlockKind.PARAGRAPH, "第一章 · 核心规则", 0),
            block(NovexDocumentBlockKind.PARAGRAPH, "第一章只是设定的开端，不应被当成目录。", 1),
            block(NovexDocumentBlockKind.PARAGRAPH, "↓", 2),
            block(NovexDocumentBlockKind.PARAGRAPH, "第二章：人物成长", 3),
            block(NovexDocumentBlockKind.PARAGRAPH, "不要因为玩家停留而暂停世界。", 4),
        ).map { it.copy(headingPath = emptyList(), mediaRef = null) })
        val reader = NovexDocumentToolRouter(NovexDocumentTools(NovexDocumentSnapshotStore { original }))
        val data = org.json.JSONObject(reader.execute("document_inspect", """{"document_ref":"${ref.value}"}""").toJson()).getJSONObject("data")
        val outline = data.getJSONArray("outline")
        assertEquals(2, outline.length())
        val first = outline.getJSONObject(0)
        assertEquals("第一章 · 核心规则", first.getString("title"))
        assertTrue(first.getBoolean("inferred"))
        assertEquals(1, first.getInt("first_block"))
        assertEquals(3, first.getInt("last_block"))
        val read = reader.execute("document_read", org.json.JSONObject().put("document_ref", ref.value)
            .put("heading_path", first.getJSONArray("heading_path")).toString())
        assertTrue(read.toJson().contains("第一章只是设定的开端"))
        assertFalse(read.toJson().contains("不要因为玩家"))
        assertTrue(original.blocks.all { it.kind == NovexDocumentBlockKind.PARAGRAPH && it.headingPath.isEmpty() })
        val receipt = NovexDocumentPromptReceipt.build(listOf(original))
        assertTrue(receipt.contains("第一章 · 核心规则"))
        assertTrue(receipt.contains("inferred=\"true\""))
        assertFalse(receipt.contains("不要因为玩家"))
    }

    @Test
    fun explicitOutlineWinsAndOrdinaryProseIsNotGuessedIntoAChapter() {
        val unstyledProse = snapshot.copy(blocks = listOf(
            block(NovexDocumentBlockKind.PARAGRAPH, "第一章是总纲。", 0),
            block(NovexDocumentBlockKind.PARAGRAPH, "1. 玩家输入动作", 1),
            block(NovexDocumentBlockKind.PARAGRAPH, "↓", 2),
        ))
        assertTrue(NovexDocumentOutline.entries(unstyledProse).isEmpty())
        val mixed = snapshot.copy(blocks = snapshot.blocks +
            block(NovexDocumentBlockKind.PARAGRAPH, "第二章：这是引用的别的作品标题", 2))
        val outline = NovexDocumentOutline.entries(mixed)
        assertEquals(listOf("世界规则"), outline.map { it.title })
        assertFalse(outline.single().inferred)
    }

    @Test
    fun modelFacingRouterDispatchesBothDocumentToolsThroughStableJsonArguments() {
        val inspected = router.execute(
            "document_inspect",
            JSONObject().put("document_ref", ref.value).put("max_depth", 2).toString(),
        )
        val read = router.execute(
            "document_read",
            JSONObject().put("document_ref", ref.value).put("heading_path", listOf("世界规则")).toString(),
        )

        assertTrue(inspected.ok)
        assertEquals("document.ready", inspected.code)
        assertTrue(read.ok)
        assertEquals("document.read", read.code)
        assertTrue(read.toJson().contains("魔法会消耗以太"))
    }

    @Test
    fun malformedArgumentsAndUnknownToolsReturnStableErrorsWithoutInternalExceptions() {
        val malformed = router.execute("document_read", "{not-json")
        val missingRef = router.execute("document_inspect", "{}")
        val unknown = router.execute("file_read", "{}")

        assertFalse(malformed.ok)
        assertEquals("tool.invalid_arguments", malformed.code)
        assertFalse(missingRef.ok)
        assertEquals("tool.invalid_arguments", missingRef.code)
        assertFalse(unknown.ok)
        assertEquals("tool.unknown", unknown.code)
        assertEquals(listOf("document_inspect", "document_read"), unknown.allowedValues)
        assertFalse(malformed.toJson().contains("JSONException"))
    }

    @Test
    fun snapshotJsonCodecRoundTripsEveryPublicFieldForRestartSafeStorage() {
        val encoded = NovexDocumentSnapshotJsonCodec.encode(snapshot)
        val restored = NovexDocumentSnapshotJsonCodec.decode(encoded)

        assertEquals(snapshot, restored)
        assertFalse(encoded.contains("/var/minis/"))
    }

    private fun block(
        kind: NovexDocumentBlockKind,
        text: String,
        ordinal: Int,
        level: Int? = null,
    ): NovexDocumentBlock {
        val source = NovexDocumentSourceAnchor(
            part = "word/document.xml",
            ordinal = ordinal,
            page = ordinal + 1,
            detail = "paragraph:${ordinal + 1}",
        )
        return NovexDocumentBlock(
            id = NovexDocumentBlockId.from(sha, source),
            kind = kind,
            order = ordinal,
            text = text,
            headingPath = listOf("世界规则"),
            headingLevel = level,
            source = source,
            mediaRef = if (ordinal == 1) NovexResourceRef("novex://media/map-1") else null,
        )
    }
}
