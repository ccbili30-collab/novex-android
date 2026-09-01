package com.openminis.app.data.character

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CharacterCatalogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorld(world: WorldEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCharacter(character: CharacterEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: CharacterVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorldMembership(membership: WorldCharacterVersionEntity)

    @Transaction
    suspend fun insertCharacterWithOriginal(
        character: CharacterEntity,
        original: CharacterVersionEntity,
    ) {
        require(original.id == character.originalVersionId)
        require(original.characterId == character.id)
        require(original.kind == CharacterVersionKind.ORIGINAL)
        insertCharacter(character)
        insertVersion(original)
    }

    @Query("SELECT * FROM worlds WHERE id = :id")
    suspend fun world(id: String): WorldEntity?

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun character(id: String): CharacterEntity?

    @Query("SELECT * FROM character_versions WHERE id = :id")
    suspend fun version(id: String): CharacterVersionEntity?

    @Query(
        "SELECT * FROM character_versions WHERE character_id = :characterId " +
            "ORDER BY CASE kind WHEN 'ORIGINAL' THEN 0 ELSE 1 END, updated_at DESC, id ASC",
    )
    suspend fun versionsForCharacter(characterId: String): List<CharacterVersionEntity>

    @Query(
        "SELECT versions.* FROM character_versions AS versions " +
            "INNER JOIN world_character_versions AS memberships " +
            "ON memberships.character_version_id = versions.id " +
            "WHERE memberships.world_id = :worldId " +
            "ORDER BY memberships.position ASC, memberships.created_at ASC, versions.id ASC",
    )
    suspend fun versionsForWorld(worldId: String): List<CharacterVersionEntity>

    @Query(
        "SELECT worlds.* FROM worlds " +
            "INNER JOIN world_character_versions AS memberships " +
            "ON memberships.world_id = worlds.id " +
            "WHERE memberships.character_version_id = :versionId " +
            "ORDER BY memberships.created_at ASC, worlds.id ASC",
    )
    suspend fun worldsForVersion(versionId: String): List<WorldEntity>

    @Query(
        "DELETE FROM world_character_versions " +
            "WHERE world_id = :worldId AND character_version_id = :versionId",
    )
    suspend fun removeWorldMembership(worldId: String, versionId: String)

    @Query("DELETE FROM worlds WHERE id = :worldId")
    suspend fun deleteWorld(worldId: String)
}
