package com.openminis.app.novex.domain

import com.openminis.app.tools.NovexManagementTools
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Native content execution owns proposal recovery and transactional write/configuration receipts.
 * The host supplies a locked conversation snapshot; permissions remain at the common tool gate. */
class NovexContentToolExecutor(
    private val workspace: NovexWorkspace,
    private val management: NovexManagementService,
    private val cardFiles: NovexCardFileOperations,
    private val transaction: NovexManagementTransaction,
) {
    data class Request(val configuration: NovexConversationConfigurationSnapshot, val userRequests: List<String>,
        val replyId: String, val callId: String, val requestId: String?) {
        val operationId get() = NovexFrozenContextCodec.digest("${configuration.conversationId}|$replyId|$callId")
    }
    data class Result(val tool: ToolExecutionResult, val configuration: NovexConversationConfigurationSnapshot)

    suspend fun execute(name: String, arguments: String, request: Request,
        saveConfiguration: suspend (NovexConversationConfigurationSnapshot) -> Unit): Result {
        var next = request.configuration
        return try {
            val args = JSONObject(arguments)
            var payload: JSONObject? = null
            transaction.run {
                payload = when (name) {
                    NovexManagementTools.PROPOSE -> propose(args, request)
                    NovexManagementTools.APPLY -> {
                        val id = args.getString("proposal_id").trim()
                        val plan = requireNotNull(management.planForExecution(next, id)) { "找不到已保存的内容计划，请重新准备" }
                        val applied = management.apply(next, plan, "")
                        if (!applied.replayed) applied.createdSubjects.forEach { subject ->
                            next = NovexConversationConfiguration.open(next).apply(
                                NovexConversationCommand.MountSubject(subject, ManagedAccess.EDIT)).snapshot
                        }
                        JSONObject().put("proposal_id", id).put("applied_changes", applied.appliedChanges)
                            .put("replayed", applied.replayed)
                            .put("readback_status", "已写入，正文尚未回读核验；请用原对象编号读取，不要重复创建")
                            .put("created_subjects", JSONArray(applied.createdSubjects.map { subject ->
                                JSONObject().put("kind", subject.kind.managementWireName()).put("id", subject.id)
                            }))
                    }
                    else -> {
                        val saved = NovexCardFileService(workspace, management, cardFiles, transaction).execute(next,
                            name, args, request.userRequests, request.operationId, request.requestId)
                        next = saved.configuration
                        saved.payload
                    }
                }
                if (next != request.configuration) saveConfiguration(next)
            }
            Result(ToolExecutionResult(requireNotNull(payload).toString(2), true,
                toolTitle = if (name == NovexManagementTools.PROPOSE) "准备内容变更" else "保存卡片内容"), next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Result(ToolExecutionResult("内容操作未完成：${failure.message ?: "执行失败"}。请核对原对象或原计划的回执，不要改建其他卡片。",
                false, toolTitle = "内容操作未完成"), request.configuration)
        }
    }

    private suspend fun propose(args: JSONObject, request: Request): JSONObject {
        val changes = when (val value = args.opt("changes")) {
            is JSONArray -> value.toString()
            is String -> value
            else -> error("changes（变更列表）必须是数组或数组文本")
        }
        val plan = management.propose(request.configuration, changes, request.userRequests.lastOrNull().orEmpty(),
            request.operationId, request.userRequests.dropLast(1))
        val applied = workspace.conversationDrafts(plan.conversationId)?.completedWrites?.any { it.id == plan.id } == true
        return JSONObject().put("proposal_id", plan.id).put("summary", plan.summary).put("impact", JSONArray(plan.impact))
            .put("already_applied", applied).put("message", if (applied)
                "此计划此前已写入，请用原计划编号读取回执，不要另建卡片。"
                else "计划已保存，正文尚未写入。调用 novex_apply_content_changes（执行内容变更），软件按对话权限处理。")
    }
}
