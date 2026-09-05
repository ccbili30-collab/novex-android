package com.openminis.app.ui.chat

import com.openminis.app.data.attachments.stripAgentAttachmentMetadata
import com.openminis.app.novex.domain.NovexDocumentBlock
import com.openminis.app.novex.domain.NovexDocumentBlockId
import com.openminis.app.novex.domain.NovexDocumentBlockKind
import com.openminis.app.novex.domain.NovexDocumentFormat
import com.openminis.app.novex.domain.NovexDocumentSnapshot
import com.openminis.app.novex.domain.NovexDocumentSourceAnchor
import com.openminis.app.novex.domain.NovexDocumentStatus
import com.openminis.app.novex.domain.NovexResourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPromptMetadataTest {
    private val sha = "a".repeat(64)
    private val ref = NovexResourceRef("novex://documents/$sha")

    @Test
    fun `document attachment becomes a bounded receipt without body or device path`() {
        val bodyMarker = "不可直接注入的正文" + "字".repeat(120_000)
        val prompt = buildUserAttachedFilesPrompt(
            listOf(
                UserAttachedFilePromptMeta(
                    linuxPath = "/var/minis/attachments/uploads/world&rules.docx",
                    size = 500_000,
                    modifiedIso = "2026-08-31T00:00:00Z",
                    documentSnapshot = snapshot(bodyMarker),
                ),
            ),
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains(ref.value))
        assertTrue(prompt.contains("世界规则"))
        assertTrue(prompt.contains("document_inspect"))
        assertTrue(prompt.contains("document_read"))
        assertFalse(prompt.contains(bodyMarker.take(12)))
        assertFalse(prompt.contains("/var/minis/"))
        assertTrue(prompt.length < 4_000)
    }

    @Test
    fun `persisted receipt restores only validated Novex document references`() {
        val prompt = buildUserAttachedFilesPrompt(
            listOf(
                UserAttachedFilePromptMeta(
                    linuxPath = "/var/minis/attachments/uploads/world.docx",
                    size = 10,
                    modifiedIso = "2026-08-31T00:00:00Z",
                    documentSnapshot = snapshot("正文"),
                ),
            ),
        ).orEmpty() + " ref=\"novex://documents/not-a-hash\""

        assertEquals(setOf(ref.value), novexDocumentRefsInPrompt(prompt))
    }

    @Test
    fun `source collection receipt persists branch capability without exposing source bodies`() {
        val collectionRef = NovexResourceRef("novex://source-collections/${"b".repeat(64)}")
        val prompt = buildUserAttachedFilesPrompt(
            metas = listOf(
                UserAttachedFilePromptMeta(
                    linuxPath = "/var/minis/attachments/uploads/world.docx",
                    size = 10,
                    modifiedIso = "2026-08-31T00:00:00Z",
                    documentSnapshot = snapshot("不得泄露的完整正文"),
                ),
            ),
            sourceCollectionRef = collectionRef,
            sourceCount = 1,
        ).orEmpty()

        assertTrue(prompt.contains("<novex-source-collection"))
        assertTrue(prompt.contains(collectionRef.value))
        assertFalse(prompt.contains("不得泄露的完整正文"))
        assertEquals(setOf(collectionRef.value), novexSourceCollectionRefsInPrompt(prompt))
        assertEquals("", stripAgentAttachmentMetadata(prompt))
    }

    @Test
    fun `source collection recovery rejects untrusted references`() {
        val valid = "novex://source-collections/${"c".repeat(64)}"
        val prompt = """
            <novex-source-collection ref="$valid" sources="3" />
            <novex-source-collection ref="novex://source-collections/not-a-hash" sources="2" />
            <novex-source-collection ref="file:///private/data" sources="1" />
        """.trimIndent()

        assertEquals(setOf(valid), novexSourceCollectionRefsInPrompt(prompt))
    }

    @Test
    fun `ordinary non-document attachment keeps only its inventory metadata`() {
        val prompt = buildUserAttachedFilesPrompt(
            listOf(
                UserAttachedFilePromptMeta(
                    linuxPath = "/var/minis/attachments/uploads/archive.bin",
                    size = 42,
                    modifiedIso = "2026-08-31T00:00:00Z",
                ),
            ),
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("archive.bin"))
        assertFalse(prompt.contains("extracted_text"))
    }

    @Test
    fun `all attachment metadata envelopes are stripped from visible text`() {
        val oldInventory = "<user-attached-files>\n<file path=\"hidden\" />\n</user-attached-files>"
        val documentReceipt = "<novex-document-receipts>\n<document ref=\"${ref.value}\" />\n</novex-document-receipts>"

        assertEquals("正文前\n\n正文后", stripAgentAttachmentMetadata("正文前\n$oldInventory\n正文后"))
        assertEquals("正文前\n\n正文后", stripAgentAttachmentMetadata("正文前\n$documentReceipt\n正文后"))
        assertEquals("", stripAgentAttachmentMetadata(documentReceipt))
    }

    private fun snapshot(body: String): NovexDocumentSnapshot {
        val headingSource = NovexDocumentSourceAnchor("compatibility-text", 0)
        val bodySource = NovexDocumentSourceAnchor("compatibility-text", 1)
        return NovexDocumentSnapshot(
            ref = ref,
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = "world&rules.docx",
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = listOf(
                NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha, headingSource),
                    kind = NovexDocumentBlockKind.HEADING,
                    order = 0,
                    text = "世界规则",
                    headingPath = listOf("世界规则"),
                    headingLevel = 1,
                    source = headingSource,
                ),
                NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha, bodySource),
                    kind = NovexDocumentBlockKind.PARAGRAPH,
                    order = 1,
                    text = body,
                    headingPath = listOf("世界规则"),
                    source = bodySource,
                ),
            ),
        )
    }
}
