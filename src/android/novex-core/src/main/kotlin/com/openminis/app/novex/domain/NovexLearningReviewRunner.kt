package com.openminis.app.novex.domain

import java.security.MessageDigest
import java.util.UUID

data class NovexLearningReviewRequest(
    val collectionRef: NovexResourceRef,
    val documentRef: NovexResourceRef,
    val documentTitle: String,
    val blocks: List<NovexDocumentBlock>,
    val estimatedInputTokens: Int,
    val maxOutputTokens: Int,
    val sourceRanges: List<NovexLearningReadRange> = emptyList(),
) {
    val prompt: NovexLearningPrompt get() = NovexLearningPrompt.review(documentTitle, blocks)
}

data class NovexLearningSynthesisRequest(
    val collectionRef: NovexResourceRef,
    val collectionTitle: String,
    val notes: List<NovexLearningNote>,
    val estimatedInputTokens: Int,
    val maxOutputTokens: Int,
    val targetCharacters: Int? = null,
) {
    val prompt: NovexLearningPrompt get() = NovexLearningPrompt.synthesis(collectionTitle, notes, targetCharacters)
}

data class NovexLearningReviewOutput(
    val title: String,
    val body: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val usageIsEstimated: Boolean = false,
    val stopReason: String? = "stop",
) {
    val isComplete: Boolean get() = body.isNotBlank() && stopReason?.lowercase() in setOf("stop", "end_turn", "stop_sequence")
    init {
        require(title.isNotBlank()) { "学习笔记标题不能为空" }
        require(inputTokens >= 0 && outputTokens >= 0) { "学习模型用量不能为负数" }
    }

    companion object {
        fun fromProvider(title: String, text: String, inputTokens: Int?, outputTokens: Int?,
            reservedInputTokens: Int, reservedOutputTokens: Int, stopReason: String? = "stop"): NovexLearningReviewOutput {
            val body = text.trim()
            require(reservedInputTokens > 0 && reservedOutputTokens > 0) { "请求预留预算无效" }
            val observedInput = inputTokens?.takeIf { it > 0 }
            val observedOutput = outputTokens?.takeIf { it > 0 }
            return NovexLearningReviewOutput(title, body,
                observedInput ?: reservedInputTokens, observedOutput ?: reservedOutputTokens,
                usageIsEstimated = observedInput == null || observedOutput == null, stopReason = stopReason)
        }
    }
}

interface NovexLearningReviewer {
    suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput
    suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput
}

/**
 * Runs one confirmed full-review task without knowing any model provider or card repository.
 * Every successful batch advances coverage and persists an anchored note atomically through
 * [saveCheckpoint]. No content-management command exists on this seam.
 */
class NovexLearningReviewRunner(
    private val documents: NovexDocumentSnapshotStore,
    private val reviewer: NovexLearningReviewer,
    private val saveCheckpoint: (NovexLearningState) -> Unit,
    private val maxBlocksPerBatch: Int = NovexLearningBatchPlanner.DEFAULT_MAX_BLOCKS,
    private val maxCharsPerBatch: Int = NovexLearningBatchPlanner.DEFAULT_MAX_CHARS,
    private val responseJournal: NovexLearningResponseJournal? = null,
) {
    init {
        require(maxBlocksPerBatch in 1..5000) { "学习批次内容块数量必须在一到五千之间" }
        require(maxCharsPerBatch in 1_000..48_000) { "学习批次字符预算必须在一千到四万八千之间" }
    }

    suspend fun run(initial: NovexLearningState): NovexLearningState {
        var state = responseJournal?.let { initial.accountFor(it.responses(initial.collection.ref)) } ?: initial
        var task = requireNotNull(state.task) { "学习任务尚未由原生界面确认启动" }
        val limits = requireNotNull(task.preflight.modelLimits) { "旧学习任务缺少模型窗口记录，请重新确认学习计划" }
        val batchChars = maxCharsPerBatch
        require(task.status in setOf(NovexLearningTaskStatus.INDEXING, NovexLearningTaskStatus.REVIEWING, NovexLearningTaskStatus.SYNTHESIZING)) {
            "当前学习任务不能执行通读"
        }
        if (task.status != NovexLearningTaskStatus.SYNTHESIZING) task = task.advanceTo(NovexLearningTaskStatus.REVIEWING)
        state = state.copy(task = task)
        saveCheckpoint(state)
        if (state.reviewLedger.totalReadableBlocks == 0) return state.finishPartialFailure()

        suspend fun response(key: String, reservedInput: Int, reservedOutput: Int,
            acceptCached: (NovexLearningReviewOutput) -> Boolean = { true },
            call: suspend () -> NovexLearningReviewOutput): NovexLearningReviewOutput? {
            val cached = responseJournal?.responses(state.collection.ref)?.firstOrNull {
                it.preflightId == task.preflightId && it.requestKey == key && it.output.isComplete && acceptCached(it.output)
            }
            if (cached == null && !task.usage.canConsume(reservedInput, reservedOutput)) {
                state = state.copy(task = task.pauseForBudget())
                saveCheckpoint(state)
                return null
            }
            val receipt = cached ?: NovexLearningResponseReceipt(UUID.randomUUID().toString(), task.preflightId, key, call())
                .also { responseJournal?.recordResponse(state.collection.ref, it) }
            // Receipts are durable before note persistence; recovery accounts each one exactly once.
            state = state.copy(task = task).accountFor(listOf(receipt))
            task = requireNotNull(state.task)
            val output = receipt.output
            if (!output.isComplete) {
                val message = when {
                    output.body.isBlank() -> "模型未返回可保存正文"
                    output.stopReason?.lowercase() in setOf("length", "max_tokens") -> "模型输出被截断"
                    else -> "模型未确认正常结束"
                } + "；本批未计入已整理，用量和返回结果已保留。继续整理会重新处理未完成批次并产生用量。"
                state = state.copy(task = if (task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) task else task.pause(),
                    lastFailure = message)
                saveCheckpoint(state)
                error(message)
            }
            state = state.copy(lastFailure = null)
            return output
        }

        for ((documentRef, readableBlockIds) in state.reviewLedger.readableBlocksByDocument) {
            val snapshot = documents.find(documentRef) ?: return state.finishPartialFailure()
            if (snapshot.status != NovexDocumentStatus.READY) {
                state = state.copy(reviewLedger = state.reviewLedger.recordIncompleteSources(
                    state.collection.sources.filter { it.documentRef == documentRef }.map { it.ref },
                ))
                saveCheckpoint(state)
                if (snapshot.status != NovexDocumentStatus.TRUNCATED) return state.finishPartialFailure()
            }
            val presentIds = snapshot.blocks.map { it.id }.toSet()
            if (readableBlockIds.any { it !in presentIds }) return state.finishPartialFailure()
            val alreadyReviewed = state.reviewLedger.reviewedBlocksByDocument[documentRef].orEmpty()
            val remaining = snapshot.blocks.filter { it.id in readableBlockIds && it.id !in alreadyReviewed }
            val readRanges = state.notes.flatMap { it.readRanges }.toMutableList()
            for (request in NovexLearningBatchPlanner.reviewRequests(state.collection.ref, snapshot.copy(blocks = remaining),
                limits, maxBlocksPerBatch, batchChars, readRanges)) {
                val blocks = request.blocks
                val estimatedInput = request.estimatedInputTokens
                val reservedOutput = request.maxOutputTokens
                val output = response(stableNoteRef("response-review", snapshot.parserVersion, request.prompt.system,
                    request.prompt.user, documentRef.value, request.sourceRanges.toString()).value, estimatedInput, reservedOutput) {
                    reviewer.review(request)
                } ?: return state
                val exceedsReservation = output.inputTokens > estimatedInput || output.outputTokens > reservedOutput
                val blockIds = blocks.map { it.id }.distinct()
                readRanges += request.sourceRanges
                val rangesByBlock = readRanges.filter { it.documentRef == documentRef }.groupBy { it.blockId }
                val completed = remaining.filter { block ->
                    block.id in blockIds && NovexLearningBatchPlanner.fullyCovered(block.text.length, rangesByBlock[block.id].orEmpty())
                }.map { it.id }
                val note = NovexLearningNote(
                    ref = stableNoteRef("review", documentRef.value, request.sourceRanges.joinToString("\u001f") { "${it.blockId}:${it.start}:${it.end}" }),
                    level = if (blockIds.size == 1) NovexLearningNoteLevel.BLOCK else NovexLearningNoteLevel.SECTION,
                    title = output.title,
                    body = output.body,
                    sourceDocumentRefs = listOf(documentRef),
                    sourceBlockIds = blockIds,
                    readRanges = request.sourceRanges,
                )
                state = state.copy(
                    reviewLedger = if (completed.isEmpty()) state.reviewLedger else state.reviewLedger.recordRead(
                        documentRef = documentRef,
                        blockIds = completed,
                        mode = NovexDocumentReadMode.FULL_REVIEW,
                    ),
                    notes = state.notes.upsert(note),
                    task = task,
                )
                saveCheckpoint(state)
                if (exceedsReservation) {
                    state = state.pauseAfterObservedOverrun()
                    error("计入的用量超出本批预留，已保存已读笔记、进度与用量并暂停；缺少提供商计数的部分标为估算，请核对模型计费后再继续")
                }
                if (task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) return state
            }
        }

        if (task.status != NovexLearningTaskStatus.SYNTHESIZING) task = task.advanceTo(NovexLearningTaskStatus.SYNTHESIZING)
        state = state.copy(task = task)
        saveCheckpoint(state)
        while (true) {
            // Keep every leaf note. Only the frontier enters the next model request;
            // persisted input edges also prevent repeating completed paid summaries.
            val consumed = state.notes.flatMap { it.inputNoteRefs }.toSet()
            val frontier = state.notes.filter { it.ref !in consumed && it.level != NovexLearningNoteLevel.COLLECTION }
            if (frontier.isEmpty() && state.notes.any { it.level == NovexLearningNoteLevel.COLLECTION }) {
                val status = if (state.reviewLedger.reviewedBlocks == state.reviewLedger.totalReadableBlocks &&
                    state.reviewLedger.unreadableSourceRefs.isEmpty()) NovexLearningTaskStatus.COMPLETE
                    else NovexLearningTaskStatus.PARTIAL_FAILURE
                return state.copy(task = task.finish(status)).also(saveCheckpoint)
            }
            fun fitsSynthesis(notes: List<NovexLearningNote>) = NovexLearningBudgetPolicy.fits(
                NovexLearningPrompt.synthesis(state.collection.title, notes, Int.MAX_VALUE), limits,
            )
            val oversized = frontier.firstOrNull { it.body.length > batchChars || !fitsSynthesis(listOf(it)) }
            if (oversized != null) {
                var low = 0
                var high = minOf(oversized.body.length, batchChars)
                while (low < high) {
                    val mid = low + (high - low + 1) / 2
                    if (fitsSynthesis(listOf(oversized.copy(body = oversized.body.take(mid))))) low = mid else high = mid - 1
                }
                require(low >= 2) { "笔记标题已占满模型窗口，无法容纳综合正文与输出；请使用更大的模型窗口" }
                // A later fragment may have more bytes per character than the prefix.
                // Every child is checked again before it is sent and may need
                // another split; none of the source note is removed.
                val pieces = NovexLearningBatchPlanner.splitText(oversized.body, low)
                    .mapIndexedNotNull { index, body ->
                        if (body.isBlank()) null else NovexLearningNote(
                            ref = stableNoteRef("note-piece", oversized.ref.value, index.toString()),
                            level = NovexLearningNoteLevel.FILE,
                            title = oversized.title,
                            body = body,
                            sourceDocumentRefs = oversized.sourceDocumentRefs,
                            sourceBlockIds = oversized.sourceBlockIds,
                            inputNoteRefs = listOf(oversized.ref),
                        )
                    }
                state = state.copy(notes = state.notes + pieces)
                saveCheckpoint(state)
                continue
            }
            var chars = 0
            val batch = mutableListOf<NovexLearningNote>()
            for (note in frontier) {
                if (chars.toLong() + note.body.length > batchChars || !fitsSynthesis(batch + note)) break
                batch += note
                chars += note.body.length
            }
            require(batch.isNotEmpty()) { "没有可综合的笔记，请检查资料读取状态和模型窗口" }
            val final = batch.size == frontier.size
            val targetCharacters = if (final) null else (chars / 2).coerceAtLeast(1)
            val synthesisInput = NovexLearningBudgetPolicy.inputReservation(
                NovexLearningPrompt.synthesis(state.collection.title, batch, targetCharacters),
            )
            val synthesisOutput = NovexLearningBudgetPolicy.outputReservation(synthesisInput, limits)
            val synthesisRequest = NovexLearningSynthesisRequest(
                collectionRef = state.collection.ref,
                collectionTitle = state.collection.title,
                notes = batch,
                estimatedInputTokens = synthesisInput,
                maxOutputTokens = synthesisOutput,
                targetCharacters = targetCharacters,
            )
            val synthesis = response(stableNoteRef("response-synthesis", synthesisRequest.prompt.system,
                synthesisRequest.prompt.user, batch.map { it.ref.value }.toString()).value, synthesisInput, synthesisOutput,
                acceptCached = { final || it.body.length <= requireNotNull(targetCharacters) }) {
                reviewer.synthesize(synthesisRequest)
            } ?: return state
            val exceedsReservation = synthesis.inputTokens > synthesisInput || synthesis.outputTokens > synthesisOutput
            if (!final && synthesis.body.length > requireNotNull(targetCharacters)) {
                // Charge the completed request, then stop instead of repeatedly paying
                // for a model that is expanding its intermediate summaries.
                state = state.copy(task = if (task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) task else task.pause(),
                    lastFailure = "模型未按约定缩短阶段笔记，已保留原笔记和本次用量；继续会重新处理这次综合。")
                saveCheckpoint(state)
                error("模型未按约定缩短阶段笔记，已暂停并保留原笔记和本次用量；请检查模型输出后决定是否继续")
            }
            val note = NovexLearningNote(
                ref = if (final) stableNoteRef("collection", state.collection.ref.value)
                    else stableNoteRef("synthesis", *batch.map { it.ref.value }.toTypedArray()),
                level = if (final) NovexLearningNoteLevel.COLLECTION else NovexLearningNoteLevel.FILE,
                title = synthesis.title,
                body = synthesis.body,
                sourceDocumentRefs = if (final) state.collection.uniqueDocumentRefs else batch.flatMap { it.sourceDocumentRefs }.distinct(),
                sourceBlockIds = batch.flatMap { it.sourceBlockIds }.distinct(),
                inputNoteRefs = batch.map { it.ref },
            )
            if (final && !exceedsReservation) {
                val status = if (state.reviewLedger.reviewedBlocks == state.reviewLedger.totalReadableBlocks &&
                    state.reviewLedger.unreadableSourceRefs.isEmpty()) NovexLearningTaskStatus.COMPLETE
                    else NovexLearningTaskStatus.PARTIAL_FAILURE
                task = task.finish(status)
            }
            state = state.copy(notes = state.notes.upsert(note), task = task)
            saveCheckpoint(state)
            if (exceedsReservation) {
                state = state.pauseAfterObservedOverrun()
                error("计入的综合用量超出预留，已保存笔记和用量并暂停；缺少提供商计数的部分标为估算，请核对模型计费后再继续")
            }
            if (final || task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) return state
        }
    }

    private fun NovexLearningState.finishPartialFailure(): NovexLearningState = copy(
        task = requireNotNull(task).finish(NovexLearningTaskStatus.PARTIAL_FAILURE),
    ).also(saveCheckpoint)

    private fun NovexLearningState.pauseAfterObservedOverrun(): NovexLearningState = copy(
        task = requireNotNull(task).let { if (it.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) it else it.pause() },
        lastFailure = "模型用量超出本批预留，已保存笔记与用量；请核对计费后继续。",
    ).also(saveCheckpoint)

    private fun List<NovexLearningNote>.upsert(note: NovexLearningNote): List<NovexLearningNote> =
        filterNot { it.ref == note.ref } + note

    private fun stableNoteRef(vararg parts: String): NovexResourceRef {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u001f").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return NovexResourceRef("novex://learning-notes/$digest")
    }

}
