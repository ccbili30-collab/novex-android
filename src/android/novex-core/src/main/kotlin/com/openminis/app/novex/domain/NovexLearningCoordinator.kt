package com.openminis.app.novex.domain

/**
 * Application-facing learning control seam.
 *
 * Provider adapters may expose learning_prepare, but this start operation is intentionally absent
 * from the model tool catalog and must only be called after the native UI captures confirmation.
 */
class NovexLearningCoordinator {
    fun start(
        preflight: NovexLearningPreflightSnapshot,
        confirmation: NovexLearningConfirmation?,
    ): NovexLearningTaskState {
        require(preflight.requiresConfirmation) { "少量资料应直接读取，不应建立长时学习任务" }
        require(confirmation != null) { "开始学习前必须由原生界面取得用户确认" }
        val usage = NovexLearningUsageLedger.start(preflight, confirmation)
        return NovexLearningTaskState(
            preflight = preflight,
            status = NovexLearningTaskStatus.INDEXING,
            usage = usage,
            resumeStatus = NovexLearningTaskStatus.INDEXING,
        )
    }

    fun extendBudget(
        task: NovexLearningTaskState,
        preflight: NovexLearningPreflightSnapshot,
        confirmation: NovexLearningConfirmation?,
    ): NovexLearningTaskState {
        require(task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) {
            "只有到达预算后暂停的学习任务可以扩大预算"
        }
        require(preflight.collectionRef == task.collectionRef) { "扩大预算不能更换资料集" }
        require(preflight.modelId == task.preflight.modelId) { "扩大预算不能更换学习模型" }
        require(preflight.sourceRefs == task.preflight.sourceRefs) { "扩大预算不能更换资料范围" }
        require(confirmation != null) { "扩大预算前必须由原生界面重新确认" }
        require(NovexLearningGate.authorize(preflight, confirmation) == NovexLearningAuthorization.AUTHORIZED) {
            "扩大预算尚未获得与新预检匹配的用户确认"
        }
        require(
            confirmation.maxInputTokens >= task.usage.maxInputTokens &&
                confirmation.maxOutputTokens >= task.usage.maxOutputTokens &&
                (confirmation.maxInputTokens > task.usage.maxInputTokens ||
                    confirmation.maxOutputTokens > task.usage.maxOutputTokens)
        ) { "新确认的学习预算必须高于原预算" }
        val usage = NovexLearningUsageLedger.restore(
            preflightId = preflight.id,
            maxInputTokens = confirmation.maxInputTokens,
            maxOutputTokens = confirmation.maxOutputTokens,
            usedInputTokens = task.usage.usedInputTokens,
            usedOutputTokens = task.usage.usedOutputTokens,
            status = task.resumeStatus,
        )
        return NovexLearningTaskState(
            preflight = preflight,
            status = task.resumeStatus,
            usage = usage,
            resumeStatus = task.resumeStatus,
        )
    }
}

class NovexLearningTaskState internal constructor(
    val preflight: NovexLearningPreflightSnapshot,
    val status: NovexLearningTaskStatus,
    val usage: NovexLearningUsageLedger,
    internal val resumeStatus: NovexLearningTaskStatus,
) {
    val preflightId: String get() = preflight.id
    val collectionRef: NovexResourceRef get() = preflight.collectionRef

    fun recordUsage(inputTokens: Int, outputTokens: Int): NovexLearningTaskState {
        check(status in EXECUTING_STATUSES) { "只有执行中的学习任务可以记录模型用量" }
        val nextUsage = usage.record(inputTokens, outputTokens)
        return copy(
            status = if (nextUsage.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) {
                NovexLearningTaskStatus.PAUSED_BUDGET_REACHED
            } else {
                status
            },
            usage = nextUsage,
        )
    }

    fun pause(): NovexLearningTaskState {
        check(status in EXECUTING_STATUSES) { "只有执行中的学习任务可以暂停" }
        return copy(status = NovexLearningTaskStatus.PAUSED, resumeStatus = status)
    }

    fun resume(): NovexLearningTaskState {
        check(status == NovexLearningTaskStatus.PAUSED) { "只有已暂停的学习任务可以继续" }
        return copy(status = resumeStatus)
    }

    fun cancel(): NovexLearningTaskState {
        check(status !in TERMINAL_STATUSES) { "已结束的学习任务不能再次取消" }
        return copy(status = NovexLearningTaskStatus.CANCELLED)
    }

    fun advanceTo(next: NovexLearningTaskStatus): NovexLearningTaskState {
        val allowed = when (status) {
            NovexLearningTaskStatus.INDEXING -> setOf(NovexLearningTaskStatus.REVIEWING)
            NovexLearningTaskStatus.REVIEWING -> setOf(NovexLearningTaskStatus.REVIEWING, NovexLearningTaskStatus.SYNTHESIZING)
            else -> emptySet()
        }
        require(next in allowed) { "学习任务阶段不能从 $status 前进到 $next" }
        return copy(status = next, resumeStatus = next)
    }

    fun pauseForBudget(): NovexLearningTaskState {
        check(status in EXECUTING_STATUSES) { "只有执行中的学习任务可以因预算暂停" }
        return copy(status = NovexLearningTaskStatus.PAUSED_BUDGET_REACHED, resumeStatus = status)
    }

    fun finish(result: NovexLearningTaskStatus): NovexLearningTaskState {
        require(result in setOf(NovexLearningTaskStatus.COMPLETE, NovexLearningTaskStatus.PARTIAL_FAILURE)) {
            "学习任务只能结束为完成或部分失败"
        }
        check(status in EXECUTING_STATUSES || status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED) {
            "当前学习任务不能结束"
        }
        return copy(status = result)
    }

    private fun copy(
        status: NovexLearningTaskStatus = this.status,
        usage: NovexLearningUsageLedger = this.usage,
        resumeStatus: NovexLearningTaskStatus = this.resumeStatus,
    ) = NovexLearningTaskState(
        preflight = preflight,
        status = status,
        usage = usage,
        resumeStatus = resumeStatus,
    )

    companion object {
        internal fun restore(
            preflight: NovexLearningPreflightSnapshot,
            status: NovexLearningTaskStatus,
            usage: NovexLearningUsageLedger,
            resumeStatus: NovexLearningTaskStatus,
        ): NovexLearningTaskState {
            require(usage.preflightId == preflight.id) { "学习用量与预检快照不一致" }
            return NovexLearningTaskState(preflight, status, usage, resumeStatus)
        }

        private val EXECUTING_STATUSES = setOf(
            NovexLearningTaskStatus.INDEXING,
            NovexLearningTaskStatus.REVIEWING,
            NovexLearningTaskStatus.SYNTHESIZING,
        )
        private val TERMINAL_STATUSES = setOf(
            NovexLearningTaskStatus.CANCELLED,
            NovexLearningTaskStatus.PARTIAL_FAILURE,
            NovexLearningTaskStatus.COMPLETE,
        )
    }
}
