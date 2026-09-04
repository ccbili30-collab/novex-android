package com.openminis.app.data.character

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ContentModuleDao {
    @Query(
        "SELECT * FROM content_modules WHERE owner_type = :ownerType AND owner_id = :ownerId " +
            "ORDER BY position ASC, created_at ASC, id ASC",
    )
    suspend fun list(ownerType: ModuleOwnerType, ownerId: String): List<ContentModuleEntity>

    @Query("SELECT * FROM content_modules ORDER BY owner_type ASC, owner_id ASC, position ASC")
    suspend fun all(): List<ContentModuleEntity>

    @Query("SELECT * FROM content_modules WHERE id = :id")
    suspend fun module(id: String): ContentModuleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(module: ContentModuleEntity)

    @Update
    suspend fun update(module: ContentModuleEntity)

    @Update
    suspend fun updateAll(modules: List<ContentModuleEntity>)

    @Query("DELETE FROM content_modules WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReference(reference: ContentModuleReferenceEntity)

    @Query(
        "SELECT * FROM content_module_references WHERE source_module_id = :moduleId " +
            "ORDER BY position ASC, target_type ASC, target_id ASC",
    )
    suspend fun references(moduleId: String): List<ContentModuleReferenceEntity>

    @Query(
        "DELETE FROM content_module_references " +
            "WHERE source_module_id = :sourceModuleId AND target_type = :targetType AND target_id = :targetId",
    )
    suspend fun deleteReference(
        sourceModuleId: String,
        targetType: ModuleReferenceTargetType,
        targetId: String,
    )

    @Query(
        "DELETE FROM content_module_references WHERE target_type = 'MODULE' AND target_id = :moduleId",
    )
    suspend fun deleteInboundModuleReferences(moduleId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM worlds WHERE id = :id)")
    suspend fun worldExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM character_versions WHERE id = :id)")
    suspend fun characterVersionExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM interactive_fiction_projects WHERE id = :id)")
    suspend fun interactiveFictionExists(id: String): Boolean

    @Transaction
    suspend fun append(
        module: ContentModuleEntity,
        repeatable: Boolean,
    ): ContentModuleEntity {
        val existing = list(module.ownerType, module.ownerId)
        require(repeatable || existing.none { it.type == module.type }) {
            "同一对象不能重复添加${module.name}"
        }
        val position = existing.size
        val positioned = module.copy(position = position)
        insert(positioned)
        return positioned
    }

    @Transaction
    suspend fun move(id: String, toIndex: Int, now: Long): ContentModuleEntity? {
        val target = module(id) ?: return null
        val rows = list(target.ownerType, target.ownerId).toMutableList()
        val fromIndex = rows.indexOfFirst { it.id == id }
        if (fromIndex < 0) return null
        val moved = rows.removeAt(fromIndex)
        rows.add(toIndex.coerceIn(0, rows.size), moved)
        val normalized = rows.mapIndexed { index, row ->
            row.copy(position = index, updatedAt = if (row.id == id) now else row.updatedAt)
        }
        updateAll(normalized)
        return normalized.first { it.id == id }
    }

    @Transaction
    suspend fun deleteAndCompact(id: String) {
        val target = module(id) ?: return
        deleteInboundModuleReferences(id)
        deleteRow(id)
        val normalized = list(target.ownerType, target.ownerId).mapIndexed { index, row ->
            row.copy(position = index)
        }
        updateAll(normalized)
    }
}
