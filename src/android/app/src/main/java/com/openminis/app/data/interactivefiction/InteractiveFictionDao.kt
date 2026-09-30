package com.openminis.app.data.interactivefiction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import novex.android.data.cards.LargeColumnReader
import novex.android.data.cards.LargeTextField
import novex.android.data.cards.readLargeText

@Dao
interface InteractiveFictionDao : LargeColumnReader {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: InteractiveFictionProjectEntity)

    @Update
    suspend fun update(project: InteractiveFictionProjectEntity)

    @Query("SELECT id, name, '' AS summary, launch_mode, '' AS player_identity, created_at, updated_at, source_id, NULL AS source_document_json FROM interactive_fiction_projects WHERE id = :id")
    suspend fun projectRecord(id: String): InteractiveFictionProjectEntity?

    @Transaction
    suspend fun project(id: String): InteractiveFictionProjectEntity? = projectRecord(id)?.let { hydrate(it) }

    @Query("SELECT id, name, '' AS summary, launch_mode, '' AS player_identity, created_at, updated_at, source_id, NULL AS source_document_json FROM interactive_fiction_projects ORDER BY updated_at DESC, id ASC")
    suspend fun listRecords(): List<InteractiveFictionProjectEntity>

    @Transaction
    suspend fun list(): List<InteractiveFictionProjectEntity> = listRecords().map { hydrate(it) }

    @Query("DELETE FROM interactive_fiction_projects WHERE id = :id")
    suspend fun delete(id: String)
    private suspend fun hydrate(row: InteractiveFictionProjectEntity) = row.copy(
        summary = requireNotNull(readLargeText(LargeTextField.GAME_SUMMARY, row.id)),
        playerIdentity = requireNotNull(readLargeText(LargeTextField.GAME_PLAYER, row.id)),
        sourceDocumentJson = readLargeText(LargeTextField.GAME_SOURCE, row.id),
    )
}
