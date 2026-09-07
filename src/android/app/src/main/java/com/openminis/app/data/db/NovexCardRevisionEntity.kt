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
interface NovexCardRevisionDao {
    @Query("SELECT * FROM novex_world_revisions WHERE world_id = :id ORDER BY sequence") suspend fun worlds(id: String): List<NovexWorldRevisionEntity>
    @Query("SELECT * FROM novex_game_revisions WHERE project_id = :id ORDER BY sequence") suspend fun games(id: String): List<NovexGameRevisionEntity>
    @Query("SELECT * FROM novex_world_revisions WHERE world_id = :id ORDER BY sequence DESC LIMIT 1") suspend fun latestWorld(id: String): NovexWorldRevisionEntity?
    @Query("SELECT * FROM novex_game_revisions WHERE project_id = :id ORDER BY sequence DESC LIMIT 1") suspend fun latestGame(id: String): NovexGameRevisionEntity?
    @Insert suspend fun insertWorld(value: NovexWorldRevisionEntity)
    @Insert suspend fun insertGame(value: NovexGameRevisionEntity)
}
