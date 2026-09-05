package com.openminis.app.novex.domain

enum class NovexLearningControl {
    PAUSE,
    RESUME,
    CANCEL,
    DISMISS,
}

/** Keeps native controls aligned with the persisted task state. */
object NovexLearningControlPolicy {
    fun allowedControls(status: NovexLearningTaskStatus): Set<NovexLearningControl> = when (status) {
        NovexLearningTaskStatus.INDEXING,
        NovexLearningTaskStatus.REVIEWING,
        NovexLearningTaskStatus.SYNTHESIZING,
        -> setOf(NovexLearningControl.PAUSE, NovexLearningControl.CANCEL)

        NovexLearningTaskStatus.PAUSED ->
            setOf(NovexLearningControl.RESUME, NovexLearningControl.CANCEL)

        NovexLearningTaskStatus.NOT_STARTED,
        NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
        NovexLearningTaskStatus.CANCELLED,
        NovexLearningTaskStatus.PARTIAL_FAILURE,
        NovexLearningTaskStatus.COMPLETE,
        -> setOf(NovexLearningControl.DISMISS)
    }
}
