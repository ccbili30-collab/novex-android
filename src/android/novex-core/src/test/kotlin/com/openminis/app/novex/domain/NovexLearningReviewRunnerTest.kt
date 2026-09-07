package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class NovexLearningReviewRunnerTest {
    @Test fun `confirmed source parsing is pinned through reparse and note reload`() = runTest {
        val fixture = fixture(100_000, 20_000, listOf("原解析：围巾借出与归还，借出时间未知。"))
        val directory = java.nio.file.Files.createTempDirectory("learning-pinned-sources").toFile()
        try {
            val documents = FileNovexDocumentSnapshotRepository(java.io.File(directory, "documents"))
            documents.store(NovexDocumentSnapshotCacheKey(fixture.document.sha256, fixture.document.parserVersion), fixture.document)
            val changed = fixture.document.copy(parserVersion = "fixture-v2", blocks = fixture.document.blocks.map { it.copy(text = "新解析，不能替换已确认资料。") })
            documents.store(NovexDocumentSnapshotCacheKey(changed.sha256, changed.parserVersion), changed)
            val repository = FileNovexLearningRepository(java.io.File(directory, "learning"))
            val reviewer = RecordingReviewer()
            NovexLearningReviewRunner(documents, reviewer, repository::save, responseJournal = repository).run(fixture.state)
            assertEquals(fixture.document.blocks, reviewer.reviewRequests.single().blocks)
            val restored = requireNotNull(FileNovexLearningRepository(java.io.File(directory, "learning")).find(fixture.state.collection.ref))
            val note = restored.notes.first { it.level != NovexLearningNoteLevel.COLLECTION }
            assertEquals(NovexSourceReadEvidence.documentRevision(fixture.document), note.sourceRevisions[fixture.document.ref])
            val result = NovexLearningTools(object : NovexLearningPreflightResolver {
                override fun prepare(collectionRef: NovexResourceRef, modelId: String?) = restored.preflight
                override fun readState(collectionRef: NovexResourceRef) = restored
            }).learningRead(restored.collection.ref, org.json.JSONObject().put("note_ref", note.ref.value))
            assertTrue(result.toJson().contains(note.sourceRevisions.values.single()))
            assertTrue(runCatching {
                NovexLearningPreflight.prepare(NovexLearningPreflightRequest(
                    collectionRef = restored.collection.ref,
                    sources = listOf(NovexLearningSourceEstimate(changed.ref, 20_000)),
                    sourceDocuments = mapOf(changed.ref to changed), modelId = "model-a",
                    effectiveContextTokens = 200_000, occupiedContextTokens = 0, directReadBudgetTokens = 1000,
                    proposedBudget = NovexLearningTokenBudget(100_000, 20_000)), restored)
            }.exceptionOrNull()?.message?.contains("旧笔记覆盖") == true)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `long sources reserve the configured output room before selecting each batch`() {
        for (window in listOf(16_384, 24_576, 32_768)) {
            val fixture = fixture(500_000, 80_000, listOf("独立记录，保存未知来源与未完成事项。".repeat(3000)), modelWindow = window)
            val requests = NovexLearningBatchPlanner.reviewRequests(fixture.state.collection.ref, fixture.document,
                NovexLearningModelLimits(window, 4096))
            assertTrue(requests.size > 1)
            assertTrue(requests.all { it.maxOutputTokens == 4096 && it.estimatedInputTokens + it.maxOutputTokens <= window })
            assertEquals(fixture.document.blocks.single().text, requests.flatMap { it.blocks }.joinToString("") { it.text })
        }
    }

    @Test fun `returned provider work survives failed note commit and replays without another paid request`() = runTest {
        val fixture = fixture(100_000, 20_000, listOf("围巾借出后在傍晚收回；没有记录借出时刻。"))
        val directory = java.nio.file.Files.createTempDirectory("learning-receipt-recovery").toFile()
        try {
            var repository = FileNovexLearningRepository(directory)
            repository.save(fixture.state)
            var calls = 0
            val reviewer = object : NovexLearningReviewer {
                override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                    calls++
                    return NovexLearningReviewOutput("围巾", "借出后傍晚收回，借出时刻未知。", 100, 20)
                }
                override suspend fun synthesize(request: NovexLearningSynthesisRequest) = review(
                    NovexLearningReviewRequest(request.collectionRef, fixture.document.ref, "总结", emptyList(), 100, 20))
            }
            val failed = runCatching {
                NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer, {
                    if (it.notes.isNotEmpty()) throw java.io.IOException("注入：笔记主文件提交失败")
                    repository.save(it)
                }, responseJournal = repository).run(fixture.state)
            }
            assertTrue(failed.isFailure)
            repository = FileNovexLearningRepository(directory)
            val restored = requireNotNull(repository.find(fixture.state.collection.ref))
            assertEquals(0, restored.notes.size)
            assertEquals(100, restored.task!!.usage.usedInputTokens)
            assertEquals(20, restored.task!!.usage.usedOutputTokens)
            assertEquals(1, repository.responses(restored.collection.ref).size)
            val result = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer,
                repository::save, responseJournal = repository).run(restored)
            assertEquals(2, calls) // One source request and one synthesis; the source response was reused.
            assertEquals(2, result.notes.size)
            assertEquals(200, result.task!!.usage.usedInputTokens)
            repository.save(result)
            assertEquals(200, FileNovexLearningRepository(directory).find(restored.collection.ref)!!.task!!.usage.usedInputTokens)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `truncated response persists its cost and evidence but never advances organized coverage`() = runTest {
        val fixture = fixture(100_000, 20_000, listOf("青庐倒水，没有记录饮水。"))
        val directory = java.nio.file.Files.createTempDirectory("learning-truncation").toFile()
        try {
            val repository = FileNovexLearningRepository(directory)
            repository.save(fixture.state)
            var calls = 0
            val reviewer = object : NovexLearningReviewer {
                override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                    calls++
                    return NovexLearningReviewOutput.fromProvider("倒水", "尚未完成的表格 |", 100, 20, 1000, 4096, "length")
                }
                override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput = error("不得综合")
            }
            assertTrue(runCatching {
                NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer,
                    repository::save, responseJournal = repository).run(fixture.state)
            }.isFailure)
            val restored = requireNotNull(FileNovexLearningRepository(directory).find(fixture.state.collection.ref))
            assertEquals(1, calls)
            assertEquals(0, restored.notes.size)
            assertEquals(0, restored.reviewLedger.reviewedBlocks)
            assertEquals(100, restored.task!!.usage.usedInputTokens)
            assertEquals(NovexLearningTaskStatus.PAUSED, restored.task!!.status)
            assertTrue(restored.lastFailure!!.contains("截断"))
            assertEquals("尚未完成的表格 |", repository.responses(restored.collection.ref).single().output.body)
            assertEquals("length", repository.responses(restored.collection.ref).single().output.stopReason)
            assertTrue(!NovexLearningReviewOutput.fromProvider("空返回", "", 10, 5, 100, 200, "stop").isComplete)
            assertTrue(!NovexLearningReviewOutput.fromProvider("未知结束", "正文", 10, 5, 100, 200, null).isComplete)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `progress disclosure preserves estimated and legacy usage and never denies an observed overrun`() {
        val fixture = fixture(30_000, 8_000)
        val overrun = fixture.state.copy(task = fixture.state.task!!.recordObservedUsage(31_000, 20, estimated = true))
        val message = NovexLearningControlPolicy.progressMessage(overrun.task!!)
        assertTrue(message.contains("估算"))
        assertTrue(message.contains("31000"))
        assertTrue(message.contains("超出"))
        assertEquals(false, message.contains("没有超出"))
        val oldJson = org.json.JSONObject(NovexLearningStateJsonCodec.encode(overrun)).put("version", 7)
        oldJson.getJSONObject("task").getJSONObject("usage").apply {
            remove("contains_estimates"); remove("contains_unknown_legacy_usage")
        }
        val legacy = NovexLearningStateJsonCodec.decode(oldJson.toString())
        assertTrue(NovexLearningControlPolicy.progressMessage(legacy.task!!).contains("来源未记录"))
        assertEquals(31_000, legacy.task!!.usage.usedInputTokens)
    }

    @Test fun `missing provider usage uses request reservations labelled as estimates not a fabricated bill`() {
        val missing = NovexLearningReviewOutput.fromProvider("笔记", " 返回正文 ", null, null, 1600, 4096)
        assertTrue(missing.usageIsEstimated)
        assertEquals(1600, missing.inputTokens)
        assertEquals(4096, missing.outputTokens)
        assertEquals("返回正文", missing.body)
        val partial = NovexLearningReviewOutput.fromProvider("笔记", "正文", 123, null, 1600, 4096)
        assertTrue(partial.usageIsEstimated)
        assertEquals(123, partial.inputTokens)
        val actual = NovexLearningReviewOutput.fromProvider("笔记", "正文", 123, 42, 1600, 4096)
        assertEquals(false, actual.usageIsEstimated)
        assertEquals(42, actual.outputTokens)
    }

    @Test fun `estimated provider usage remains labelled after summaries and process restoration`() = runTest {
        val fixture = fixture(30_000, 8_000, listOf("短规则"))
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest) =
                NovexLearningReviewOutput("笔记", "内容已读", 100, 100, usageIsEstimated = true)
            override suspend fun synthesize(request: NovexLearningSynthesisRequest) =
                NovexLearningReviewOutput("总览", "内容已汇总", 100, 100)
        }
        val result = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer, {}).run(fixture.state)
        val restored = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(result))
        val usage = org.json.JSONObject(NovexLearningStateJsonCodec.encode(restored)).getJSONObject("task").getJSONObject("usage")
        assertTrue("一次估算不能被后续真实用量或重启掩盖", usage.optBoolean("contains_estimates"))
        assertEquals(200, restored.task!!.usage.usedInputTokens)
        assertEquals(200, restored.task!!.usage.usedOutputTokens)
    }

    @Test fun `continuation preflight excludes completed source fragments and still requires confirmation`() = runTest {
        val fixture = fixture(500_000, 80_000, listOf("a".repeat(50_000)))
        var saved = fixture.state
        var readCalls = 0
        val firstBatchOnly = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                if (++readCalls > 1) throw CancellationException("暂停")
                return NovexLearningReviewOutput("第一批", "已读第一批", request.estimatedInputTokens, 20)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput = error("尚未综合")
        }
        runCatching {
            NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, firstBatchOnly,
                { saved = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(it)) }).run(fixture.state)
        }
        assertEquals(24_000, saved.notes.single().readRanges.single().end)
        val request = NovexLearningPreflightRequest(
            collectionRef = fixture.state.collection.ref,
            sources = listOf(NovexLearningSourceEstimate(fixture.document.ref, 50_000)),
            sourceDocuments = mapOf(fixture.document.ref to fixture.document), modelId = "model-a",
            effectiveContextTokens = 200_000, occupiedContextTokens = 0, directReadBudgetTokens = 100_000,
            proposedBudget = NovexLearningTokenBudget(600_000, 90_000))
        val continuation = NovexLearningPreflight.prepare(request, saved)
        assertEquals("已完成首批后只剩两批正文", 2, continuation.reviewBatchCount)
        assertTrue("扩大预算必须重新确认，不能因剩余量变小绕过确认", continuation.requiresConfirmation)
        val remainingReviewer = RecordingReviewer()
        NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, remainingReviewer, {}).run(saved)
        assertEquals(2, remainingReviewer.reviewRequests.size)
        assertEquals(26_000, remainingReviewer.reviewRequests.sumOf { it.blocks.sumOf { block -> block.text.length } })
        assertEquals(remainingReviewer.reviewRequests.sumOf { it.estimatedInputTokens }, continuation.reviewInputReservationTokens)
    }

    @Test fun `truncated parsing preserves available notes but never claims complete source review`() = runTest {
        val fixture = fixture(30_000, 8_000)
        val reviewer = RecordingReviewer()
        val result = NovexLearningReviewRunner(
            NovexDocumentSnapshotStore { fixture.document.copy(status = NovexDocumentStatus.TRUNCATED) },
            reviewer, {},
        ).run(fixture.state)
        val restored = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(result))
        assertEquals(NovexLearningTaskStatus.PARTIAL_FAILURE, restored.task?.status)
        assertTrue("可读部分仍应保留整理成果", restored.notes.isNotEmpty())
        assertTrue("未能完整解析的来源必须明确留在阅读记录中", restored.reviewLedger.unreadableSourceRefs.isNotEmpty())
        assertEquals(fixture.document.blocks.size, restored.reviewLedger.reviewedBlocks)
    }

    @Test fun `missing planned source blocks cannot be reported as a completed full review`() = runTest {
        val fixture = fixture(30_000, 8_000)
        val reviewer = RecordingReviewer()
        val result = NovexLearningReviewRunner(
            NovexDocumentSnapshotStore { fixture.document.copy(blocks = fixture.document.blocks.dropLast(1)) },
            reviewer, {},
        ).run(fixture.state)
        assertEquals(NovexLearningTaskStatus.PARTIAL_FAILURE, result.task?.status)
        assertTrue("发现来源结构不一致后不应继续付费读取", reviewer.reviewRequests.isEmpty())
    }

    @Test fun `paid final summary can finish after an overrun pause without another model call`() = runTest {
        val fixture = fixture(30_000, 8_000, listOf("完整规则"))
        var saved = fixture.state
        var summaries = 0
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest) =
                NovexLearningReviewOutput("笔记", "规则已读", request.estimatedInputTokens, 20)
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                summaries++
                return NovexLearningReviewOutput("最终总结", "全部完成", request.estimatedInputTokens + 1, 20)
            }
        }
        val runner = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer,
            { saved = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(it)) })
        runCatching { runner.run(fixture.state) }
        assertEquals(NovexLearningTaskStatus.PAUSED, saved.task?.status)
        val charged = saved.task!!.usage.usedInputTokens
        val result = runner.run(saved.copy(task = saved.task!!.resume()))
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertEquals(1, summaries)
        assertEquals(charged, result.task!!.usage.usedInputTokens)
        assertEquals("全部完成", result.notes.last().body)
    }

    @Test fun `provider usage over the reservation is persisted before stopping further paid work`() = runTest {
        val fixture = fixture(30_000, 8_000, listOf("完整规则"))
        var saved = fixture.state
        var calls = 0
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                calls++
                return NovexLearningReviewOutput("已付费笔记", "规则内容已读", 31_000, 20)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput =
                error("发现超出保留预算后不能继续付费调用")
        }
        runCatching {
            NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer,
                { saved = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(it)) }).run(fixture.state)
        }
        assertEquals(1, calls)
        assertEquals("不能因超额而丢弃真实账目", 31_000, saved.task?.usage?.usedInputTokens)
        assertEquals(NovexLearningTaskStatus.PAUSED_BUDGET_REACHED, saved.task?.status)
        assertEquals("规则内容已读", saved.notes.single().body)
        assertEquals(1, saved.reviewLedger.reviewedBlocks)
    }

    @Test fun `request reservation includes long document and note titles not only body text`() = runTest {
        val fixture = fixture(500_000, 80_000, listOf("正文"), modelWindow = 8192)
        val document = fixture.document.copy(title = "T".repeat(6000))
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                assertTrue("标题本身就需要至少六千个单字节位置", request.estimatedInputTokens >= 6000)
                assertTrue(request.estimatedInputTokens + request.maxOutputTokens <= 8192)
                return NovexLearningReviewOutput("N".repeat(6000), "已读", request.estimatedInputTokens, 20)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                assertTrue("笔记标题也属于实际输入", request.estimatedInputTokens >= 6000)
                assertTrue(request.estimatedInputTokens + request.maxOutputTokens <= 8192)
                return NovexLearningReviewOutput("总览", "完成", request.estimatedInputTokens, 20)
            }
        }
        val result = NovexLearningReviewRunner(NovexDocumentSnapshotStore { document }, reviewer, {}).run(fixture.state)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
    }

    @Test fun `review and synthesis requests fit the confirmed model window with output reserved`() = runTest {
        val fixture = fixture(500_000, 80_000, listOf("文游规则🌏\n".repeat(1000)), modelWindow = 4096)
        val received = mutableListOf<String>()
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                assertTrue("请求不能超过模型窗口", request.estimatedInputTokens + request.maxOutputTokens <= 4096)
                received += request.blocks.joinToString("") { it.text }
                return NovexLearningReviewOutput("小结", "规则已读", request.estimatedInputTokens, 20)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                assertTrue("总结必须预留输出空间", request.estimatedInputTokens + request.maxOutputTokens <= 4096)
                return NovexLearningReviewOutput("总览", "规则汇总", request.estimatedInputTokens, 20)
            }
        }
        val result = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer, {}).run(fixture.state)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertEquals(fixture.document.blocks.single().text, received.joinToString(""))
    }

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

    @Test fun `preflight rounds reflect actual short-line review batches plus final synthesis`() = runTest {
        val fixture = fixture(500_000, 80_000, List(1154) { "规则 $it：玩家不是世界的中心。" })
        val reviewer = RecordingReviewer()
        val result = NovexLearningReviewRunner(NovexDocumentSnapshotStore { fixture.document }, reviewer, {}).run(fixture.state)
        assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
        assertEquals("预检不能漏掉实际综合请求", reviewer.reviewRequests.size + reviewer.synthesisRequests,
            fixture.state.preflight?.estimatedModelRounds)
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

    private fun fixture(maxInputTokens: Int, maxOutputTokens: Int, texts: List<String>? = null, modelWindow: Int = 200_000): Fixture {
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
                sourceDocuments = mapOf(document.ref to document),
                modelId = "model-a",
                effectiveContextTokens = modelWindow,
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
