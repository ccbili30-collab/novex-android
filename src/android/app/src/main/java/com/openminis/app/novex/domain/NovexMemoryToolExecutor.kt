package com.openminis.app.novex.domain

import com.openminis.app.tools.ToolExecutionResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Scoped native memory operations. The common tool execution gate owns approval, never this handler. */
class NovexMemoryToolExecutor(private val service: NovexMemoryService, private val plans: File) {
    private val lock = locks.computeIfAbsent(plans.canonicalPath) { Any() }
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun exportPlans(directory: File, conversationId: String): Map<String, String> =
            synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
                directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
                    val raw = file.readText()
                    if (JSONObject(raw).getString("conversation_id") == conversationId) file.name to raw else null
                }.toMap()
            }
    }

    fun inspect(scope: NovexMemoryScope, source: NovexMemoryReadContext, arguments: String): ToolExecutionResult = result("查看长期记忆") {
        val args = JSONObject(arguments.ifBlank { "{}" })
        val inspection = service.inspect(scope, source, args.optString("keywords").trim(), args.optInt("limit", 100))
        NovexToolResult.success("memory.ready", "找到 ${inspection.entries.size} 条记忆", data = mapOf(
            "scope" to scope.kind.wireName,
            "entries" to inspection.entries.map { mapOf("memory_ref" to it.ref.value, "content" to it.content,
                "tags" to it.tags, "revision" to it.revision, "updated_at_millis" to it.updatedAtMillis) }),
            affectedRefs = inspection.entries.map { it.ref.asResourceRef() })
    }

    fun propose(scope: NovexMemoryScope, source: NovexMemoryReadContext, sourceBranchId: String,
        sourceMessageId: String?, arguments: String): ToolExecutionResult = result("准备记忆变更") {
        synchronized(lock) {
            val args = JSONObject(arguments)
            val changes = when (val value = args.get("changes")) {
                is String -> value
                is JSONArray -> value.toString()
                else -> error("changes（变更列表）必须是数组或数组文本")
            }
            val plan = service.propose(scope, changes, source, sourceBranchId, sourceMessageId, UUID.randomUUID().toString())
            val stored = JSONObject().put("conversation_id", source.conversationId).put("branch_id", sourceBranchId)
                .put("plan", JSONObject(NovexMemoryPlanCodec.encode(plan)))
            write(path(source.conversationId, plan.id), stored.toString())
            NovexToolResult.success("memory.proposal_ready", plan.summary,
                data = mapOf("proposal_id" to plan.id, "applied" to false),
                nextActions = listOf(NovexToolNextAction("apply_memory_plan", "调用记忆写入工具；软件按对话权限执行或弹窗批准")))
        }
    }

    fun apply(scope: NovexMemoryScope, source: NovexMemoryReadContext, arguments: String): ToolExecutionResult = result("更新长期记忆") {
        synchronized(lock) {
            val id = JSONObject(arguments).getString("proposal_id")
            val plan = load(source, id, scope)
            val applied = service.apply(plan)
            NovexToolResult.success("memory.applied", if (applied.replayed) "这项记忆变更此前已执行，本次未重复写入" else "记忆已保存", data = mapOf("proposal_id" to id,
                "replayed" to applied.replayed, "changed_entries" to if (applied.replayed) 0 else plan.changes.size), affectedRefs = plan.affectedRefs, sideEffect = NovexToolSideEffect.SHARED_WRITE)
        }
    }

    /** Used by the approval popup before executing an immutable proposal id. */
    fun review(scope: NovexMemoryScope, source: NovexMemoryReadContext, proposalId: String): String = synchronized(lock) {
        val plan = load(source, proposalId, scope)
        val existing = service.inspect(scope, source, limit = 500).entries.associateBy { it.ref.value }
        plan.changes.joinToString("\n\n") { change -> when (change) {
            is NovexMemoryChange.Add -> "新增记忆：\n${change.entry.content}"
            is NovexMemoryChange.Replace -> "修改记忆：\n${existing[change.ref.value]?.content.orEmpty()}\n\n修改为：\n${change.content}"
            is NovexMemoryChange.Remove -> "删除记忆：\n${existing[change.ref.value]?.content ?: "该记忆已不存在"}"
        } }
    }

    private fun load(source: NovexMemoryReadContext, id: String, scope: NovexMemoryScope): NovexMemoryPlan {
        val file = path(source.conversationId, id)
        require(file.isFile) { "找不到本对话的记忆计划，请重新准备变更" }
        val saved = JSONObject(file.readText())
        require(saved.getString("conversation_id") == source.conversationId) { "记忆计划不属于当前对话" }
        require(saved.getString("branch_id") in source.activeBranchIds) { "记忆计划来自另一条对话分支，未执行" }
        val plan = NovexMemoryPlanCodec.decode(saved.getJSONObject("plan").toString())
        require(plan.id == id && plan.scope == scope) { "当前回答身份已改变，记忆计划尚未执行" }
        return plan
    }
    private fun path(conversationId: String, id: String): File {
        require(conversationId.isNotBlank() && id.isNotBlank()) { "记忆计划编号不能为空" }
        return File(plans, "${NovexFrozenContextCodec.digest("$conversationId|$id")}.json")
    }
    private fun write(target: File, raw: String) {
        check(plans.isDirectory || plans.mkdirs()) { "无法保存记忆计划" }
        check(!target.exists()) { "记忆计划编号已存在" }
        val temporary = File.createTempFile("memory-plan-", ".pending", plans)
        try {
            java.io.FileOutputStream(temporary).use { it.write(raw.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath()) }
        } finally { temporary.delete() }
    }
    private fun result(title: String, action: () -> NovexToolResult): ToolExecutionResult = try {
        ToolExecutionResult(action().toJson(), true, toolTitle = title)
    } catch (failure: Exception) {
        ToolExecutionResult(NovexToolResult.failure("memory.failed", failure.message ?: "记忆操作未完成").toJson(), false, toolTitle = title)
    }
}
