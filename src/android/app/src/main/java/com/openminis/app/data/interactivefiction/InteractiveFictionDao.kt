package com.openminis.app.data.interactivefiction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.openminis.app.data.db.NovexCardTextReader
import com.openminis.app.data.db.NovexCardTextField
import com.openminis.app.data.db.readCardText

@Dao
interface InteractiveFictionDao : NovexCardTextReader {
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
        summary = requireNotNull(readCardText(NovexCardTextField.GAME_SUMMARY, row.id)),
        playerIdentity = requireNotNull(readCardText(NovexCardTextField.GAME_PLAYER, row.id)),
        sourceDocumentJson = readCardText(NovexCardTextField.GAME_SOURCE, row.id),
    )
}
