package com.openminis.app.novex.domain

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.novex.adapter.NovexManagementUserRequests
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

data class NovexPendingToolCall(val id: String, val name: String, val arguments: String)
data class NovexPendingToolTurn(val replyId: String, val requestId: String?, val calls: List<NovexPendingToolCall>) {
    companion object {
        /** Uses raw active-path rows, never model summaries or inferred intent. */
        fun find(rows: List<MessageEntity>): NovexPendingToolTurn? {
            val lastAssistant = rows.indexOfLast { it.role == "assistant" }
            if (lastAssistant < 0) return null
            if (NovexManagementUserRequests.fromActiveMessages(rows.drop(lastAssistant + 1)).isNotEmpty()) return null
            val reply = rows[lastAssistant]
            val parts = JSONArray(reply.partsJson)
            val completedIds = rows.drop(lastAssistant + 1).flatMap { row ->
                val values = JSONArray(row.partsJson)
                (0 until values.length()).mapNotNull { index ->
                    values.getJSONObject(index).takeIf { it.optString("type") == "toolResult" }
                        ?.getJSONObject("value")?.getString("toolUseId")
                }
            }.toSet()
            val calls = (0 until parts.length()).mapNotNull { index ->
                val part = parts.getJSONObject(index)
                if (part.optString("type") !in setOf("toolUse", "uiToolUse")) return@mapNotNull null
                val value = part.getJSONObject("value")
                val id = value.getString("toolUseId")
                if (id in completedIds) return@mapNotNull null
                val args = if (value.has("executionInput") && !value.isNull("executionInput"))
                    value.getString("executionInput") else value.getString("input")
                JSONObject(args) // Refuse incomplete persisted input; do not repair a write on restart.
                NovexPendingToolCall(id, value.getString("name"), args)
            }
            if (calls.isEmpty()) return null
            val request = rows.take(lastAssistant).lastOrNull {
                it.role == "user" && NovexManagementUserRequests.fromActiveMessages(listOf(it)).isNotEmpty()
            }
            return NovexPendingToolTurn(reply.id, request?.id, calls)
        }
    }

    /** The supplied invoker is the normal live executor, including its exact-call approval gate. */
    suspend fun recover(
        invoke: suspend (NovexPendingToolCall) -> ToolExecutionResult,
        persist: suspend (NovexPendingToolCall, ToolExecutionResult) -> Unit,
    ): Boolean {
        var terminal = false
        for (call in calls) {
            val result = invoke(call)
            if (call.name == "present_choices" && result.success) terminal = true
            else persist(call, result)
        }
        return terminal
    }
}
