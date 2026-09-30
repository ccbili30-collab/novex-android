package novex.android.adapter

import novex.android.data.chat.MessageRow
import novex.core.NovexCheckpointSourceEvent

object NovexCheckpointSourceCapture {
    fun capture(conversationId: String, activePathIds: List<String>, rows: List<MessageRow>): List<NovexCheckpointSourceEvent> {
        require(rows.all { it.sessionId == conversationId }) { "存档原始依据不能混入另一对话" }
        val byId = rows.associateBy { it.id }
        return activePathIds.distinct().mapNotNull { byId[it] }.map { row ->
            NovexCheckpointSourceEvent(row.id, row.role, row.partsJson, row.parentMessageId, row.createdAt, row.updatedAt, row.errorInfo)
        }
    }
}
