package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexDocumentPromptContractTest {
    @Test
    fun documentCapabilityExposesTwoDeepToolsInsteadOfRawFileOrShellTools() {
        val tools = NovexToolCatalog.forCapabilities(setOf(NovexToolCapability.DOCUMENTS))

        assertEquals(listOf("document_inspect", "document_read"), tools.map { it.name })
        assertTrue(tools.all { it.risk == NovexToolRisk.READ_ONLY })
        assertFalse(tools.any { it.name == "file_read" || it.name == "shell_execute" })
    }

    @Test
    fun attachmentReceiptContainsOutlineAndReferenceButNeverTheLargeDocumentBody() {
        val sha = "b".repeat(64)
        val secretBody = "长篇正文唯一标记" + "甲".repeat(120_000)
        val snapshot = NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = "西幻人生模拟器资料",
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = listOf(
                block(sha, NovexDocumentBlockKind.HEADING, "核心规则", 0, level = 1),
                block(sha, NovexDocumentBlockKind.PARAGRAPH, secretBody, 1),
            ),
        )

        val prompt = NovexDocumentPromptReceipt.build(listOf(snapshot))

        assertTrue(prompt.contains("novex://documents/$sha"))
        assertTrue(prompt.contains("核心规则"))
        assertTrue(prompt.contains("document_inspect"))
        assertTrue(prompt.contains("document_read"))
        assertFalse(prompt.contains("长篇正文唯一标记"))
        assertFalse(prompt.contains("/var/minis/"))
        assertTrue(prompt.length < 4_000)
    }

    private fun block(
        sha: String,
        kind: NovexDocumentBlockKind,
        text: String,
        ordinal: Int,
        level: Int? = null,
    ): NovexDocumentBlock {
        val anchor = NovexDocumentSourceAnchor("word/document.xml", ordinal)
        return NovexDocumentBlock(
            id = NovexDocumentBlockId.from(sha, anchor),
            kind = kind,
            order = ordinal,
            text = text,
            headingPath = if (kind == NovexDocumentBlockKind.HEADING) listOf(text) else listOf("核心规则"),
            headingLevel = level,
            source = anchor,
        )
    }
}
