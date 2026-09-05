package com.openminis.app.novex.domain

import java.security.MessageDigest

data class NovexLearningReviewRequest(
    val collectionRef: NovexResourceRef,
    val documentRef: NovexResourceRef,
    val documentTitle: String,
    val blocks: List<NovexDocumentBlock>,
    val estimatedInputTokens: Int,
    val maxOutputTokens: Int,
)

data class NovexLearningSynthesisRequest(
    val collectionRef: NovexResourceRef,
    val collectionTitle: String,
    val notes: List<NovexLearningNote>,
    val estimatedInputTokens: Int,
    val maxOutputTokens: Int,
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
    private val maxBlocksPerBatch: Int = 20,
    private val maxCharsPerBatch: Int = 24_000,
) {
    init {
        require(maxBlocksPerBatch in 1..100) { "学习批次内容块数量必须在一到一百之间" }
        require(maxCharsPerBatch in 1_000..48_000) { "学习批次字符预算必须在一千到四万八千之间" }
    }

    suspend fun run(initial: NovexLearningState): NovexLearningState {
        var state = initial
        var task = requireNotNull(state.task) { "学习任务尚未由原生界面确认启动" }
        require(task.status in setOf(NovexLearningTaskStatus.INDEXING, NovexLearningTaskStatus.REVIEWING)) {
            "当前学习任务不能执行通读"
        }
        task = task.advanceTo(NovexLearningTaskStatus.REVIEWING)
        state = state.copy(task = task)
        saveCheckpoint(state)

        for ((documentRef, readableBlockIds) in state.reviewLedger.readableBlocksByDocument) {
            val snapshot = documents.find(documentRef) ?: return state.finishPartialFailure()
            val alreadyReviewed = state.reviewLedger.reviewedBlocksByDocument[documentRef].orEmpty()
            val remaining = snapshot.blocks.filter { it.id in readableBlockIds && it.id !in alreadyReviewed }
            for (blocks in remaining.boundedBatches()) {
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
                    ),
                )
                require(output.inputTokens <= estimatedInput && output.outputTokens <= reservedOutput) {
                    "学习模型实际用量超过本批保留预算"
                }
                task = task.recordUsage(output.inputTokens, output.outputTokens)
                val blockIds = blocks.map { it.id }
                val note = NovexLearningNote(
                    ref = stableNoteRef("review", documentRef.value, blockIds.joinToString("\u001f")),
                    level = if (blockIds.size == 1) NovexLearningNoteLevel.BLOCK else NovexLearningNoteLevel.SECTION,
                    title = output.title,
                    body = output.body,
                    sourceDocumentRefs = listOf(documentRef),
                    sourceBlockIds = blockIds,
                )
                state = state.copy(
                    reviewLedger = state.reviewLedger.recordRead(
                        documentRef = documentRef,
                        blockIds = blockIds,
                        mode = NovexDocumentReadMode.FULL_REVIEW,
                    ),
                    notes = state.notes.upsert(note),
                    task = task,
                )
                saveCheckpoint(state)
                if (task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) return state
            }
        }

        val synthesisInput = conservativeInputEstimate(state.notes.sumOf { it.body.length })
        val synthesisOutput = outputReservation(synthesisInput)
        if (!task.usage.canConsume(synthesisInput, synthesisOutput)) {
            state = state.copy(task = task.pauseForBudget())
            saveCheckpoint(state)
            return state
        }
        task = task.advanceTo(NovexLearningTaskStatus.SYNTHESIZING)
        state = state.copy(task = task)
        saveCheckpoint(state)
        val synthesis = reviewer.synthesize(
            NovexLearningSynthesisRequest(
                collectionRef = state.collection.ref,
                collectionTitle = state.collection.title,
                notes = state.notes,
                estimatedInputTokens = synthesisInput,
                maxOutputTokens = synthesisOutput,
            ),
        )
        require(synthesis.inputTokens <= synthesisInput && synthesis.outputTokens <= synthesisOutput) {
            "学习总结实际用量超过保留预算"
        }
        task = task.recordUsage(synthesis.inputTokens, synthesis.outputTokens)
        val collectionNote = NovexLearningNote(
            ref = stableNoteRef("collection", state.collection.ref.value),
            level = NovexLearningNoteLevel.COLLECTION,
            title = synthesis.title,
            body = synthesis.body,
            sourceDocumentRefs = state.collection.uniqueDocumentRefs,
        )
        val finishedStatus = if (state.reviewLedger.unreadableSourceRefs.isEmpty()) {
            NovexLearningTaskStatus.COMPLETE
        } else {
            NovexLearningTaskStatus.PARTIAL_FAILURE
        }
        state = state.copy(
            notes = state.notes.upsert(collectionNote),
            task = task.finish(finishedStatus),
        )
        saveCheckpoint(state)
        return state
    }

    private fun NovexLearningState.finishPartialFailure(): NovexLearningState = copy(
        task = requireNotNull(task).finish(NovexLearningTaskStatus.PARTIAL_FAILURE),
    ).also(saveCheckpoint)

    private fun List<NovexDocumentBlock>.boundedBatches(): List<List<NovexDocumentBlock>> {
        val result = mutableListOf<List<NovexDocumentBlock>>()
        var batch = mutableListOf<NovexDocumentBlock>()
        var chars = 0
        for (block in this) {
            val wouldOverflow = batch.isNotEmpty() &&
                (batch.size >= maxBlocksPerBatch || chars + block.text.length > maxCharsPerBatch)
            if (wouldOverflow) {
                result += batch
                batch = mutableListOf()
                chars = 0
            }
            batch += block
            chars += block.text.length
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }

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
