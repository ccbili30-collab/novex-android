package novex.android.data.chat

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Row types for the conversation core of the main Novex database
 * (file "minis.db", schema version 39).
 *
 * Everything the persisted schema observes — table names, column names,
 * column order, nullability, defaults, indexes, foreign keys — is pinned
 * deliberately with explicit @Entity / @ColumnInfo / Index declarations so
 * that reorganizing Kotlin code can never drift the on-disk layout. Each
 * row class below names its table in the KDoc; the database file version
 * history lives in MainDatabaseMigrations.kt.
 */

/** One row of `sessions` — the root aggregate of a conversation. */
@Entity(
    tableName = "sessions",
    indices = [Index(value = ["folder_id"], name = "index_sessions_folder_id")],
)
data class SessionRow(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    /** Where the session came from, e.g. a launcher shortcut or a share sheet. */
    @ColumnInfo(name = "source") val source: String? = null,
    /** 1 = memory features on for this session, 0 = off. */
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,
    /** Epoch-millis pin stamp; null leaves the row in the unpinned section. */
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,
    /** Explicit per-session thinking choice; null = inherit the model default. */
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,
    /**
     * Owning group, or null for ungrouped. Intentionally a bare column with
     * no foreign key: a group id that is not present locally must still open
     * (it renders as ungrouped) instead of tripping a constraint.
     */
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "character_id") val characterId: String? = null,
    @ColumnInfo(name = "character_snapshot_json") val characterSnapshotJson: String? = null,
    @ColumnInfo(name = "world_snapshot_json") val worldSnapshotJson: String? = null,
    @ColumnInfo(name = "persona_id") val personaId: String? = null,
    @ColumnInfo(name = "persona_snapshot_json") val personaSnapshotJson: String? = null,
    @ColumnInfo(name = "world_id") val worldId: String? = null,
    @ColumnInfo(name = "character_version_id") val characterVersionId: String? = null,
    /** null inherits the snapshot default; the empty string clears it. */
    @ColumnInfo(name = "chat_background_path") val chatBackgroundPath: String? = null,
    @ColumnInfo(name = "conversation_prompt") val conversationPrompt: String? = null,
    @ColumnInfo(name = "image_style_prompt") val imageStylePrompt: String? = null,
    @ColumnInfo(name = "per_turn_prompt") val perTurnPrompt: String? = null,
    @ColumnInfo(name = "text_style_prompt") val textStylePrompt: String? = null,
    @ColumnInfo(name = "runtime_dice_enabled") val runtimeDiceEnabled: Int = 0,
    @ColumnInfo(name = "runtime_ledger_enabled") val runtimeLedgerEnabled: Int = 0,
    @ColumnInfo(name = "role_presentation_enabled") val rolePresentationEnabled: Int = 0,
    @ColumnInfo(name = "assistant_display_name") val assistantDisplayName: String? = null,
    @ColumnInfo(name = "assistant_avatar_path") val assistantAvatarPath: String? = null,
    @ColumnInfo(name = "player_display_name") val playerDisplayName: String? = null,
    @ColumnInfo(name = "player_avatar_path") val playerAvatarPath: String? = null,
    /** Serialized Novex conversation configuration; typed again at the repository boundary. */
    @ColumnInfo(name = "novex_configuration_json") val novexConfigurationJson: String? = null,
    @ColumnInfo(name = "active_root_message_id") val activeRootMessageId: String? = null,
    @ColumnInfo(name = "active_leaf_message_id") val activeLeafMessageId: String? = null,
    /** Composer text kept across process death; never part of model context. */
    @ColumnInfo(name = "composer_draft") val composerDraft: String? = null,
    /** Non-null marks a hidden side session forked from that parent id. */
    @ColumnInfo(name = "side_of_session") val sideOfSession: String? = null,
)

/** One row of `messages` — a turn in the retained conversation tree. */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionRow::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "sort_order"], name = "index_messages_session_id_sort_order"),
        Index(value = ["session_id", "parent_message_id"], name = "index_messages_session_id_parent_message_id"),
    ],
)
data class MessageRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "token_usage") val tokenUsage: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    @ColumnInfo(name = "stream_interrupt_count") val streamInterruptCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    /** Terminal error sticker persisted on an assistant turn; null = clean. */
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    @ColumnInfo(name = "parent_message_id") val parentMessageId: String? = null,
    @ColumnInfo(name = "active_child_id") val activeChildId: String? = null,
)

/** One row of `compact_markers` — an append-only archival summary boundary. */
@Entity(
    tableName = "compact_markers",
    foreignKeys = [
        ForeignKey(
            entity = SessionRow::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id"], name = "index_compact_markers_session_id"),
        Index(value = ["first_kept_message_id"], name = "index_compact_markers_first_kept_message_id"),
    ],
)
data class CompactMarkerRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val summary: String,
    @ColumnInfo(name = "first_kept_sort_order") val firstKeptSortOrder: Int,
    @ColumnInfo(name = "compacted_count") val compactedCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "ui_boundary_sort_order") val uiBoundarySortOrder: Int? = null,
    @ColumnInfo(name = "boundary_message_id") val boundaryMessageId: String? = null,
    /** Id-first boundary preferred over the legacy sort-order fields when present. */
    @ColumnInfo(name = "first_kept_message_id") val firstKeptMessageId: String? = null,
    @ColumnInfo(name = "last_compacted_message_id") val lastCompactedMessageId: String? = null,
    /** 1 = legacy multi-field marker, 2+ = id-anchored marker model. */
    val version: Int = 1,
    @ColumnInfo(name = "history_scope_key") val historyScopeKey: String? = null,
)

/**
 * One row of `folders` — a session group. Code calls it a folder, the UI
 * calls it a group, mirroring the deliberate upstream split because
 * "ModelGroup" already means fallback routing at symbol level.
 */
@Entity(tableName = "folders")
data class SessionFolderRow(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    /** "manual" | "ai" — provenance only, nothing branches on it. */
    val origin: String = MANUAL_ORIGIN,
    /** Reserved for drag-reorder; always 0 today. */
    @ColumnInfo(name = "sort_index") val sortIndex: Int = 0,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    val description: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Record-edit stamp; member moves deliberately leave it untouched. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val isPinned: Boolean get() = pinnedAt != null

    companion object {
        const val MANUAL_ORIGIN = "manual"
        const val AI_ORIGIN = "ai"

        /** Description length cap kept in step with the iOS sibling. */
        const val DESCRIPTION_MAX_CHARS = 100
    }
}

/** One row of `novex_context_usage_records` — provenance of one request's structured sources. */
@Entity(
    tableName = "novex_context_usage_records",
    foreignKeys = [
        ForeignKey(
            entity = SessionRow::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "created_at"], name = "index_novex_context_usage_session"),
        Index(value = ["session_id", "request_message_id"], name = "index_novex_context_usage_request"),
    ],
)
data class ContextUsageRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "request_message_id") val requestMessageId: String,
    @ColumnInfo(name = "response_message_id") val responseMessageId: String?,
    @ColumnInfo(name = "branch_id") val branchId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One row of `novex_conversation_drafts` — a draft outliving its session, by design no cascade. */
@Entity(tableName = "novex_conversation_drafts")
data class ConversationDraftRow(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

@Dao
interface ConversationDraftDao {
    @Query("SELECT * FROM novex_conversation_drafts WHERE conversation_id = :conversationId")
    suspend fun find(conversationId: String): ConversationDraftRow?

    @Query("SELECT * FROM novex_conversation_drafts")
    suspend fun all(): List<ConversationDraftRow>

    @Query("SELECT novex_conversation_drafts.* FROM novex_conversation_drafts")
    fun observeAll(): Flow<List<ConversationDraftRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ConversationDraftRow)
}
