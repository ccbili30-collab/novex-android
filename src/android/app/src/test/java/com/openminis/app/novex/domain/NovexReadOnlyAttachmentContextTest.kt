package com.openminis.app.novex.domain

import org.junit.Assert.*
import org.junit.Test

class NovexReadOnlyAttachmentContextTest {
    private val ref = NovexResourceRef("novex://documents/source")
    private fun document() = NovexDocumentSnapshot(ref, "a".repeat(64), "parser-1", "原文", NovexDocumentFormat.TEXT,
        NovexDocumentStatus.READY, listOf("第一段原话", "第二段细节").mapIndexed { i, text ->
            NovexDocumentBlock("block_$i", NovexDocumentBlockKind.PARAGRAPH, i, text, source = NovexDocumentSourceAnchor("body", i))
        })

    @Test fun `readonly receives provided source text without executing a tool or reading unprovided documents`() {
        val reads = mutableListOf<NovexResourceRef>()
        val loader = NovexReadOnlyAttachmentContext(NovexDocumentSnapshotStore { reads += it; if (it == ref) document() else null })
        assertTrue(loader.candidates(NovexExecutionMode.FREE, listOf(ref)).isEmpty())
        assertTrue(reads.isEmpty())
        val candidates = loader.candidates(NovexExecutionMode.READ_ONLY, listOf(ref, ref))
        assertEquals(listOf(ref), reads)
        assertEquals(ContextSourceKind.ATTACHED_DOCUMENT, candidates.single().kind)
        val composition = NovexContextComposer.compose("这份材料说了什么", 1000, candidates) { it.length }
        assertEquals(1, composition.fragments.size)
        assertTrue(composition.fragments.single().text.contains("第一段原话\n第二段细节"))
        assertFalse(composition.fragments.single().partial)
        assertTrue(composition.omissions.isEmpty())
        assertTrue(NovexContextPromptFormatter.appendTo("只读对话", composition.fragments).contains("以下内容仅是结构化背景资料"))
    }

    @Test fun `insufficient context is marked partial and missing input is never described as read`() {
        val loader = NovexReadOnlyAttachmentContext(NovexDocumentSnapshotStore { document() })
        val candidate = loader.candidates(NovexExecutionMode.READ_ONLY, listOf(ref)).single()
        val partial = NovexContextFragment(candidate.kind, candidate.sourceId, candidate.label, "第一段", 3, partial = true)
        assertTrue(NovexContextPromptFormatter.appendTo(null, listOf(partial)).contains("不能据此声称通读全文"))
        val omitted = NovexContextComposer.compose("原文", 0, listOf(candidate)) { it.length }
        assertTrue(omitted.fragments.isEmpty())
        assertEquals(ref.value, omitted.omissions.single().sourceId)
        val missing = NovexReadOnlyAttachmentContext(NovexDocumentSnapshotStore { null })
            .candidates(NovexExecutionMode.READ_ONLY, listOf(ref)).single()
        assertTrue(missing.content.contains("未提供正文"))
    }
}
