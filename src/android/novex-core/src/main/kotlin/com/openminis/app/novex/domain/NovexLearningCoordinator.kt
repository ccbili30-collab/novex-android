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
