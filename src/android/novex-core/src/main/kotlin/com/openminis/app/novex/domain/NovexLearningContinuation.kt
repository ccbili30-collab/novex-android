package com.openminis.app.novex.domain

enum class NovexLearningContinuationMode { CURRENT_SOURCES, RECHECK_SOURCES }

/** Native proposal preparation; never grants model-side permission to start or reset spending. */
object NovexLearningContinuation {
    fun originFingerprint(state: NovexLearningState): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(NovexLearningStateJsonCodec.encode(state).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun prepareState(state: NovexLearningState, documents: NovexDocumentSnapshotStore,
        mode: NovexLearningContinuationMode): NovexLearningState {
        val task = requireNotNull(state.task) { "尚无可继续的学习任务" }
        require(task.status in setOf(NovexLearningTaskStatus.PAUSED, NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
            NovexLearningTaskStatus.PARTIAL_FAILURE, NovexLearningTaskStatus.COMPLETE)) { "请先暂停当前整理" }
        if (mode == NovexLearningContinuationMode.CURRENT_SOURCES) {
            require(task.status != NovexLearningTaskStatus.COMPLETE) { "资料已经完成，无需换模型重复整理" }
            return state
        }
        val imports = state.collection.sources.map { source ->
            val snapshot = source.documentRef?.let(documents::find)
            NovexSourceImportResult(source.ref, source.title, source.sha256, snapshot,
                if (snapshot == null) source.failureCode ?: "来源解析目前不可读" else null)
        }
        val collection = NovexSourceCollectionBuilder.create(state.collection.ref, state.collection.scopeRef,
            state.collection.title, imports, state.collection.updatedAtMillis).copy(createdAtMillis = state.collection.createdAtMillis)
        return state.copy(collection = collection, reviewLedger = NovexReviewLedger.start(collection), notes = emptyList(),
            task = null, preflight = null, previousTasks = state.previousTasks + task,
            historicalNotes = state.historicalNotes + state.notes, lastFailure = null)
    }

    fun confirm(original: NovexLearningState, prepared: NovexLearningState, mode: NovexLearningContinuationMode,
        preflight: NovexLearningPreflightSnapshot, confirmation: NovexLearningConfirmation?): NovexLearningState {
        val previous = requireNotNull(original.task)
        require(preflight.sourcePlanFingerprint == originFingerprint(original)) { "原任务进度或用量已变化，请重新确认续接计划" }
        require(previous.status in setOf(NovexLearningTaskStatus.PAUSED, NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
            NovexLearningTaskStatus.PARTIAL_FAILURE, NovexLearningTaskStatus.COMPLETE)) { "原任务尚未暂停或结束" }
        require(original.collection.ref == prepared.collection.ref && prepared.collection.ref == preflight.collectionRef &&
            original.collection.scopeRef == prepared.collection.scopeRef) { "续接不能更换资料集或对话分支" }
        require(original.collection.sources.map { it.ref to it.sha256 } == prepared.collection.sources.map { it.ref to it.sha256 }) {
            "续接不能更换原文件范围"
        }
        if (mode == NovexLearningContinuationMode.CURRENT_SOURCES) {
            require(previous.status != NovexLearningTaskStatus.COMPLETE) { "已完成任务不能重复开始" }
            require(previous.preflight.sourceRefs == preflight.sourceRefs && previous.preflight.documentRevisions == preflight.documentRevisions) {
                "换模型继续必须保留原来源修订；变更来源请使用重新核对入口"
            }
            require(prepared.notes == original.notes && prepared.reviewLedger == original.reviewLedger) { "换模型不能丢弃已保存进度" }
        }
        val started = NovexLearningCoordinator().start(preflight, confirmation)
        require(started.usage.maxInputTokens >= previous.usage.usedInputTokens &&
            started.usage.maxOutputTokens >= previous.usage.usedOutputTokens) { "新累计预算不能低于已经发生的用量" }
        val usage = NovexLearningUsageLedger.restore(preflight.id, started.usage.maxInputTokens, started.usage.maxOutputTokens,
            previous.usage.usedInputTokens, previous.usage.usedOutputTokens, NovexLearningTaskStatus.INDEXING,
            previous.usage.containsEstimates, previous.usage.containsUnknownLegacyUsage)
        return prepared.copy(task = NovexLearningTaskState.restore(preflight, NovexLearningTaskStatus.INDEXING, usage,
            NovexLearningTaskStatus.INDEXING), preflight = preflight, lastFailure = null,
            previousTasks = if (mode == NovexLearningContinuationMode.CURRENT_SOURCES) prepared.previousTasks + previous else prepared.previousTasks)
    }
}
