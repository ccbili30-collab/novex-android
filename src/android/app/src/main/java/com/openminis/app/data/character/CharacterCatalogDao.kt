package com.openminis.app.data.character

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorldIfAbsent(world: WorldEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCharacterIfAbsent(character: CharacterEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersionIfAbsent(version: CharacterVersionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorldMembershipIfAbsent(membership: WorldCharacterVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMigrationState(state: CatalogMigrationStateEntity)

    @Query("SELECT * FROM catalog_migration_state WHERE id = :id")
    suspend fun migrationState(id: String): CatalogMigrationStateEntity?

    @Query(
        "UPDATE sessions SET world_id = :worldId, character_version_id = :characterVersionId " +
            "WHERE id = :sessionId",
    )
    suspend fun updateSessionCatalogReferences(
        sessionId: String,
        worldId: String?,
        characterVersionId: String?,
    )

    /** One transaction makes a failed or interrupted legacy import retryable. */
    @Transaction
    suspend fun importLegacyCatalogIfNeeded(
        state: CatalogMigrationStateEntity,
        worlds: List<WorldEntity>,
        characters: List<CharacterEntity>,
        versions: List<CharacterVersionEntity>,
        memberships: List<WorldCharacterVersionEntity>,
        sessionReferences: List<CatalogSessionReference>,
    ): Boolean {
        if (migrationState(state.id) != null) return false
        worlds.forEach { insertWorldIfAbsent(it) }
        characters.forEach { insertCharacterIfAbsent(it) }
        versions.forEach { insertVersionIfAbsent(it) }
        memberships.forEach { insertWorldMembershipIfAbsent(it) }
        sessionReferences.forEach { reference ->
            updateSessionCatalogReferences(
                sessionId = reference.sessionId,
                worldId = reference.worldId,
                characterVersionId = reference.characterVersionId,
            )
        }
        insertMigrationState(state)
        return true
    }

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

    @Query("SELECT * FROM worlds ORDER BY updated_at DESC, id ASC")
    suspend fun listWorlds(): List<WorldEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun character(id: String): CharacterEntity?

    @Query("SELECT * FROM character_versions WHERE id = :id")
    suspend fun version(id: String): CharacterVersionEntity?

    @Query("SELECT * FROM character_versions ORDER BY updated_at DESC, id ASC")
    suspend fun listVersions(): List<CharacterVersionEntity>

    @Update
    suspend fun updateWorld(world: WorldEntity)

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
