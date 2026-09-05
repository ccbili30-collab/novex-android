package com.openminis.app.tools

import com.openminis.app.novex.domain.NovexDocumentBlock
import com.openminis.app.novex.domain.NovexDocumentBlockId
import com.openminis.app.novex.domain.NovexDocumentBlockKind
import com.openminis.app.novex.domain.NovexDocumentFormat
import com.openminis.app.novex.domain.NovexDocumentSnapshot
import com.openminis.app.novex.domain.NovexDocumentSnapshotStore
import com.openminis.app.novex.domain.NovexDocumentSourceAnchor
import com.openminis.app.novex.domain.NovexDocumentStatus
import com.openminis.app.novex.domain.NovexResourceRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocumentAgentToolsTest {
    private val sha = "b".repeat(64)
    private val ref = NovexResourceRef("novex://documents/$sha")
    private val store = NovexDocumentSnapshotStore { requested -> snapshot.takeIf { it.ref == requested } }
    private val tools = NovexDocumentAgentTools(store) { requested -> requested == ref }

    @Test
    fun `document tools are capability gated in the model catalog`() {
        val withoutDocuments = AgentTools.makeAgentTools(documentsAvailable = false)
        val withDocuments = AgentTools.makeAgentTools(documentsAvailable = true)

        assertFalse(withoutDocuments.any { it.name.startsWith("document_") })
        assertEquals(
            listOf("document_inspect", "document_read"),
            withDocuments.filter { it.name.startsWith("document_") }.map { it.name },
        )
    }

    @Test
    fun `document read schema exposes real arrays instead of encoded strings`() {
        val definition = tools.definitions().single { it.name == "document_read" }
        val parameters = definition.toOpenAIJson()
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertEquals("array", parameters.getJSONObject("block_ids").getString("type"))
        assertEquals(
            "string",
            parameters.getJSONObject("block_ids").getJSONObject("items").getString("type"),
        )
        assertEquals("object", parameters.getJSONObject("page_range").getString("type"))
    }

    @Test
    fun `agent adapter executes bounded reads and preserves the standard result envelope`() {
        val result = tools.execute(
            "document_read",
            JSONObject().put("document_ref", ref.value).put("query", "以太").toString(),
        )

        assertTrue(result.success)
        assertEquals("读取文档", result.toolTitle)
        assertTrue(result.output.contains("document.read"))
        assertTrue(result.output.contains("魔法消耗以太"))
        assertFalse(result.output.contains("/var/minis/"))
    }

    @Test
    fun `agent adapter cannot read a document outside the active conversation`() {
        val result = NovexDocumentAgentTools(store) { false }.execute(
            "document_inspect",
            JSONObject().put("document_ref", ref.value).toString(),
        )

        assertFalse(result.success)
        assertTrue(result.output.contains("document.not_found"))
        assertFalse(result.output.contains("魔法消耗以太"))
    }

    private val snapshot: NovexDocumentSnapshot
        get() {
            val source = NovexDocumentSourceAnchor("word/document.xml", 0)
            return NovexDocumentSnapshot(
                ref = ref,
                sha256 = sha,
                parserVersion = "fixture-v1",
                title = "世界资料",
                format = NovexDocumentFormat.DOCX,
                status = NovexDocumentStatus.READY,
                blocks = listOf(
                    NovexDocumentBlock(
                        id = NovexDocumentBlockId.from(sha, source),
                        kind = NovexDocumentBlockKind.PARAGRAPH,
                        order = 0,
                        text = "魔法消耗以太。",
                        source = source,
                    ),
                ),
            )
        }
}
