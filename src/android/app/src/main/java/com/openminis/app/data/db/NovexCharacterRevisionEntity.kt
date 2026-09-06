package com.openminis.app.data.db

import androidx.room.*
import com.openminis.app.data.character.CharacterVersionEntity

@Entity(tableName = "novex_character_revisions", primaryKeys = ["version_id", "sequence"], foreignKeys = [
    ForeignKey(entity = CharacterVersionEntity::class, parentColumns = ["id"], childColumns = ["version_id"], onDelete = ForeignKey.CASCADE),
])
data class NovexCharacterRevisionEntity(
    @ColumnInfo(name = "version_id") val versionId: String,
    val sequence: Int,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

@Dao
interface NovexCharacterRevisionDao {
    @Query("SELECT * FROM novex_character_revisions WHERE version_id = :versionId ORDER BY sequence")
    suspend fun list(versionId: String): List<NovexCharacterRevisionEntity>
    @Query("SELECT * FROM novex_character_revisions WHERE version_id = :versionId ORDER BY sequence DESC LIMIT 1")
    suspend fun latest(versionId: String): NovexCharacterRevisionEntity?
    @Insert
    suspend fun insert(revision: NovexCharacterRevisionEntity)
}
