package com.openminis.app.novex.adapter

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.attachments.containsAgentAttachmentMetadata
import com.openminis.app.data.attachments.stripAgentAttachmentMetadata
import com.openminis.app.data.repository.ChatRepository
import org.json.JSONArray

/**
 * Reads host-owned rows from the repository's selected conversation path, not
 * the model's compacted history. Attachment receipts never become authorization.
 * An empty real user turn remains a boundary: do not fall back to an older OK.
 */
object NovexManagementUserRequests {
    fun fromActiveMessages(rows: List<MessageEntity>): List<String> {
        require(rows.all { it.sessionId == rows.first().sessionId }) { "用户请求不能跨对话合并" }
        val requests = mutableListOf<String>()
        var offeredChoices = emptySet<String>()
        for (row in rows) {
            if (row.role == "assistant") {
                offeredChoices = runCatching {
                    val parts = JSONArray(row.partsJson)
                    (0 until parts.length()).flatMap { index ->
                        val part = parts.getJSONObject(index)
                        val value = part.optJSONObject("value")
                        if (part.optString("type") !in setOf("toolUse", "uiToolUse") || value?.optString("name") != "present_choices") emptyList()
                        else {
                            val args = JSONObject(value.getString("input"))
                            val choices = JSONArray(args.getString("choices"))
                            (0 until choices.length()).map { choices.getString(it).trim() }
                        }
                    }.toSet()
                }.getOrDefault(emptySet())
                continue
            }
            if (row.role != "user") continue
            val values = runCatching {
                val parts = JSONArray(row.partsJson)
                (0 until parts.length()).map { parts.getJSONObject(it) }
            }.getOrNull()
            if (values == null) {
                // Corrupt user rows cannot be silently skipped to resurrect an
                // earlier task or confirmation. A later explicit request can recover.
                requests.clear()
                requests += ""
                continue
            }
            if (values.any { it.optString("type") == "toolResult" }) continue
            val texts = values.filter { it.optString("type") == "text" }
                .mapNotNull { it.opt("value") as? String }
            val caption = texts.joinToString("\n") { text ->
                val clean = ChatRepository.stripSystemReminders(stripAgentAttachmentMetadata(text))
                // Fail closed for incomplete internal envelopes as well.
                if (containsAgentAttachmentMetadata(clean) || clean.contains("<system-reminder>")) "" else clean
            }.trim()
            val reminderOnly = caption.isEmpty() && texts.any { it.contains("<system-reminder>") } &&
                values.all { it.optString("type") == "text" } && texts.none(::containsAgentAttachmentMetadata)
            if (!reminderOnly) {
                // Only a real user's exact selection can continue the existing creation scope.
                // This host interpretation is never written back over the user's original message.
                val selectsExecution = caption in offeredChoices &&
                    Regex("(打包|按.{0,40}(运行|制作|生成|整理)|照.{0,40}(制作|整理))").containsMatchIn(caption) &&
                    !caption.startsWith("确认执行 ")
                requests += if (selectsExecution) "$caption\n继续此前创建任务" else caption
                offeredChoices = emptySet()
            }
        }
        return requests
    }
}
