package com.openminis.app.novex.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A model tool returns saved outcomes, not an asynchronous launch receipt. */
object NovexLearningRunCompletion {
    suspend fun await(
        collectionRef: NovexResourceRef,
        preflightId: String,
        run: Job?,
        readState: suspend () -> NovexLearningState?,
        stopOwnedRun: suspend () -> Unit,
    ): NovexToolResult {
        try { run?.join() }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopOwnedRun() }
            throw cancelled
        }
        val state = readState()
        val task = state?.task
        if (task == null || task.preflight.id != preflightId) {
            return NovexToolResult.failure("learning.task_changed", "整理任务已变化，不能把其他计划的结果当成本次完成。已保存笔记保留。")
        }
        val data = mapOf("collection_ref" to collectionRef.value, "preflight_id" to preflightId,
            "task_status" to task.status.name, "reviewed_blocks" to state.reviewLedger.reviewedBlocks,
            "total_blocks" to state.reviewLedger.totalReadableBlocks)
        return if (task.status == NovexLearningTaskStatus.COMPLETE) {
            NovexToolResult.success("learning.completed", "资料整理已完成并保存。读取整理笔记及必要原文后，继续用户原先的任务。",
                data = data, nextActions = listOf(NovexToolNextAction("learning_read", "读取已保存成果，继续原任务")),
                affectedRefs = listOf(collectionRef))
        } else {
            val explanation = when (task.status) {
                NovexLearningTaskStatus.PAUSED_BUDGET_REACHED -> "本次预算已用完，整理暂停；已保存进度和笔记保留。"
                NovexLearningTaskStatus.CANCELLED -> "整理已取消；已保存成果保留。"
                NovexLearningTaskStatus.PARTIAL_FAILURE -> "部分资料尚未整理成功；已保存成果保留。"
                else -> "整理已暂停，尚未全部完成；已保存进度和笔记保留。"
            }
            NovexToolResult.failure("learning.incomplete", explanation, data = data,
                nextActions = listOf(NovexToolNextAction("learning_read", "查看已保存成果与未完成范围")),
                affectedRefs = listOf(collectionRef))
        }
    }
}
