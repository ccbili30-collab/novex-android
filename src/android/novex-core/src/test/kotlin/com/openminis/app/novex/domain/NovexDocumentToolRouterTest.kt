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
