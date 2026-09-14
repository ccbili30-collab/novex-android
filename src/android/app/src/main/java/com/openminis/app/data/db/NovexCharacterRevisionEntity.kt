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
interface NovexCharacterRevisionDao : NovexCardTextReader {
    @Query("SELECT version_id, sequence, saved_at, '' AS content_json FROM novex_character_revisions WHERE version_id = :versionId ORDER BY sequence")
    suspend fun listRecords(versionId: String): List<NovexCharacterRevisionEntity>
    @Transaction
    suspend fun list(versionId: String): List<NovexCharacterRevisionEntity> = listRecords(versionId).map { hydrate(it) }
    @Query("SELECT version_id, sequence, saved_at, '' AS content_json FROM novex_character_revisions WHERE version_id = :versionId ORDER BY sequence DESC LIMIT 1")
    suspend fun latestRecords(versionId: String): NovexCharacterRevisionEntity?
    @Transaction
    suspend fun latest(versionId: String): NovexCharacterRevisionEntity? = latestRecords(versionId)?.let { hydrate(it) }
    @Insert
    suspend fun insert(revision: NovexCharacterRevisionEntity)
    private suspend fun hydrate(row: NovexCharacterRevisionEntity) = row.copy(
        contentJson = requireNotNull(readCardText(NovexCardTextField.ROLE_REVISION, row.versionId, row.sequence)),
    )
}
