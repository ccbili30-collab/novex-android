package com.openminis.app.novex.domain

import java.security.MessageDigest

data class NovexLearningReviewRequest(
    val collectionRef: NovexResourceRef,
    val documentRef: NovexResourceRef,
    val documentTitle: String,
    val blocks: List<NovexDocumentBlock>,
    val estimatedInputTokens: Int,
    val maxOutputTokens: Int,
    val sourceRanges: List<NovexLearningReadRange> = emptyList(),
)

data class NovexLearningSynthesisRequest(
    val collectionRef: NovexResourceRef,
    val collectionTitle: String,
    val notes: List<NovexLearningNote>,
    val estimatedInputTokens: Int,
    val maxOutputTokens: Int,
    val targetCharacters: Int? = null,
)

data class NovexLearningReviewOutput(
    val title: String,
    val body: String,
    val inputTokens: Int,
    val outputTokens: Int,
) {
    init {
        require(title.isNotBlank()) { "学习笔记标题不能为空" }
        require(body.isNotBlank()) { "学习笔记正文不能为空" }
        require(inputTokens >= 0 && outputTokens >= 0) { "学习模型用量不能为负数" }
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
) {
    init {
        require(maxBlocksPerBatch in 1..5000) { "学习批次内容块数量必须在一到五千之间" }
        require(maxCharsPerBatch in 1_000..48_000) { "学习批次字符预算必须在一千到四万八千之间" }
    }

    suspend fun run(initial: NovexLearningState): NovexLearningState {
        var state = initial
        var task = requireNotNull(state.task) { "学习任务尚未由原生界面确认启动" }
        require(task.status in setOf(NovexLearningTaskStatus.INDEXING, NovexLearningTaskStatus.REVIEWING, NovexLearningTaskStatus.SYNTHESIZING)) {
            "当前学习任务不能执行通读"
        }
        if (task.status != NovexLearningTaskStatus.SYNTHESIZING) task = task.advanceTo(NovexLearningTaskStatus.REVIEWING)
        state = state.copy(task = task)
        saveCheckpoint(state)

        for ((documentRef, readableBlockIds) in state.reviewLedger.readableBlocksByDocument) {
            val snapshot = documents.find(documentRef) ?: return state.finishPartialFailure()
            val alreadyReviewed = state.reviewLedger.reviewedBlocksByDocument[documentRef].orEmpty()
            val remaining = snapshot.blocks.filter { it.id in readableBlockIds && it.id !in alreadyReviewed }
            val readRanges = state.notes.flatMap { it.readRanges }.toMutableList()
            for (batch in NovexLearningBatchPlanner.plan(documentRef, remaining, maxBlocksPerBatch, maxCharsPerBatch, readRanges)) {
                val blocks = batch.blocks
                val estimatedInput = conservativeInputEstimate(blocks.sumOf { it.text.length })
                val reservedOutput = outputReservation(estimatedInput)
                if (!task.usage.canConsume(estimatedInput, reservedOutput)) {
                    state = state.copy(task = task.pauseForBudget())
                    saveCheckpoint(state)
                    return state
                }
                val output = reviewer.review(
                    NovexLearningReviewRequest(
                        collectionRef = state.collection.ref,
                        documentRef = documentRef,
                        documentTitle = snapshot.title,
                        blocks = blocks,
                        estimatedInputTokens = estimatedInput,
                        maxOutputTokens = reservedOutput,
                        sourceRanges = batch.ranges,
                    ),
                )
                require(output.inputTokens <= estimatedInput && output.outputTokens <= reservedOutput) {
                    "学习模型实际用量超过本批保留预算"
                }
                task = task.recordUsage(output.inputTokens, output.outputTokens)
                val blockIds = blocks.map { it.id }.distinct()
                readRanges += batch.ranges
                val rangesByBlock = readRanges.filter { it.documentRef == documentRef }.groupBy { it.blockId }
                val completed = remaining.filter { block ->
                    block.id in blockIds && NovexLearningBatchPlanner.fullyCovered(block.text.length, rangesByBlock[block.id].orEmpty())
                }.map { it.id }
                val note = NovexLearningNote(
                    ref = stableNoteRef("review", documentRef.value, batch.ranges.joinToString("\u001f") { "${it.blockId}:${it.start}:${it.end}" }),
                    level = if (blockIds.size == 1) NovexLearningNoteLevel.BLOCK else NovexLearningNoteLevel.SECTION,
                    title = output.title,
                    body = output.body,
                    sourceDocumentRefs = listOf(documentRef),
                    sourceBlockIds = blockIds,
                    readRanges = batch.ranges,
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
            val oversized = frontier.firstOrNull { it.body.length > maxCharsPerBatch }
            if (oversized != null) {
                val pieces = NovexLearningBatchPlanner.splitText(oversized.body, maxCharsPerBatch)
                    .mapIndexedNotNull { index, body ->
                        if (body.isBlank()) null else NovexLearningNote(
                            ref = stableNoteRef("note-piece", oversized.ref.value, index.toString()),
                            level = NovexLearningNoteLevel.FILE,
                            title = "${oversized.title} · ${index + 1}",
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
            val batch = frontier.takeWhile { note ->
                (chars + note.body.length <= maxCharsPerBatch).also { fits -> if (fits) chars += note.body.length }
            }
            val final = batch.size == frontier.size
            val synthesisInput = conservativeInputEstimate(chars)
            val synthesisOutput = outputReservation(synthesisInput)
            if (!task.usage.canConsume(synthesisInput, synthesisOutput)) {
                state = state.copy(task = task.pauseForBudget())
                saveCheckpoint(state)
                return state
            }
            val targetCharacters = if (final) null else (chars / 2).coerceAtLeast(1)
            val synthesis = reviewer.synthesize(NovexLearningSynthesisRequest(
                collectionRef = state.collection.ref,
                collectionTitle = state.collection.title,
                notes = batch,
                estimatedInputTokens = synthesisInput,
                maxOutputTokens = synthesisOutput,
                targetCharacters = targetCharacters,
            ))
            require(synthesis.inputTokens <= synthesisInput && synthesis.outputTokens <= synthesisOutput) {
                "学习总结实际用量超过保留预算"
            }
            task = task.recordUsage(synthesis.inputTokens, synthesis.outputTokens)
            if (!final && synthesis.body.length > requireNotNull(targetCharacters)) {
                // Charge the completed request, then stop instead of repeatedly paying
                // for a model that is expanding its intermediate summaries.
                state = state.copy(task = if (task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) task else task.pause())
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
            if (final) {
                val status = if (state.reviewLedger.unreadableSourceRefs.isEmpty()) NovexLearningTaskStatus.COMPLETE
                    else NovexLearningTaskStatus.PARTIAL_FAILURE
                task = task.finish(status)
            }
            state = state.copy(notes = state.notes.upsert(note), task = task)
            saveCheckpoint(state)
            if (final || task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) return state
        }
    }

    private fun NovexLearningState.finishPartialFailure(): NovexLearningState = copy(
        task = requireNotNull(task).finish(NovexLearningTaskStatus.PARTIAL_FAILURE),
    ).also(saveCheckpoint)

    private fun conservativeInputEstimate(charCount: Int): Int =
        (charCount.toLong() * WORST_CASE_TOKENS_PER_UTF16_UNIT + INPUT_OVERHEAD_TOKENS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun outputReservation(inputTokens: Int): Int =
        (inputTokens / 4).coerceIn(MIN_OUTPUT_RESERVATION, MAX_OUTPUT_RESERVATION)

    private fun List<NovexLearningNote>.upsert(note: NovexLearningNote): List<NovexLearningNote> =
        filterNot { it.ref == note.ref } + note

    private fun stableNoteRef(vararg parts: String): NovexResourceRef {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u001f").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return NovexResourceRef("novex://learning-notes/$digest")
    }

    companion object {
        private const val WORST_CASE_TOKENS_PER_UTF16_UNIT = 4L
        private const val INPUT_OVERHEAD_TOKENS = 2_048L
        private const val MIN_OUTPUT_RESERVATION = 512
        private const val MAX_OUTPUT_RESERVATION = 4_096
    }
}
