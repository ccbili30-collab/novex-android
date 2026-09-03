package com.openminis.app.startup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NovexStartupPhase {
    PREPARING,
    MINIMUM_READY,
    RUNTIME_READY,
    FAILED,
}

enum class NovexStartupStage {
    MINIMUM,
    RUNTIME,
}

data class NovexStartupFailure(
    val stage: NovexStartupStage,
    val message: String,
)

data class NovexStartupState(
    val phase: NovexStartupPhase = NovexStartupPhase.PREPARING,
    val failure: NovexStartupFailure? = null,
    val minimumAvailable: Boolean = false,
)

/**
 * Single process coordinator for the two startup depths Novex exposes.
 * Callers only choose the depth they need; task sharing, failure retention and
 * ordering stay behind this interface.
 */
class NovexStartupCoordinator(
    private val scope: CoroutineScope,
    val safeMode: Boolean,
    private val initializeMinimum: suspend () -> Unit,
    private val initializeRuntime: suspend () -> Unit,
) {
    private val mutableState = MutableStateFlow(NovexStartupState())
    val state: StateFlow<NovexStartupState> = mutableState.asStateFlow()

    private var minimumTask: Deferred<Result<Unit>>? = null
    private var runtimeTask: Deferred<Result<Unit>>? = null

    fun startMinimum() {
        if (safeMode) return
        minimumTask()
    }

    suspend fun awaitMinimum(): Result<Unit> {
        if (safeMode) return Result.failure(IllegalStateException("安全模式未启动数据层"))
        return minimumTask().await()
    }

    suspend fun ensureRuntime(): Result<Unit> {
        if (safeMode) return Result.failure(IllegalStateException("安全模式未启动旧运行时"))
        val minimum = minimumTask().await()
        if (minimum.isFailure) return minimum
        val task = synchronized(this) {
            runtimeTask ?: scope.async {
                runCatching { initializeRuntime() }
                    .onSuccess {
                        mutableState.value = NovexStartupState(
                            phase = NovexStartupPhase.RUNTIME_READY,
                            minimumAvailable = true,
                        )
                    }
                    .onFailure { error ->
                        mutableState.value = NovexStartupState(
                            phase = NovexStartupPhase.FAILED,
                            failure = NovexStartupFailure(
                                stage = NovexStartupStage.RUNTIME,
                                message = error.message ?: error::class.java.simpleName,
                            ),
                            minimumAvailable = true,
                        )
                    }
            }.also { runtimeTask = it }
        }
        return task.await()
    }

    private fun minimumTask(): Deferred<Result<Unit>> = synchronized(this) {
        minimumTask ?: scope.async {
            NovexStartupMetrics.reportStage("minimum_start")
            runCatching { initializeMinimum() }
                .onSuccess {
                    NovexStartupMetrics.reportStage("minimum_ready")
                    mutableState.value = NovexStartupState(
                        phase = NovexStartupPhase.MINIMUM_READY,
                        minimumAvailable = true,
                    )
                }
                .onFailure { error ->
                    NovexStartupMetrics.reportStage("minimum_failed")
                    mutableState.value = NovexStartupState(
                        phase = NovexStartupPhase.FAILED,
                        failure = NovexStartupFailure(
                            stage = NovexStartupStage.MINIMUM,
                            message = error.message ?: error::class.java.simpleName,
                        ),
                        minimumAvailable = false,
                    )
                }
        }.also { minimumTask = it }
    }
}
