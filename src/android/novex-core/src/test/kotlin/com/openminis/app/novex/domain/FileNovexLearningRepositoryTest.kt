package com.openminis.app.novex.domain

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNovexLearningRepositoryTest {
    @Test
    fun confirmationPreflightPersistsBeforeAnyLearningTaskStarts() {
        val directory = Files.createTempDirectory("novex-learning-preflight").toFile()
        try {
            val collection = collection()
            val preflight = preflight(collection)
            val state = NovexLearningState(
                collection = collection,
                reviewLedger = NovexReviewLedger.start(collection),
                preflight = preflight,
            )

            FileNovexLearningRepository(directory).save(state)
            val restored = FileNovexLearningRepository(directory).find(collection.ref)

            assertEquals(preflight.id, restored?.preflight?.id)
            assertEquals(NovexLearningTaskStatus.NOT_STARTED, restored?.preflight?.taskStatus)
            assertEquals(preflight.sourcePlanFingerprint, restored?.preflight?.sourcePlanFingerprint)
            assertEquals(null, restored?.task)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun collectionCoverageAndAnchoredNotesResumeAfterRepositoryRecreation() {
        val directory = Files.createTempDirectory("novex-learning").toFile()
        try {
            val collection = collection()
            val documentRef = requireNotNull(collection.sources.first().documentRef)
            val firstBlock = collection.sources.first().blockIds.first()
            val state = NovexLearningState(
                collection = collection,
                reviewLedger = NovexReviewLedger.start(collection).recordRead(
                    documentRef = documentRef,
                    blockIds = listOf(firstBlock),
                    mode = NovexDocumentReadMode.FULL_REVIEW,
                ),
                notes = listOf(
                    NovexLearningNote(
                        ref = NovexResourceRef("novex://learning-notes/note-1"),
                        level = NovexLearningNoteLevel.BLOCK,
                        title = "第一节笔记",
                        body = "主角来自北境。",
                        sourceDocumentRefs = listOf(documentRef),
                        sourceBlockIds = listOf(firstBlock),
                    ),
                ),
                task = pausedTask(collection),
            )
            FileNovexLearningRepository(directory).save(state)

            val restored = FileNovexLearningRepository(directory).find(collection.ref)

            assertNotNull(restored)
            assertEquals(1, restored!!.reviewLedger.reviewedBlocks)
            assertEquals(2, restored.reviewLedger.totalReadableBlocks)
            assertEquals("第一节笔记", restored.notes.single().title)
            assertEquals(listOf(firstBlock), restored.notes.single().sourceBlockIds)
            assertTrue(restored.collection.scopeRef.value.contains("conversation-branches"))
            assertEquals(NovexLearningTaskStatus.PAUSED, restored.task!!.status)
            assertEquals(12_000, restored.task!!.usage.usedInputTokens)
            assertEquals(NovexLearningTaskStatus.INDEXING, restored.task!!.resume().status)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun pausedTask(collection: NovexSourceCollection): NovexLearningTaskState {
        val preflight = preflight(collection)
        val confirmation = NovexLearningConfirmation(
            preflightId = preflight.id,
            modelId = preflight.modelId,
            sourceRefs = preflight.sourceRefs,
            maxInputTokens = 120_000,
            maxOutputTokens = 12_000,
            confirmedAtMillis = 1_000,
        )
        return NovexLearningCoordinator().start(preflight, confirmation)
            .recordUsage(12_000, 1_200)
            .pause()
    }

    private fun preflight(collection: NovexSourceCollection): NovexLearningPreflightSnapshot {
        val documentRef = requireNotNull(collection.sources.first().documentRef)
        return NovexLearningPreflight.prepare(
            NovexLearningPreflightRequest(
                collectionRef = collection.ref,
                sources = listOf(
                    NovexLearningSourceEstimate(
                        ref = documentRef,
                        estimatedTokens = 90_000,
                    ),
                ),
                modelId = "model-a",
                modelProviderName = "测试模型提供商",
                effectiveContextTokens = 200_000,
                occupiedContextTokens = 20_000,
                directReadBudgetTokens = 12_000,
                proposedBudget = NovexLearningTokenBudget(120_000, 12_000),
                sourcePlanFingerprint = "wiki-plan-v1",
            ),
        )
    }

    private fun collection(): NovexSourceCollection {
        val sha = "e".repeat(64)
        val blocks = listOf("第一节", "第二节").mapIndexed { index, text ->
            val source = NovexDocumentSourceAnchor("word/document.xml", index)
            NovexDocumentBlock(
                id = NovexDocumentBlockId.from(sha, source),
                kind = NovexDocumentBlockKind.PARAGRAPH,
                order = index,
                text = text,
                source = source,
            )
        }
        val document = NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = "长篇资料.docx",
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = blocks,
        )
        return NovexSourceCollectionBuilder.create(
            ref = NovexResourceRef("novex://source-collections/persisted"),
            scopeRef = NovexResourceRef("novex://conversation-branches/branch-a"),
            title = "持久资料",
            imports = listOf(
                NovexSourceImportResult(
                    ref = NovexResourceRef("novex://sources/source-a"),
                    title = "长篇资料.docx",
                    sha256 = sha,
                    document = document,
                ),
            ),
            nowMillis = 1_000,
        )
    }
}
