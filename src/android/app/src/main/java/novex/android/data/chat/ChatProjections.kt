package novex.android.data.chat

import androidx.room.ColumnInfo

/*
 * Query-shaped projections used by the chat DAOs. These are not tables:
 * each one maps the columns of a specific SELECT shape (including the
 * aliases that shape emits), which is why the @ColumnInfo values below
 * must move together with the SQL that produces them.
 */

/**
 * Result shape of the dynamic sessions-meta query. The WHERE fragments are
 * assembled by the caller (variable keyword counts and IN lists do not fit
 * a static @Query), so the DAO exposes a [androidx.room.RawQuery] entry
 * point and relies on Room's bind-by-column-name matching against these
 * names.
 */
data class SessionMetaProjection(
    val id: String,
    val title: String?,
    @ColumnInfo(name = "first_user_msg") val firstUserMsg: String?,
    val source: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "msg_count") val msgCount: Int,
)

/** Result shape of the dynamic message-search query (same RawQuery pattern). */
data class MessageHitProjection(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val id: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

/** The two persisted anchor ids that restore a session's active branch. */
data class BranchAnchors(
    @ColumnInfo(name = "active_root_message_id") val activeRootMessageId: String?,
    @ColumnInfo(name = "active_leaf_message_id") val activeLeafMessageId: String?,
)

/**
 * Per-session last message (role + body) in one round trip — enough to mark
 * sessions whose agent loop was left interrupted after a hard process death.
 */
data class SessionTailProjection(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
)

/**
 * Token-usage row joined onto its session's model id. [modelId] is nullable
 * because the join is deliberately outer: an orphaned message still carries
 * billing facts that must show up in totals, grouped under "Unknown".
 */
data class UsageJoinRow(
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "token_usage") val tokenUsage: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
)
