package novex.android.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/*
 * Compact markers and context-usage records both anchor to message ids of a
 * session, so their cleanup rule is shared: when message rows go away, every
 * auxiliary row pinned to them must go in the same transaction, expressed as
 * one IN-list deletion per table rather than per-row loops.
 */

@Dao
interface MarkerDao {
    /** Rows are immutable once written; ABORT keeps a duplicated id loud. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addMarker(marker: CompactMarkerRow)

    /** Whole-row rewrite by primary key — the self-heal path swaps anchors in place. */
    @Update
    suspend fun rewriteMarker(marker: CompactMarkerRow)

    @Query("SELECT compact_markers.* FROM compact_markers WHERE compact_markers.session_id = :sessionId ORDER BY compact_markers.created_at DESC LIMIT 1")
    suspend fun newestMarker(sessionId: String): CompactMarkerRow?

    @Query("SELECT compact_markers.* FROM compact_markers WHERE compact_markers.session_id = :sessionId ORDER BY compact_markers.created_at ASC")
    suspend fun markersFor(sessionId: String): List<CompactMarkerRow>

    @Query("DELETE FROM compact_markers WHERE compact_markers.session_id = :sessionId")
    suspend fun dropMarkersFor(sessionId: String)

    /** Returns the number of rows removed; zero means the id was already gone. */
    @Query("DELETE FROM compact_markers WHERE compact_markers.id = :markerId")
    suspend fun dropMarker(markerId: String): Int

    @Query(
        """
        DELETE FROM compact_markers WHERE compact_markers.session_id = :sessionId AND (
            boundary_message_id IN (:messageIds)
            OR first_kept_message_id IN (:messageIds)
            OR last_compacted_message_id IN (:messageIds)
        )
        """,
    )
    suspend fun dropMarkersAnchoredTo(sessionId: String, messageIds: List<String>)
}

@Dao
interface ContextUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordContextUsage(row: ContextUsageRow)

    @Query(
        "SELECT novex_context_usage_records.* FROM novex_context_usage_records " +
            "WHERE novex_context_usage_records.session_id = :sessionId ORDER BY novex_context_usage_records.created_at ASC",
    )
    suspend fun contextUsageFor(sessionId: String): List<ContextUsageRow>

    @Query(
        """
        DELETE FROM novex_context_usage_records WHERE novex_context_usage_records.session_id = :sessionId AND (
            branch_id IN (:messageIds)
            OR request_message_id IN (:messageIds)
            OR response_message_id IN (:messageIds)
        )
        """,
    )
    suspend fun dropContextUsageAnchoredTo(sessionId: String, messageIds: List<String>)
}
