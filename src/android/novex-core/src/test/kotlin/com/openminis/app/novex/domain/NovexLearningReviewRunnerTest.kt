package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class NovexLearningReviewRunnerTest {
    @Test fun `single huge note is split for synthesis without deleting the original note`() = runTest {
        val fixture = fixture(500_000, 80_000, listOf("x".repeat(800)))
        var summaries = 0
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest) =
                NovexLearningReviewOutput("详细笔记", "a".repeat(5000), request.estimatedInputTokens, 1250)
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                assertTrue(request.notes.sumOf { it.body.length } <= 1000)
                summaries++
                return NovexLearningReviewOutput("小结", "b".repeat(100), request.estimatedInputTokens, 50)
            }
        }
        val result = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer, {},
            maxCharsPerBatch = 1000).run(fixture.state)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertTrue(summaries > 1)
        assertEquals(1, result.notes.count { it.body == "a".repeat(5000) })
    }

    @Test fun `expanding intermediate summaries pause with paid usage recorded instead of looping`() = runTest {
        val fixture = fixture(500_000, 80_000, List(4) { "x".repeat(800) })
        var saved = fixture.state
        var summaries = 0
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest) =
                NovexLearningReviewOutput("详细笔记", "a".repeat(1500), request.estimatedInputTokens, 400)
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                summaries++
                return NovexLearningReviewOutput("反而扩写", "b".repeat(2000), request.estimatedInputTokens, 500)
            }
        }
        try {
            NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer, { saved = it },
                maxCharsPerBatch = 2000).run(fixture.state)
            error("应暂停扩写循环")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("阶段笔记"))
        }
        assertEquals(1, summaries)
        assertEquals(NovexLearningTaskStatus.PAUSED, saved.task?.status)
        assertEquals(1300, saved.task?.usage?.usedOutputTokens)
        assertEquals(2, saved.notes.size)
    }

    @Test fun `large note synthesis is bounded and resumes without repeating paid summaries`() = runTest {
        val fixture = fixture(500_000, 80_000, List(12) { "正文".repeat(400) })
        var persisted = fixture.state
        var interrupt = true
        var reviewCalls = 0
        val summarized = mutableListOf<List<String>>()
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                reviewCalls++
                return NovexLearningReviewOutput("第 $reviewCalls 批", "a".repeat(1400), request.estimatedInputTokens, 400)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                if (interrupt && summarized.isNotEmpty()) throw CancellationException("总结期间退出")
                assertTrue("总结也必须遵循正文预算", request.notes.sumOf { it.body.length } <= 2000)
                val refs = request.notes.map { it.ref.value }
                assertTrue("已持久化的总结不应重复付费执行", refs !in summarized)
                summarized += refs
                return NovexLearningReviewOutput("小结", "b".repeat(200), request.estimatedInputTokens, 100)
            }
        }
        fun runner() = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer,
            { persisted = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(it)) },
            maxCharsPerBatch = 2000)
        try {
            runner().run(fixture.state)
            error("预期中断")
        } catch (_: CancellationException) { }
        assertEquals(12, persisted.reviewLedger.reviewedBlocks)
        assertEquals(1, summarized.size)
        interrupt = false
        val result = runner().run(persisted)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertEquals(6, reviewCalls)
        assertTrue(summarized.size > 1)
        assertEquals(6, result.notes.count { it.body == "a".repeat(1400) })
        assertEquals(NovexLearningNoteLevel.COLLECTION, result.notes.last().level)
    }

    @Test fun `short line documents use the content budget rather than twenty line batches`() = runTest {
        val fixture = fixture(500_000, 80_000, List(1154) { "规则 $it：玩家不是世界的中心。" })
        val reviewer = RecordingReviewer()
        val result = NovexLearningReviewRunner(
            NovexDocumentSnapshotStore { fixture.document }, reviewer, {},
        ).run(fixture.state)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertEquals(1, reviewer.reviewRequests.size)
        assertEquals(1154, result.reviewLedger.reviewedBlocks)
    }

    @Test fun `oversized block is fully read in bounded pieces and partial progress survives restart`() = runTest {
        val text = ("文游规则🌏\n".repeat(550))
        val fixture = fixture(500_000, 80_000, listOf(text))
        var persisted = fixture.state
        val received = mutableListOf<String>()
        var interrupt = true
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                if (interrupt && received.isNotEmpty()) throw CancellationException("模拟退出")
                val body = request.blocks.joinToString("") { it.text }
                assertTrue("单次不能超出约定正文预算", body.length <= 1000)
                assertTrue("不能切断表情符号", body.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == body)
                received += body
                return NovexLearningReviewOutput("片段", "记住本段", request.estimatedInputTokens, 20)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest) =
                NovexLearningReviewOutput("总结", "完整通读", request.estimatedInputTokens, 20)
        }
        fun runner() = NovexLearningReviewRunner(
            NovexDocumentSnapshotStore { fixture.document }, reviewer,
            { persisted = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(it)) },
            maxCharsPerBatch = 1000,
        )
        try {
            runner().run(fixture.state)
            error("预期发生退出")
        } catch (_: CancellationException) { }
        assertEquals("读完片段不等于读完整块", 0, persisted.reviewLedger.reviewedBlocks)
        assertEquals(1, received.size)
        interrupt = false
        val result = runner().run(persisted)
        assertEquals(text, received.joinToString(""))
        assertEquals(1, result.reviewLedger.reviewedBlocks)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
    }

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

    private fun fixture(maxInputTokens: Int, maxOutputTokens: Int, texts: List<String>? = null): Fixture {
        val sha = "f".repeat(64)
        val blocks = (0 until (texts?.size ?: 5)).map { index ->
            val source = NovexDocumentSourceAnchor("word/document.xml", index)
            NovexDocumentBlock(
                id = NovexDocumentBlockId.from(sha, source),
                kind = NovexDocumentBlockKind.PARAGRAPH,
                order = index,
                text = texts?.get(index) ?: ("第 $index 节 " + "内容".repeat(400)),
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
                proposedBudget = NovexLearningTokenBudget(maxOf(30_000, maxInputTokens), maxOf(8_000, maxOutputTokens)),
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
