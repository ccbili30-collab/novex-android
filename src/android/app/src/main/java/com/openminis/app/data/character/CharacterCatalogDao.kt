package com.openminis.app.data.character

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.openminis.app.data.db.NovexCardTextReader
import com.openminis.app.data.db.NovexCardTextField
import com.openminis.app.data.db.readCardText

@Dao
interface CharacterCatalogDao : NovexCardTextReader {
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

    @Transaction
    suspend fun insertCharacterAggregate(
        character: CharacterEntity,
        versions: List<CharacterVersionEntity>,
    ) {
        require(versions.count { it.kind == CharacterVersionKind.ORIGINAL } == 1)
        require(versions.any { it.id == character.originalVersionId })
        require(versions.all { it.characterId == character.id })
        insertCharacter(character)
        versions.forEach { insertVersion(it) }
    }

    @Query("SELECT id, name, '' AS overview, '' AS tags_json, NULL AS legacy_snapshot_json, created_at, updated_at FROM worlds WHERE id = :id")
    suspend fun worldRecord(id: String): WorldEntity?

    @Transaction
    suspend fun world(id: String): WorldEntity? = worldRecord(id)?.let { hydrateWorld(it) }

    @Query("SELECT id, name, '' AS overview, '' AS tags_json, NULL AS legacy_snapshot_json, created_at, updated_at FROM worlds ORDER BY updated_at DESC, id ASC")
    suspend fun listWorldsRecord(): List<WorldEntity>

    @Transaction
    suspend fun listWorlds(): List<WorldEntity> = listWorldsRecord().map { hydrateWorld(it) }

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun character(id: String): CharacterEntity?

    @Query("SELECT * FROM characters ORDER BY updated_at DESC, id ASC")
    suspend fun listCharacters(): List<CharacterEntity>

    @Query("SELECT id, character_id, kind, label, '' AS profile_json, position, created_at, updated_at FROM character_versions WHERE id = :id")
    suspend fun versionRecord(id: String): CharacterVersionEntity?

    @Transaction
    suspend fun version(id: String): CharacterVersionEntity? = versionRecord(id)?.let { hydrateVersion(it) }

    @Query("SELECT id, character_id, kind, label, '' AS profile_json, position, created_at, updated_at FROM character_versions ORDER BY updated_at DESC, id ASC")
    suspend fun listVersionsRecord(): List<CharacterVersionEntity>

    @Transaction
    suspend fun listVersions(): List<CharacterVersionEntity> = listVersionsRecord().map { hydrateVersion(it) }

    @Update
    suspend fun updateWorld(world: WorldEntity)

    @Update
    suspend fun updateCharacter(character: CharacterEntity)

    @Update
    suspend fun updateVersion(version: CharacterVersionEntity)

    @Query(
        "SELECT id, character_id, kind, label, '' AS profile_json, position, created_at, updated_at FROM character_versions WHERE character_id = :characterId " +
            "ORDER BY position ASC, created_at ASC, id ASC",
    )
    suspend fun versionsForCharacterRecord(characterId: String): List<CharacterVersionEntity>

    @Transaction
    suspend fun versionsForCharacter(characterId: String): List<CharacterVersionEntity> = versionsForCharacterRecord(characterId).map { hydrateVersion(it) }

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM character_versions WHERE character_id = :characterId")
    suspend fun nextVersionPosition(characterId: String): Int

    @Query(
        "SELECT versions.id, versions.character_id, versions.kind, versions.label, '' AS profile_json, versions.position, versions.created_at, versions.updated_at FROM character_versions AS versions " +
            "INNER JOIN world_character_versions AS memberships " +
            "ON memberships.character_version_id = versions.id " +
            "WHERE memberships.world_id = :worldId " +
            "ORDER BY memberships.position ASC, memberships.created_at ASC, versions.id ASC",
    )
    suspend fun versionsForWorldRecord(worldId: String): List<CharacterVersionEntity>

    @Transaction
    suspend fun versionsForWorld(worldId: String): List<CharacterVersionEntity> = versionsForWorldRecord(worldId).map { hydrateVersion(it) }

    @Query(
        "SELECT worlds.id, worlds.name, '' AS overview, '' AS tags_json, NULL AS legacy_snapshot_json, worlds.created_at, worlds.updated_at FROM worlds " +
            "INNER JOIN world_character_versions AS memberships " +
            "ON memberships.world_id = worlds.id " +
            "WHERE memberships.character_version_id = :versionId " +
            "ORDER BY memberships.created_at ASC, worlds.id ASC",
    )
    suspend fun worldsForVersionRecord(versionId: String): List<WorldEntity>

    @Transaction
    suspend fun worldsForVersion(versionId: String): List<WorldEntity> = worldsForVersionRecord(versionId).map { hydrateWorld(it) }

    @Query(
        "DELETE FROM world_character_versions " +
            "WHERE world_id = :worldId AND character_version_id = :versionId",
    )
    suspend fun removeWorldMembership(worldId: String, versionId: String)

    @Query("DELETE FROM character_versions WHERE id = :versionId")
    suspend fun deleteVersion(versionId: String)

    @Query("DELETE FROM characters WHERE id = :characterId")
    suspend fun deleteCharacter(characterId: String)

    @Query("DELETE FROM worlds WHERE id = :worldId")
    suspend fun deleteWorld(worldId: String)
    private suspend fun hydrateWorld(row: WorldEntity) = row.copy(
        overview = requireNotNull(readCardText(NovexCardTextField.WORLD_OVERVIEW, row.id)),
        tagsJson = requireNotNull(readCardText(NovexCardTextField.WORLD_TAGS, row.id)),
        legacySnapshotJson = readCardText(NovexCardTextField.WORLD_SOURCE, row.id),
    )

    private suspend fun hydrateVersion(row: CharacterVersionEntity) = row.copy(
        profileJson = requireNotNull(readCardText(NovexCardTextField.ROLE_PROFILE, row.id)),
    )
}
