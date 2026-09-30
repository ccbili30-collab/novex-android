package novex.android.data.chat

import androidx.room.Transaction

/*
 * One handle over every chat-table concern. Room flattens interface
 * inheritance at processing time, so splitting the SQL surface into aspect
 * interfaces is an authoring-time decomposition — callers keep dealing
 * with a single `chatDao()` accessor. The compound operations below live
 * here (not in one of the aspects) precisely because they touch several:
 * appending a turn writes a message row, a parent pointer, and the
 * session's path anchors in one transaction.
 */
@androidx.room.Dao
interface ChatDao :
    SessionReads,
    SessionWrites,
    FolderReads,
    FolderWrites,
    MessageDao,
    MarkerDao,
    ContextUsageDao {
    /**
     * Appends a row under the session's current leaf and advances the
     * persisted tail to it. When no path exists yet the new row becomes
     * both root and leaf.
     */
    @Transaction
    suspend fun appendOnActivePath(row: MessageRow, preview: String?, updatedAt: Long): MessageRow {
        val anchors = branchAnchorsOf(row.sessionId)
        val parentId = anchors?.activeLeafMessageId
        val persisted = row.copy(
            sortOrder = nextSortOrderWithin(row.sessionId),
            parentMessageId = parentId,
            activeChildId = null,
        )
        upsertMessage(persisted)
        parentId?.let { markActiveChild(it, persisted.id) }
        moveActivePath(
            sessionId = row.sessionId,
            rootId = anchors?.activeRootMessageId ?: persisted.id,
            leafId = persisted.id,
            preview = preview,
            updatedAt = updatedAt,
        )
        return persisted
    }

    /**
     * Persists progress of a streaming assistant turn. The first checkpoint
     * of a turn appends it; later ones rewrite its body in place so the row
     * keeps the graph slot it was born with. The session preview follows
     * only while this turn is the active tail.
     */
    @Transaction
    suspend fun checkpointRunningTurn(row: MessageRow, preview: String?, updatedAt: Long): MessageRow {
        require(row.role == "assistant") { "只有助手回合可以流式落盘" }
        val stored = messageById(row.id)
            ?: return appendOnActivePath(row, preview, updatedAt)
        require(stored.sessionId == row.sessionId && stored.role == "assistant") { "对话记录归属不一致" }
        overwriteAssistantBody(row.id, row.partsJson, row.tokenUsage, row.reasoningContent)
        if (branchAnchorsOf(row.sessionId)?.activeLeafMessageId == row.id) {
            storePreview(row.sessionId, preview, updatedAt)
        }
        return stored.copy(partsJson = row.partsJson, tokenUsage = row.tokenUsage, reasoningContent = row.reasoningContent)
    }

    /** Applies a pure graph edit: child pointers, optional deletions, then the new path. */
    @Transaction
    suspend fun applyBranchMutation(
        sessionId: String,
        rootId: String?,
        leafId: String?,
        childUpdates: Map<String, String?>,
        deletedMessageIds: Set<String>,
        preview: String?,
        updatedAt: Long,
    ) {
        for ((messageId, childId) in childUpdates) {
            markActiveChild(messageId, childId)
        }
        deletedMessageIds.takeIf { it.isNotEmpty() }?.toList()?.let { doomed ->
            dropMarkersAnchoredTo(sessionId, doomed)
            dropContextUsageAnchoredTo(sessionId, doomed)
            dropMessageRows(sessionId, doomed)
        }
        moveActivePath(sessionId, rootId, leafId, preview, updatedAt)
    }

    /** Full teardown of a conversation's rows; groups and drafts are untouched. */
    @Transaction
    suspend fun removeConversation(sessionId: String) {
        dropAllMessages(sessionId)
        dropSession(sessionId)
    }
}
