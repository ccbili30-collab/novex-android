package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexLearningNoteEvidenceTest {
    private val ref = NovexResourceRef("novex://documents/" + "a".repeat(64))
    private val source = NovexDocumentSnapshot(ref, "a".repeat(64), "fixture-v1", "门禁甲本",
        NovexDocumentFormat.TEXT, NovexDocumentStatus.READY, listOf(NovexDocumentBlock("block_a",
            NovexDocumentBlockKind.PARAGRAPH, 0, "青庐北门在辰时开放。甲乙两本权威相同，作者尚未裁定冲突。",
            source = NovexDocumentSourceAnchor("body", 0))))
    private val revision = NovexSourceReadEvidence.documentRevision(source)
    private val collection = NovexSourceCollection(NovexResourceRef("novex://source-collections/evidence"),
        NovexResourceRef("novex://conversation-branches/test"), "资料集", listOf(NovexCollectionSource(
            NovexResourceRef("novex://sources/a"), source.title, source.sha256, NovexSourceStatus.READY,
            ref, listOf("block_a"))), 0, 0)
    private val note = NovexLearningNote(NovexResourceRef("novex://learning-notes/a"),
        NovexLearningNoteLevel.SECTION, "门禁笔记", "甲本没有正文，只有空白。",
        listOf(ref), listOf("block_a"), sourceRevisions = mapOf(ref to revision))
    private val state = NovexLearningState(collection, NovexReviewLedger.start(collection), notes = listOf(note))

    @Test fun anIncorrectSavedNoteTravelsWithTheActualSourceWithoutBecomingVerified() {
        val tools = NovexLearningTools(object : NovexLearningPreflightResolver {
            override fun prepare(collectionRef: NovexResourceRef, modelId: String?): NovexLearningPreflightSnapshot? = null
            override fun readState(collectionRef: NovexResourceRef) = state
            override fun readSource(collectionRef: NovexResourceRef, documentRef: NovexResourceRef, revision: String) = source
        })
        val result = tools.learningRead(collection.ref, JSONObject().put("query", "甲本"))
        assertTrue(result.ok)
        val data = JSONObject(result.toJson()).getJSONObject("data")
        assertFalse(data.getBoolean("note_bodies_verified"))
        assertTrue(data.getJSONArray("blocks").toString().contains(note.body))
        val evidence = data.getJSONObject("source_evidence").getJSONArray("excerpts").getJSONObject(0)
        assertEquals(source.blocks.single().text, evidence.getString("text"))
        assertEquals(revision, evidence.getString("source_revision"))
        assertEquals("甲本没有正文，只有空白。", state.notes.single().body)
        assertEquals(0, state.reviewLedger.reviewedBlocks)
    }

    @Test fun sourceExcerptsHonorRecordedRangesAndSharedCharacterBudget() {
        val ranged = note.copy(readRanges = listOf(NovexLearningReadRange(ref, "block_a", 3, 15)))
        val evidence = JSONObject(NovexLearningNoteEvidence.read(state, listOf(ranged), 5) { _, _, _ -> source })
        val excerpt = evidence.getJSONArray("excerpts").getJSONObject(0)
        assertEquals(source.blocks.single().text.substring(3, 8), excerpt.getString("text"))
        assertEquals(5, evidence.getInt("returned_characters"))
        assertTrue(evidence.getBoolean("more_source_available"))
        assertTrue(excerpt.getBoolean("truncated"))
    }

    @Test fun revokedMissingLegacyOrWrongRevisionsNeverFallBackToCurrentSource() {
        val otherRef = NovexResourceRef("novex://documents/" + "b".repeat(64))
        val cases = listOf(
            note.copy(sourceRevisions = emptyMap()),
            note.copy(sourceDocumentRefs = listOf(otherRef), sourceRevisions = mapOf(otherRef to revision)),
        )
        for (invalidNote in cases) {
            val notes = listOf(invalidNote)
            val evidence = JSONObject(NovexLearningNoteEvidence.read(state, notes, 1000) { _, _, _ -> error("Not authorized") })
            assertEquals(0, evidence.getJSONArray("excerpts").length())
            assertTrue(evidence.getJSONArray("unavailable_source_refs").length() > 0)
        }
        for (resolved in listOf(null, source.copy(parserVersion = "new-revision"))) {
            val evidence = JSONObject(NovexLearningNoteEvidence.read(state, listOf(note), 1000) { _, _, _ -> resolved })
            assertEquals(0, evidence.getJSONArray("excerpts").length())
            assertEquals(ref.value, evidence.getJSONArray("unavailable_source_refs").getString(0))
        }
    }
}
