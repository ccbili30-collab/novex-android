package com.openminis.app.novex.domain

enum class NovexLearningControl {
    PAUSE,
    RESUME,
    EXTEND_BUDGET,
    CANCEL,
    DISMISS,
}

/** Keeps native controls aligned with the persisted task state. */
object NovexLearningControlPolicy {
    fun blocksReplacementPreflight(status: NovexLearningTaskStatus): Boolean = status in setOf(
        NovexLearningTaskStatus.INDEXING,
        NovexLearningTaskStatus.REVIEWING,
        NovexLearningTaskStatus.SYNTHESIZING,
        NovexLearningTaskStatus.PAUSED,
    )

    fun allowedControls(status: NovexLearningTaskStatus): Set<NovexLearningControl> = when (status) {
        NovexLearningTaskStatus.INDEXING,
        NovexLearningTaskStatus.REVIEWING,
        NovexLearningTaskStatus.SYNTHESIZING,
        -> setOf(NovexLearningControl.PAUSE, NovexLearningControl.CANCEL)

        NovexLearningTaskStatus.PAUSED ->
            setOf(NovexLearningControl.RESUME, NovexLearningControl.CANCEL)

        NovexLearningTaskStatus.PAUSED_BUDGET_REACHED -> setOf(
            NovexLearningControl.EXTEND_BUDGET,
            NovexLearningControl.CANCEL,
            NovexLearningControl.DISMISS,
        )

        NovexLearningTaskStatus.NOT_STARTED,
        NovexLearningTaskStatus.CANCELLED,
        NovexLearningTaskStatus.PARTIAL_FAILURE,
        NovexLearningTaskStatus.COMPLETE,
        -> setOf(NovexLearningControl.DISMISS)
    }
}
