package com.openminis.app.data.db

import androidx.room.*
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity

@Entity(tableName = "novex_world_revisions", primaryKeys = ["world_id", "sequence"], foreignKeys = [
    ForeignKey(entity = WorldEntity::class, parentColumns = ["id"], childColumns = ["world_id"], onDelete = ForeignKey.CASCADE),
])
data class NovexWorldRevisionEntity(@ColumnInfo(name = "world_id") val worldId: String, val sequence: Int,
    @ColumnInfo(name = "saved_at") val savedAt: Long, @ColumnInfo(name = "content_json") val contentJson: String)

@Entity(tableName = "novex_game_revisions", primaryKeys = ["project_id", "sequence"], foreignKeys = [
    ForeignKey(entity = InteractiveFictionProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
])
data class NovexGameRevisionEntity(@ColumnInfo(name = "project_id") val projectId: String, val sequence: Int,
    @ColumnInfo(name = "saved_at") val savedAt: Long, @ColumnInfo(name = "content_json") val contentJson: String)

@Dao
interface NovexCardRevisionDao : NovexCardTextReader {
    @Query("SELECT world_id, sequence, saved_at, '' AS content_json FROM novex_world_revisions WHERE world_id = :id ORDER BY sequence") suspend fun worldsRecords(id: String): List<NovexWorldRevisionEntity>
    @Transaction
    suspend fun worlds(id: String): List<NovexWorldRevisionEntity> = worldsRecords(id).map { hydrate(it) }
    @Query("SELECT project_id, sequence, saved_at, '' AS content_json FROM novex_game_revisions WHERE project_id = :id ORDER BY sequence") suspend fun gamesRecords(id: String): List<NovexGameRevisionEntity>
    @Transaction
    suspend fun games(id: String): List<NovexGameRevisionEntity> = gamesRecords(id).map { hydrate(it) }
    @Query("SELECT world_id, sequence, saved_at, '' AS content_json FROM novex_world_revisions WHERE world_id = :id ORDER BY sequence DESC LIMIT 1") suspend fun latestWorldRecords(id: String): NovexWorldRevisionEntity?
    @Transaction
    suspend fun latestWorld(id: String): NovexWorldRevisionEntity? = latestWorldRecords(id)?.let { hydrate(it) }
    @Query("SELECT project_id, sequence, saved_at, '' AS content_json FROM novex_game_revisions WHERE project_id = :id ORDER BY sequence DESC LIMIT 1") suspend fun latestGameRecords(id: String): NovexGameRevisionEntity?
    @Transaction
    suspend fun latestGame(id: String): NovexGameRevisionEntity? = latestGameRecords(id)?.let { hydrate(it) }
    @Insert suspend fun insertWorld(value: NovexWorldRevisionEntity)
    @Insert suspend fun insertGame(value: NovexGameRevisionEntity)
    private suspend fun hydrate(row: NovexWorldRevisionEntity) = row.copy(
        contentJson = requireNotNull(readCardText(NovexCardTextField.WORLD_REVISION, row.worldId, row.sequence)),
    )
    private suspend fun hydrate(row: NovexGameRevisionEntity) = row.copy(
        contentJson = requireNotNull(readCardText(NovexCardTextField.GAME_REVISION, row.projectId, row.sequence)),
    )
}
