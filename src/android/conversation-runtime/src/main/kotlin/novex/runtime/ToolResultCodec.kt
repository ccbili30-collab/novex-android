package novex.runtime

import org.json.JSONObject

/** 统一解释新协议及旧卡工具日志；恢复只报告真实记录，不推测执行成功。 */
object ToolResultCodec {
    fun decode(value: JSONObject): CardToolResult {
        fun optional(key: String): String? = if(value.isNull(key)) null else value.getString(key)
        val legacy=!value.has("status")
        return when(value.optString(if(legacy) "kind" else "status")) {
            "read" -> CardToolResult.Read(value.toString())
            "saved" -> CardToolResult.Saved(value.getString(if(legacy) "root" else "root_id"), value.getString(if(legacy) "target" else "target_id"), value.getString("revision"))
            "registered" -> CardToolResult.Registered(value.getString("conversation_id"), value.getString("registration_id"))
            "state_saved" -> CardToolResult.StateSaved(value.getString("conversation_id"), value.getString("event_id"))
            "checkpoint_saved" -> CardToolResult.CheckpointSaved(value.getString("conversation_id"), value.getString("checkpoint_id"))
            "denied" -> CardToolResult.Denied
            "awaiting_approval" -> CardToolResult.AwaitingApproval
            "stopped" -> CardToolResult.Stopped(optional(if(legacy) "draft" else "preserved_draft"))
            "failed" -> CardToolResult.Failed(value.getString("reason"),optional(if(legacy) "draft" else "preserved_draft"))
            else -> CardToolResult.Unconfirmed
        }
    }
}
