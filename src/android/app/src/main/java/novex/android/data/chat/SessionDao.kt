package novex.android.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Session-facing halves of the chat store, split by direction:
 * reads observe or fetch rows, writes mutate a single session row or its
 * group membership. The message-tree and marker aspects live beside
 * [MessageDao] and [MarkerDao]; [ChatDao] glues them together for callers
 * that want one handle.
 */

@Dao
interface SessionReads {
    /** Primary conversations only — side forks are hidden from every index surface. */
    @Query("SELECT sessions.* FROM sessions WHERE NOT (sessions.side_of_session IS NOT NULL) ORDER BY sessions.updated_at DESC")
    fun observeSessionIndex(): Flow<List<SessionRow>>

    @Query("SELECT sessions.* FROM sessions WHERE sessions.side_of_session IS NULL ORDER BY sessions.updated_at DESC")
    suspend fun primarySessions(): List<SessionRow>

    @Query("SELECT sessions.* FROM sessions WHERE sessions.side_of_session = :parentSessionId ORDER BY sessions.created_at ASC")
    suspend fun sideSessionsOf(parentSessionId: String): List<SessionRow>

    @Query("SELECT sessions.* FROM sessions WHERE sessions.id = :sessionId")
    suspend fun sessionById(sessionId: String): SessionRow?

    /**
     * Title or body keyword search. The join is outer on purpose so a
     * session whose own title matches is found even with zero messages.
     */
    @Query(
        """
        SELECT DISTINCT sessions.*
        FROM sessions
        LEFT JOIN messages AS m ON sessions.id = m.session_id
        WHERE sessions.title LIKE :pattern OR m.parts_json LIKE :pattern
        ORDER BY sessions.updated_at DESC
        """,
    )
    suspend fun searchSessions(pattern: String): List<SessionRow>

    /** Pinned block first, newest pin on top, then everything else by recency. */
    @Query(
        """
        SELECT sessions.* FROM sessions
        ORDER BY CASE WHEN sessions.pinned_at IS NULL THEN 1 ELSE 0 END ASC,
                 sessions.pinned_at DESC,
                 sessions.updated_at DESC
        """,
    )
    fun observeSessionIndexPinnedFirst(): Flow<List<SessionRow>>
}

@Dao
interface SessionWrites {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(row: SessionRow)

    @Query("UPDATE sessions SET updated_at = :updatedAt, title = :title WHERE sessions.id = :sessionId")
    suspend fun renameSession(sessionId: String, title: String, updatedAt: Long)

    /** A null category argument leaves the stored category alone. */
    @Query(
        "UPDATE sessions SET title = :title, category = COALESCE(:category, sessions.category), " +
            "updated_at = :updatedAt WHERE sessions.id = :sessionId",
    )
    suspend fun renameSessionWithCategory(sessionId: String, title: String, category: String?, updatedAt: Long)

    @Query("UPDATE sessions SET composer_draft = :draftText WHERE sessions.id = :sessionId")
    suspend fun saveComposerDraft(sessionId: String, draftText: String?)

    @Query("UPDATE sessions SET updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long)

    @Query("UPDATE sessions SET last_message = :preview, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun storePreview(sessionId: String, preview: String?, updatedAt: Long)

    @Query("UPDATE sessions SET model_id = :modelId, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun switchSessionModel(sessionId: String, modelId: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE sessions SET updated_at = :updatedAt, model_id = :modelId, model_binding = :binding " +
            "WHERE sessions.id = :sessionId",
    )
    suspend fun rebindSessionModel(
        sessionId: String,
        binding: String,
        modelId: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM sessions WHERE sessions.id = :sessionId")
    suspend fun dropSession(sessionId: String)

    // ---- group membership -------------------------------------------------

    /** Filing a chat is an organizational move: recency must NOT jump. */
    @Query("UPDATE sessions SET folder_id = :folderId WHERE id = :sessionId")
    suspend fun setSessionFolder(sessionId: String, folderId: String?)

    /** Only claims sessions that no group owns yet, directly in the statement. */
    @Query("UPDATE sessions SET folder_id = :folderId WHERE id = :sessionId AND folder_id IS NULL")
    suspend fun claimUnfiledSession(sessionId: String, folderId: String): Int

    @Query("UPDATE sessions SET folder_id = NULL WHERE sessions.folder_id = :folderId")
    suspend fun releaseFolderSessions(folderId: String)

    // ---- single-flag columns ----------------------------------------------

    @Query("UPDATE sessions SET memory_enabled = :enabled, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun setMemoryFlag(sessionId: String, enabled: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET thinking_override = :levelName, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun setThinkingChoice(sessionId: String, levelName: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET pinned_at = :pinnedAt, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun setPinStamp(sessionId: String, pinnedAt: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET source = :source WHERE id = :sessionId")
    suspend fun setSessionSource(sessionId: String, source: String?)

    @Query("UPDATE sessions SET chat_background_path = :backgroundPath WHERE id = :sessionId")
    suspend fun setChatWallpaper(sessionId: String, backgroundPath: String?)

    @Query(
        """
        UPDATE sessions SET
            per_turn_prompt = :perTurnPrompt,
            novex_configuration_json = :novexConfigurationJson,
            player_avatar_path = :playerAvatarPath,
            runtime_dice_enabled = :runtimeDiceEnabled,
            assistant_display_name = :assistantDisplayName,
            chat_background_path = :chatBackgroundPath,
            player_display_name = :playerDisplayName,
            assistant_avatar_path = :assistantAvatarPath,
            conversation_prompt = :conversationPrompt,
            image_style_prompt = :imageStylePrompt,
            role_presentation_enabled = :rolePresentationEnabled,
            runtime_ledger_enabled = :runtimeLedgerEnabled,
            text_style_prompt = :textStylePrompt,
            updated_at = :updatedAt
        WHERE id = :sessionId
        """,
    )
    suspend fun writeConversationSettings(
        sessionId: String,
        conversationPrompt: String?,
        imageStylePrompt: String?,
        perTurnPrompt: String?,
        textStylePrompt: String?,
        runtimeDiceEnabled: Int,
        runtimeLedgerEnabled: Int,
        chatBackgroundPath: String?,
        rolePresentationEnabled: Int,
        assistantDisplayName: String?,
        assistantAvatarPath: String?,
        playerDisplayName: String?,
        playerAvatarPath: String?,
        novexConfigurationJson: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )
}

@Dao
interface FolderReads {
    /** Blank-id rows are skipped: they could never be opened or filed into. */
    @Query("SELECT folders.* FROM folders WHERE folders.id != '' ORDER BY folders.updated_at DESC")
    fun observeFolders(): Flow<List<SessionFolderRow>>

    @Query("SELECT folders.* FROM folders WHERE folders.id != '' ORDER BY folders.updated_at DESC")
    suspend fun allFolders(): List<SessionFolderRow>

    @Query("SELECT folders.* FROM folders WHERE folders.id = :folderId")
    suspend fun folderById(folderId: String): SessionFolderRow?

    @Query("SELECT sessions.id FROM sessions WHERE sessions.folder_id = :folderId ORDER BY sessions.updated_at DESC")
    suspend fun sessionIdsFiledUnder(folderId: String): List<String>

    @Query("SELECT COUNT(*) FROM sessions WHERE sessions.folder_id = :folderId")
    suspend fun sessionCountFiledUnder(folderId: String): Int
}

@Dao
interface FolderWrites {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: SessionFolderRow)

    /**
     * Passing a null description preserves the stored one while the empty
     * string clears it — rename and clear stay distinguishable intents.
     */
    @Query(
        """
        UPDATE folders
        SET updated_at = :updatedAt,
            description = COALESCE(:description, folders.description),
            name = :name
        WHERE folders.id = :folderId
        """,
    )
    suspend fun renameFolder(folderId: String, name: String, description: String?, updatedAt: Long)

    @Query("UPDATE folders SET pinned_at = :pinnedAt, updated_at = :updatedAt WHERE id = :folderId")
    suspend fun setFolderPinStamp(folderId: String, pinnedAt: Long?, updatedAt: Long)

    @Query("DELETE FROM folders WHERE folders.id = :folderId")
    suspend fun dropFolder(folderId: String)
}
