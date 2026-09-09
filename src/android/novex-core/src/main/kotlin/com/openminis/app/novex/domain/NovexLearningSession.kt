package com.openminis.app.novex.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the single saved learning run in a conversation. UI and model callers share its
 * commit, cancellation and restart boundary; the existing runner still owns reading and billing. */
class NovexLearningSession(
    private val scope: CoroutineScope,
    private val repository: NovexLearningRepository,
    private val visibleCollections: () -> List<NovexResourceRef>,
    private val onState: (NovexLearningState) -> Unit,
) {
    private val mutex = Mutex()
    @Volatile private var job: Job? = null
    @Volatile private var runningRef: NovexResourceRef? = null
    @Volatile private var runningPlanId: String? = null
    val isRunning: Boolean get() = job?.isActive == true

    suspend fun start(ref: NovexResourceRef, id: String, awaitCompletion: Boolean,
        commit: () -> NovexLearningExecutionPlans.Commit,
        validate: (NovexLearningPreflightSnapshot) -> Unit,
        execute: suspend (NovexLearningState) -> Unit,
    ): NovexToolResult {
        val (receipt, ownedRun) = mutex.withLock {
            require(ref in visibleCollections()) { "当前对话分支不再包含这份资料集" }
            val committed = commit()
            val task = requireNotNull(committed.state.task)
            require(committed.state.collection.ref == ref) { "保存的整理任务不属于本资料集" }
            val runnable = task.preflight.id == id && task.status in runningStatuses
            onState(committed.state)
            if (runnable) {
                validate(task.preflight)
                if (!isRunning || runningRef != ref || runningPlanId != id) {
                    job?.cancelAndJoin()
                    pauseOthers(ref)
                    runningRef = ref
                    runningPlanId = id
                    job = scope.launch(Dispatchers.IO) { execute(committed.state) }
                }
            }
            NovexToolResult.success("learning.task_saved",
                if (runnable) "整理任务已保存并启动，将分批保存笔记；尚未完成通读，可在资料整理进度中查看或暂停。"
                else "这份计划已有执行记录；保留当前进度与状态，没有重复开始。",
                data = mapOf("collection_ref" to ref.value, "preflight_id" to id, "task_status" to task.status.name,
                    "replayed" to committed.replayed, "reviewed_blocks" to committed.state.reviewLedger.reviewedBlocks,
                    "total_blocks" to committed.state.reviewLedger.totalReadableBlocks), affectedRefs = listOf(ref)) to
                job.takeIf { runnable && runningRef == ref && runningPlanId == id }
        }
        if (!awaitCompletion) return receipt
        return NovexLearningRunCompletion.await(ref, id, ownedRun,
            readState = { mutex.withLock { repository.find(ref)?.takeIf { ref in visibleCollections() } } },
            stopOwnedRun = { mutex.withLock {
                // An interrupted caller cannot cancel a replacement run started by another caller.
                if (ownedRun != null && job === ownedRun) {
                    ownedRun.cancelAndJoin()
                    job = null
                    runningRef = null
                    runningPlanId = null
                    repository.find(ref)?.takeIf { it.task?.preflight?.id == id }?.let(::pauseIfRunning)
                }
            } })
    }

    suspend fun stop(ref: NovexResourceRef, cancel: Boolean) = mutex.withLock {
        if (ref !in visibleCollections() && runningRef != ref) return@withLock
        if (runningRef == ref) {
            job?.cancelAndJoin()
            job = null
            runningRef = null
            runningPlanId = null
        }
        val state = repository.find(ref) ?: return@withLock
        val task = state.task ?: return@withLock
        val control = if (cancel) NovexLearningControl.CANCEL else NovexLearningControl.PAUSE
        if (control !in NovexLearningControlPolicy.allowedControls(task.status)) return@withLock
        save(state.copy(task = if (cancel) task.cancel() else task.pause()))
    }

    /** Stop our own work before changing branches, even if its source has just been revoked. */
    suspend fun pauseActive() = mutex.withLock { pauseOwnedRun() }

    private suspend fun pauseOwnedRun() {
        val ref = runningRef ?: return
        job?.cancelAndJoin()
        job = null
        runningRef = null
        runningPlanId = null
        repository.find(ref)?.let(::pauseIfRunning)
    }

    suspend fun resume(ref: NovexResourceRef, validate: (NovexLearningPreflightSnapshot) -> Unit,
        execute: suspend (NovexLearningState) -> Unit) = mutex.withLock {
        require(ref in visibleCollections()) { "当前对话分支不再包含这份资料集" }
        val state = repository.find(ref) ?: return@withLock
        val task = state.task?.takeIf { it.status == NovexLearningTaskStatus.PAUSED } ?: return@withLock
        validate(task.preflight)
        job?.cancelAndJoin()
        pauseOthers(ref)
        val resumed = state.copy(task = task.resume())
        save(resumed)
        runningRef = ref
        runningPlanId = resumed.task!!.preflightId
        job = scope.launch(Dispatchers.IO) { execute(resumed) }
    }

    /** After process/controller restoration, a persisted running state has no live job.
     * Pause it before returning it to the UI; never restart paid work just by opening a screen. */
    suspend fun restore(): NovexLearningState? = mutex.withLock {
        val refs = visibleCollections()
        if (isRunning && runningRef !in refs) pauseOwnedRun()
        if (isRunning && runningRef in refs) return@withLock runningRef?.let(repository::find)
        val state = refs.asReversed().asSequence().mapNotNull(repository::find).firstOrNull {
            it.task?.status?.let { status -> status !in terminalStatuses } == true
        } ?: return@withLock null
        if (visibleCollections() != refs) return@withLock null
        if (state.task?.status in runningStatuses) pauseIfRunning(state) else state
    }

    private fun pauseOthers(except: NovexResourceRef) {
        visibleCollections().filter { it != except }.forEach { ref ->
            repository.find(ref)?.takeIf { it.task?.status in runningStatuses }?.let { state ->
                repository.save(state.copy(task = state.task!!.pause()))
            }
        }
    }

    private fun pauseIfRunning(state: NovexLearningState): NovexLearningState =
        if (state.task?.status in runningStatuses) state.copy(task = state.task!!.pause()).also(::save) else state

    private fun save(state: NovexLearningState) {
        repository.save(state)
        if (state.collection.ref in visibleCollections()) onState(state)
    }

    private companion object {
        val runningStatuses = setOf(NovexLearningTaskStatus.INDEXING, NovexLearningTaskStatus.REVIEWING,
            NovexLearningTaskStatus.SYNTHESIZING)
        val terminalStatuses = setOf(NovexLearningTaskStatus.CANCELLED, NovexLearningTaskStatus.PARTIAL_FAILURE,
            NovexLearningTaskStatus.COMPLETE)
    }
}
