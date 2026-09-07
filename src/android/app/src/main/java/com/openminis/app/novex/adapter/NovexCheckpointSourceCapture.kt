package com.openminis.app.novex.adapter

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.novex.domain.NovexCheckpointSourceEvent

object NovexCheckpointSourceCapture {
    fun capture(conversationId: String, activePathIds: List<String>, rows: List<MessageEntity>): List<NovexCheckpointSourceEvent> {
        require(rows.all { it.sessionId == conversationId }) { "存档原始依据不能混入另一对话" }
        val byId = rows.associateBy { it.id }
        return activePathIds.distinct().mapNotNull { byId[it] }.map { row ->
            NovexCheckpointSourceEvent(row.id, row.role, row.partsJson, row.parentMessageId, row.createdAt, row.updatedAt, row.errorInfo)
        }
    }
}
