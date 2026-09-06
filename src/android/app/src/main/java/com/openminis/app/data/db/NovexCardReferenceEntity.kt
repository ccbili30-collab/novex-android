package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Targets deliberately have no cascade: deleting one independent card must leave a missing-link record. */
@Entity(tableName = "novex_card_references", indices = [
    Index(value = ["source_kind", "source_id"], name = "index_novex_card_references_source"),
    Index(value = ["target_kind", "target_id"], name = "index_novex_card_references_target"),
])
data class NovexCardReferenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_module_id") val sourceModuleId: String?,
    @ColumnInfo(name = "target_kind") val targetKind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val position: Int,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

@Dao
interface NovexCardReferenceDao {
    @Query("SELECT * FROM novex_card_references WHERE id = :id")
    suspend fun get(id: String): NovexCardReferenceEntity?
    @Query("SELECT * FROM novex_card_references WHERE source_kind = :kind AND source_id = :id ORDER BY position, id")
    suspend fun outgoing(kind: String, id: String): List<NovexCardReferenceEntity>
    @Query("SELECT * FROM novex_card_references WHERE target_kind = :kind AND target_id = :id ORDER BY position, id")
    suspend fun incoming(kind: String, id: String): List<NovexCardReferenceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: NovexCardReferenceEntity)
    @Query("DELETE FROM novex_card_references WHERE id = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM novex_card_references WHERE source_kind = :kind AND source_id = :id")
    suspend fun deleteSource(kind: String, id: String)
    @Query("DELETE FROM novex_card_references WHERE source_module_id = :moduleId")
    suspend fun deleteSourceModule(moduleId: String)
}
