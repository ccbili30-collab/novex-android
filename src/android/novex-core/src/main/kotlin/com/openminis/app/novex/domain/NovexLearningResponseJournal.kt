package com.openminis.app.novex.domain

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A durable provider receipt, separate from the note/coverage transaction. */
data class NovexLearningResponseReceipt(
    val id: String,
    val preflightId: String,
    val requestKey: String,
    val output: NovexLearningReviewOutput,
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

interface NovexLearningResponseJournal {
    fun responses(collectionRef: NovexResourceRef): List<NovexLearningResponseReceipt>
    fun recordResponse(collectionRef: NovexResourceRef, receipt: NovexLearningResponseReceipt)
}

/** Never falls back to overwriting a live JSON file with a non-atomic copy. */
internal fun writeNovexLearningFile(target: File, body: String) {
    check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "无法建立学习成果目录" }
    val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
    try {
        FileOutputStream(temporary).use { stream ->
            stream.write(body.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

internal fun NovexLearningState.accountFor(receipts: List<NovexLearningResponseReceipt>): NovexLearningState {
    var currentTask = task ?: return this
    val applied = accountedResponseIds.toMutableSet()
    var recoveredResponse = false
    for (receipt in receipts) {
        if (receipt.preflightId != currentTask.preflightId || !applied.add(receipt.id)) continue
        recoveredResponse = true
        val output = receipt.output
        val usage = currentTask.usage.recordObserved(output.inputTokens, output.outputTokens, output.usageIsEstimated)
        currentTask = NovexLearningTaskState.restore(currentTask.preflight,
            if (usage.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED && currentTask.status !in setOf(
                    NovexLearningTaskStatus.CANCELLED, NovexLearningTaskStatus.COMPLETE, NovexLearningTaskStatus.PARTIAL_FAILURE))
                NovexLearningTaskStatus.PAUSED_BUDGET_REACHED else currentTask.status,
            usage, currentTask.resumeStatus)
    }
    return copy(task = currentTask, accountedResponseIds = applied,
        lastFailure = lastFailure ?: if (recoveredResponse) "模型返回结果和用量已找回，笔记尚待提交；继续整理会先核对已保存结果。" else null)
}
