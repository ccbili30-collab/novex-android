package com.openminis.app.data.db

import androidx.room.*
import com.openminis.app.data.character.CharacterVersionEntity

@Entity(tableName = "novex_character_version_relations", foreignKeys = [
    ForeignKey(entity = CharacterVersionEntity::class, parentColumns = ["id"], childColumns = ["source_version_id"], onDelete = ForeignKey.CASCADE),
], indices = [
    Index(value = ["character_id"], name = "index_novex_version_relations_character"),
    Index(value = ["source_version_id"], name = "index_novex_version_relations_source"),
    Index(value = ["target_version_id"], name = "index_novex_version_relations_target"),
])
data class NovexCharacterVersionRelationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "source_version_id") val sourceVersionId: String,
    @ColumnInfo(name = "target_version_id") val targetVersionId: String,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

@Dao
interface NovexCharacterVersionRelationDao {
    @Query("SELECT * FROM novex_character_version_relations WHERE id = :id")
    suspend fun get(id: String): NovexCharacterVersionRelationEntity?
    @Query("SELECT * FROM novex_character_version_relations WHERE character_id = :characterId ORDER BY id")
    suspend fun forCharacter(characterId: String): List<NovexCharacterVersionRelationEntity>
    @Query("SELECT * FROM novex_character_version_relations WHERE source_version_id = :versionId OR target_version_id = :versionId ORDER BY id")
    suspend fun forVersion(versionId: String): List<NovexCharacterVersionRelationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(relation: NovexCharacterVersionRelationEntity)
    @Query("DELETE FROM novex_character_version_relations WHERE id = :id")
    suspend fun delete(id: String)
}
