package com.openminis.app.novex.domain

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

enum class NovexLearningPlanAction(val wireName: String) {
    START("start"), CONTINUE("continue"), RECHECK("recheck");
    companion object {
        fun parse(value: String) = entries.firstOrNull { it.wireName == value }
            ?: error("action（计划用途）只能是 start（首次整理）、continue（继续）、recheck（重新核对来源）")
    }
}

/** Durable, exact learning plans shared by model tools and native controls. No approval UI or model calls here. */
class NovexLearningExecutionPlans(
    private val repository: NovexLearningRepository,
    private val documents: NovexDocumentSnapshotStore,
    private val directory: File,
) {
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private data class Plan(val preflight: NovexLearningPreflightSnapshot, val action: NovexLearningPlanAction)
    data class Commit(val state: NovexLearningState, val replayed: Boolean)

    fun prepare(conversationId: String, ref: NovexResourceRef, action: NovexLearningPlanAction,
        allowed: (NovexResourceRef) -> Boolean,
        build: (NovexLearningState, NovexLearningTokenBudget?, String?) -> NovexLearningPreflightSnapshot,
    ): NovexLearningPreflightSnapshot = synchronized(lock) {
        require(allowed(ref)) { "当前对话分支不再包含这份资料集" }
        val stored = requireNotNull(repository.find(ref)) { "找不到待整理的资料集" }
        if (action == NovexLearningPlanAction.START && stored.task != null) {
            return@synchronized stored.task.preflight.copy(taskStatus = stored.task.status)
        }
        val mode = action.continuationMode()
        val prepared = if (mode == null) stored else NovexLearningContinuation.prepareState(stored, documents, mode)
        val task = stored.task
        val budget = task?.usage?.let { usage ->
            val extend = task.status == NovexLearningTaskStatus.PAUSED_BUDGET_REACHED || action == NovexLearningPlanAction.RECHECK
            NovexLearningTokenBudget(
                if (extend) (maxOf(usage.maxInputTokens.toLong(), usage.usedInputTokens.toLong()) + 64_000L).coerceAtMost(10_000_000L).toInt() else usage.maxInputTokens,
                if (extend) (maxOf(usage.maxOutputTokens.toLong(), usage.usedOutputTokens.toLong()) + 8_000L).coerceAtMost(1_000_000L).toInt() else usage.maxOutputTokens,
            )
        }
        val preflight = build(prepared, budget, mode?.let { NovexLearningContinuation.originFingerprint(stored) })
        require(preflight.collectionRef == ref && allowed(ref)) { "资料范围已变化，请重新准备计划" }
        val raw = JSONObject().put("conversation_id", conversationId).put("action", action.wireName)
            .put("preflight", NovexLearningStateJsonCodec.encodePreflight(preflight)).toString()
        val file = path(conversationId, preflight.id)
        if (file.exists()) {
            // A plan id is content-addressed; never overwrite something already presented for approval.
            val previous = load(conversationId, ref, preflight.id, allowed)
            require(previous == Plan(preflight, action)) { "计划编号冲突，请重新准备" }
        } else writeNovexAtomicFile(file, raw)
        if (stored.task == null) repository.save(stored.copy(preflight = preflight))
        preflight
    }

    fun review(conversationId: String, ref: NovexResourceRef, id: String,
        allowed: (NovexResourceRef) -> Boolean): String = synchronized(lock) {
        NovexLearningControlPolicy.preflightMessage(load(conversationId, ref, id, allowed).preflight)
    }

    /** Called only after the common tool gate, or an explicit native button action. Saves before scheduling work. */
    fun commit(conversationId: String, ref: NovexResourceRef, id: String,
        allowed: (NovexResourceRef) -> Boolean,
        build: (NovexLearningState, NovexLearningTokenBudget?, String?) -> NovexLearningPreflightSnapshot,
        requireContext: (NovexLearningPreflightSnapshot) -> Unit,
    ): Commit = synchronized(lock) {
        val plan = load(conversationId, ref, id, allowed)
        val stored = requireNotNull(repository.find(ref)) { "找不到待整理的资料集" }
        // A lost tool receipt must not reset progress, budget, pause or cancellation.
        if (stored.task?.preflight?.id == id || stored.previousTasks.any { it.preflight.id == id }) {
            return@synchronized Commit(stored, true)
        }
        val mode = plan.action.continuationMode()
        require(mode != null || stored.task == null) { "资料已有整理任务；请读取进度后准备续接计划" }
        val prepared = if (mode == null) stored else NovexLearningContinuation.prepareState(stored, documents, mode)
        val refreshed = build(prepared, plan.preflight.confirmedBudget,
            mode?.let { NovexLearningContinuation.originFingerprint(stored) })
        require(refreshed.id == plan.preflight.id) { "资料、进度、模型或预算已变化，本计划未执行；请重新调用 learning_prepare（准备资料整理）" }
        require(refreshed.requiresConfirmation) { "资料可以直接读取，无需后台整理；请使用文档读取工具" }
        requireContext(refreshed)
        require(allowed(ref)) { "当前对话分支不再包含这份资料集" }
        val confirmation = NovexLearningConfirmation(refreshed.id, refreshed.modelId, refreshed.sourceRefs,
            refreshed.confirmedBudget.inputTokens, refreshed.confirmedBudget.outputTokens, System.currentTimeMillis())
        val next = if (mode == null) stored.copy(preflight = refreshed,
            task = NovexLearningCoordinator().start(refreshed, confirmation))
        else NovexLearningContinuation.confirm(stored, prepared, mode, refreshed, confirmation)
        repository.save(next)
        Commit(requireNotNull(repository.find(ref)), false)
    }

    private fun load(conversationId: String, ref: NovexResourceRef, id: String,
        allowed: (NovexResourceRef) -> Boolean): Plan {
        require(allowed(ref)) { "当前对话分支不再包含这份资料集" }
        val file = path(conversationId, id)
        require(file.isFile) { "找不到本对话的已保存整理计划，请重新准备" }
        val raw = JSONObject(file.readText())
        val preflight = NovexLearningStateJsonCodec.decodePreflight(raw.getJSONObject("preflight"))
        require(raw.getString("conversation_id") == conversationId && preflight.collectionRef == ref && preflight.id == id) {
            "整理计划不属于当前对话或资料集"
        }
        return Plan(preflight, NovexLearningPlanAction.parse(raw.getString("action")))
    }
    private fun path(conversationId: String, id: String): File {
        require(conversationId.isNotBlank() && id.isNotBlank()) { "对话和计划编号不能为空" }
        val digest = MessageDigest.getInstance("SHA-256").digest("$conversationId|$id".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$digest.json")
    }
    private fun NovexLearningPlanAction.continuationMode() = when (this) {
        NovexLearningPlanAction.START -> null
        NovexLearningPlanAction.CONTINUE -> NovexLearningContinuationMode.CURRENT_SOURCES
        NovexLearningPlanAction.RECHECK -> NovexLearningContinuationMode.RECHECK_SOURCES
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        fun exportPlans(directory: File, conversationId: String): Map<String, String> =
            synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
                directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
                    val raw = file.readText()
                    if (JSONObject(raw).getString("conversation_id") == conversationId) file.name to raw else null
                }.toMap()
            }
    }
}
