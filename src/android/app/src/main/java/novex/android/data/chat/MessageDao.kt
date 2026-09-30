package novex.android.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/*
 * The message tree of a conversation: rows carry both a global sort order
 * (append-only, stable across imports) and the parent/child links that
 * record which branch survived. "Active path" means the root/leaf pair the
 * session row pins plus each row's active_child_id pointer along it.
 */

@Dao
interface MessageDao {
    // ---- plain row access -------------------------------------------------

    @Query("SELECT messages.* FROM messages WHERE messages.session_id = :sessionId ORDER BY messages.sort_order ASC")
    suspend fun historyFor(sessionId: String): List<MessageRow>

    @Query("SELECT messages.* FROM messages WHERE messages.session_id = :sessionId ORDER BY messages.sort_order ASC")
    fun observeHistory(sessionId: String): Flow<List<MessageRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(row: MessageRow)

    @Query("SELECT messages.* FROM messages WHERE messages.id = :messageId")
    suspend fun messageById(messageId: String): MessageRow?

    @Query(
        "UPDATE messages SET token_usage = :usageJson, reasoning_content = :reasoningText, parts_json = :partsJson " +
            "WHERE messages.id = :messageId",
    )
    suspend fun overwriteAssistantBody(messageId: String, partsJson: String, usageJson: String?, reasoningText: String?)

    @Query("UPDATE messages SET active_child_id = :childId WHERE messages.id = :messageId")
    suspend fun markActiveChild(messageId: String, childId: String?)

    @Query(
        """
        UPDATE sessions SET
            active_leaf_message_id = :leafId,
            last_message = :preview,
            active_root_message_id = :rootId,
            updated_at = :updatedAt
        WHERE sessions.id = :sessionId
        """,
    )
    suspend fun moveActivePath(
        sessionId: String,
        rootId: String?,
        leafId: String?,
        preview: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM messages WHERE messages.id IN (:messageIds) AND messages.session_id = :sessionId")
    suspend fun dropMessageRows(sessionId: String, messageIds: List<String>)

    @Query("DELETE FROM messages WHERE messages.session_id = :sessionId")
    suspend fun dropAllMessages(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun totalMessageCount(): Int

    @Query("SELECT messages.token_usage FROM messages WHERE messages.token_usage IS NOT NULL AND messages.session_id = :sessionId")
    suspend fun tokenUsageJsonFor(sessionId: String): List<String>

    @Query(
        """
        SELECT s.model_id AS model_id, m.token_usage AS token_usage, m.created_at AS created_at, m.session_id AS session_id
        FROM messages AS m
        LEFT JOIN sessions AS s ON s.id = m.session_id
        WHERE m.token_usage IS NOT NULL
        """,
    )
    suspend fun usageJoinRows(): List<UsageJoinRow>

    @Query("SELECT COUNT(*) FROM messages WHERE messages.session_id = :sessionId")
    suspend fun messageCountIn(sessionId: String): Int

    @Query(
        """
        SELECT messages.* FROM messages
        WHERE messages.session_id = :sessionId
          AND (:endMs IS NULL OR messages.created_at <= :endMs)
          AND (:startMs IS NULL OR messages.created_at >= :startMs)
        ORDER BY messages.sort_order ASC, messages.created_at ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun messagePageBetween(
        sessionId: String,
        offset: Int,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<MessageRow>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE messages.session_id = :sessionId
          AND (:startMs IS NULL OR messages.created_at >= :startMs)
          AND (:endMs IS NULL OR messages.created_at <= :endMs)
        """,
    )
    suspend fun messageCountBetween(sessionId: String, startMs: Long?, endMs: Long?): Int

    @Query(
        """
        SELECT messages.* FROM messages
        WHERE messages.session_id = :sessionId
        ORDER BY messages.sort_order ASC, messages.created_at ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun messagePage(sessionId: String, offset: Int, limit: Int): List<MessageRow>

    @Query(
        "SELECT messages.* FROM messages WHERE messages.created_at > :since AND messages.role = 'user' " +
            "ORDER BY messages.created_at ASC LIMIT :limit",
    )
    suspend fun userMessagesSince(since: Long, limit: Int): List<MessageRow>

    @Query("UPDATE messages SET stream_interrupt_count = messages.stream_interrupt_count + 1, updated_at = :updatedAt WHERE messages.id = :messageId")
    suspend fun bumpInterruptCounter(messageId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET error_info = :errorInfo WHERE messages.id = :messageId")
    suspend fun setMessageSticker(messageId: String, errorInfo: String?)

    // ---- active-path state --------------------------------------------------

    @Query(
        """
        SELECT sessions.active_root_message_id AS active_root_message_id,
               sessions.active_leaf_message_id AS active_leaf_message_id
        FROM sessions WHERE sessions.id = :sessionId
        """,
    )
    suspend fun branchAnchorsOf(sessionId: String): BranchAnchors?

    @Query(
        """
        SELECT leaf.parts_json FROM messages AS leaf
        INNER JOIN sessions AS s ON s.active_leaf_message_id = leaf.id
        WHERE s.id = :sessionId
        LIMIT 1
        """,
    )
    suspend fun activeLeafBody(sessionId: String): String?

    @Query(
        """
        SELECT leaf.session_id AS session_id, leaf.role AS role, leaf.parts_json AS parts_json
        FROM messages AS leaf
        INNER JOIN sessions AS s ON s.active_leaf_message_id = leaf.id
        """,
    )
    suspend fun sessionTails(): List<SessionTailProjection>

    @Query("SELECT 1 + COALESCE((SELECT MAX(messages.sort_order) FROM messages WHERE messages.session_id = :sessionId), -1)")
    suspend fun nextSortOrderWithin(sessionId: String): Int

    // ---- dynamic query escape hatches ----------------------------------------

    /** Runs a caller-composed sessions query; columns must match [SessionMetaProjection]. */
    @RawQuery
    suspend fun runSessionMetaQuery(query: SupportSQLiteQuery): List<SessionMetaProjection>

    /** Runs a caller-composed message query; columns must match [MessageHitProjection]. */
    @RawQuery
    suspend fun runMessageSearch(query: SupportSQLiteQuery): List<MessageHitProjection>
}
