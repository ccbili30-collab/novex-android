package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class NovexLearningReviewRunnerTest {
    @Test
    fun `full review checkpoints coverage and anchored notes after every bounded batch`() = runTest {
        val fixture = fixture(maxInputTokens = 30_000, maxOutputTokens = 8_000)
        val checkpoints = mutableListOf<NovexLearningState>()
        val reviewer = RecordingReviewer()
        val runner = NovexLearningReviewRunner(
            documents = NovexDocumentSnapshotStore { ref -> fixture.document.takeIf { it.ref == ref } },
            reviewer = reviewer,
            saveCheckpoint = checkpoints::add,
            maxBlocksPerBatch = 2,
            maxCharsPerBatch = 10_000,
        )

        val result = runner.run(fixture.state)

        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertEquals(5, result.reviewLedger.reviewedBlocks)
        assertEquals(3, reviewer.reviewRequests.size)
        assertEquals(1, reviewer.synthesisRequests)
        assertEquals(4, result.notes.size)
        assertTrue(result.notes.take(3).all { it.sourceBlockIds.isNotEmpty() })
        assertEquals(NovexLearningNoteLevel.COLLECTION, result.notes.last().level)
        assertTrue(checkpoints.size >= 5)
        assertEquals(listOf(2, 4, 5), checkpoints
            .filter { it.reviewLedger.reviewedBlocks > 0 }
            .map { it.reviewLedger.reviewedBlocks }
            .distinct())
    }

    @Test
    fun `runner pauses before a batch that cannot fit the confirmed budget`() = runTest {
        val fixture = fixture(maxInputTokens = 100, maxOutputTokens = 100)
        var calls = 0
        val runner = NovexLearningReviewRunner(
            documents = NovexDocumentSnapshotStore { fixture.document },
            reviewer = object : NovexLearningReviewer {
                override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                    calls += 1
                    error("预算不足时不应调用模型")
                }

                override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                    calls += 1
                    error("预算不足时不应调用模型")
                }
            },
            saveCheckpoint = {},
            maxBlocksPerBatch = 2,
            maxCharsPerBatch = 10_000,
        )

        val result = runner.run(fixture.state)

        assertEquals(0, calls)
        assertEquals(NovexLearningTaskStatus.PAUSED_BUDGET_REACHED, result.task?.status)
        assertEquals(0, result.reviewLedger.reviewedBlocks)
    }

    @Test
    fun `interrupted review resumes from the last persisted block without rereading it`() = runTest {
        val fixture = fixture(maxInputTokens = 30_000, maxOutputTokens = 8_000)
        var persisted = fixture.state
        var callsBeforeInterruption = 0
        val interrupted = NovexLearningReviewRunner(
            documents = NovexDocumentSnapshotStore { fixture.document },
            reviewer = object : NovexLearningReviewer {
                override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                    callsBeforeInterruption += 1
                    if (callsBeforeInterruption == 2) throw CancellationException("模拟应用退出")
                    return NovexLearningReviewOutput(
                        title = "首批",
                        body = "已经完成首批",
                        inputTokens = request.estimatedInputTokens,
                        outputTokens = 100,
                    )
                }

                override suspend fun synthesize(request: NovexLearningSynthesisRequest) =
                    error("中断前不应进入总结")
            },
            saveCheckpoint = { persisted = it },
            maxBlocksPerBatch = 2,
            maxCharsPerBatch = 10_000,
        )

        try {
            interrupted.run(fixture.state)
            error("预期发生取消")
        } catch (_: CancellationException) {
            // Expected: the last successful checkpoint remains authoritative.
        }
        assertEquals(2, persisted.reviewLedger.reviewedBlocks)

        val resumedReviewer = RecordingReviewer()
        val resumed = NovexLearningReviewRunner(
            documents = NovexDocumentSnapshotStore { fixture.document },
            reviewer = resumedReviewer,
            saveCheckpoint = { persisted = it },
            maxBlocksPerBatch = 2,
            maxCharsPerBatch = 10_000,
        ).run(persisted)

        assertEquals(NovexLearningTaskStatus.COMPLETE, resumed.task?.status)
        assertEquals(5, resumed.reviewLedger.reviewedBlocks)
        assertEquals(2, resumedReviewer.reviewRequests.size)
        assertTrue(resumedReviewer.reviewRequests.flattenedBlockIds()
            .none { it in fixture.document.blocks.take(2).map(NovexDocumentBlock::id) })
    }

    private fun List<NovexLearningReviewRequest>.flattenedBlockIds(): List<String> =
        flatMap { request -> request.blocks.map(NovexDocumentBlock::id) }

    private class RecordingReviewer : NovexLearningReviewer {
        val reviewRequests = mutableListOf<NovexLearningReviewRequest>()
        var synthesisRequests = 0

        override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
            reviewRequests += request
            return NovexLearningReviewOutput(
                title = "批次 ${reviewRequests.size}",
                body = "已整理 ${request.blocks.size} 个内容块",
                inputTokens = request.estimatedInputTokens,
                outputTokens = 120,
            )
        }

        override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
            synthesisRequests += 1
            return NovexLearningReviewOutput(
                title = "资料集总结",
                body = "共 ${request.notes.size} 条分层笔记",
                inputTokens = request.estimatedInputTokens,
                outputTokens = 160,
            )
        }
    }

    private data class Fixture(
        val document: NovexDocumentSnapshot,
        val state: NovexLearningState,
    )

    private fun fixture(maxInputTokens: Int, maxOutputTokens: Int): Fixture {
        val sha = "f".repeat(64)
        val blocks = (0 until 5).map { index ->
            val source = NovexDocumentSourceAnchor("word/document.xml", index)
            NovexDocumentBlock(
                id = NovexDocumentBlockId.from(sha, source),
                kind = NovexDocumentBlockKind.PARAGRAPH,
                order = index,
                text = "第 $index 节 " + "内容".repeat(400),
                source = source,
            )
        }
        val document = NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = "长文资料",
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = blocks,
        )
        val collection = NovexSourceCollectionBuilder.create(
            ref = NovexResourceRef("novex://source-collections/review-runner"),
            scopeRef = NovexResourceRef("novex://conversation-branches/branch-a"),
            title = "学习资料",
            imports = listOf(
                NovexSourceImportResult(
                    ref = NovexResourceRef("novex://sources/source-a"),
                    title = document.title,
                    sha256 = sha,
                    document = document,
                ),
            ),
            nowMillis = 1_000,
        )
        val preflight = NovexLearningPreflight.prepare(
            NovexLearningPreflightRequest(
                collectionRef = collection.ref,
                sources = listOf(NovexLearningSourceEstimate(document.ref, 20_000)),
                modelId = "model-a",
                effectiveContextTokens = 200_000,
                occupiedContextTokens = 10_000,
                directReadBudgetTokens = 1_000,
                proposedBudget = NovexLearningTokenBudget(30_000, 8_000),
            ),
        )
        val confirmation = NovexLearningConfirmation(
            preflightId = preflight.id,
            modelId = preflight.modelId,
            sourceRefs = preflight.sourceRefs,
            maxInputTokens = maxInputTokens,
            maxOutputTokens = maxOutputTokens,
            confirmedAtMillis = 2_000,
        )
        return Fixture(
            document = document,
            state = NovexLearningState(
                collection = collection,
                reviewLedger = NovexReviewLedger.start(collection),
                preflight = preflight,
                task = NovexLearningCoordinator().start(preflight, confirmation),
            ),
        )
    }
}
