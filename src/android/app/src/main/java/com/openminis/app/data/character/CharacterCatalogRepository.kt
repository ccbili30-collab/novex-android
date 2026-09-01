package com.openminis.app.data.character

import java.util.UUID

/**
 * Write boundary for the reusable character-version catalog.
 *
 * Legacy character cards are deliberately not read here. Their one-time import
 * belongs to the next migration checkpoint, keeping this schema change empty
 * and independently reversible.
 */
class CharacterCatalogRepository(
    private val dao: CharacterCatalogDao,
) {
    suspend fun createWorld(
        name: String,
        overview: String = "",
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): WorldEntity {
        val world = WorldEntity(
            id = id,
            name = name.trim().ifBlank { "我的世界" },
            overview = overview,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertWorld(world)
        return world
    }

    suspend fun createCharacter(
        name: String,
        originalLabel: String = "本体",
        originalProfileJson: String = "{}",
        now: Long = System.currentTimeMillis(),
        characterId: String = UUID.randomUUID().toString(),
        originalVersionId: String = UUID.randomUUID().toString(),
    ): CharacterAggregate {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "角色名称不能为空" }
        val character = CharacterEntity(
            id = characterId,
            name = normalizedName,
            originalVersionId = originalVersionId,
            createdAt = now,
            updatedAt = now,
        )
        val original = CharacterVersionEntity(
            id = originalVersionId,
            characterId = characterId,
            kind = CharacterVersionKind.ORIGINAL,
            label = originalLabel.trim().ifBlank { "本体" },
            profileJson = originalProfileJson,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertCharacterWithOriginal(character, original)
        return CharacterAggregate(character, original, emptyList())
    }

    suspend fun createVariant(
        characterId: String,
        label: String,
        profileJson: String = "{}",
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): CharacterVersionEntity {
        requireNotNull(dao.character(characterId)) { "角色不存在" }
        val variant = CharacterVersionEntity(
            id = id,
            characterId = characterId,
            kind = CharacterVersionKind.VARIANT,
            label = label.trim().ifBlank { "分身" },
            profileJson = profileJson,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertVersion(variant)
        return variant
    }

    suspend fun addVersionToWorld(
        worldId: String,
        versionId: String,
        position: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        requireNotNull(dao.world(worldId)) { "世界不存在" }
        requireNotNull(dao.version(versionId)) { "角色版本不存在" }
        dao.upsertWorldMembership(
            WorldCharacterVersionEntity(
                worldId = worldId,
                characterVersionId = versionId,
                position = position.coerceAtLeast(0),
                createdAt = now,
            ),
        )
    }

    suspend fun removeVersionFromWorld(worldId: String, versionId: String) {
        dao.removeWorldMembership(worldId, versionId)
    }

    suspend fun character(id: String): CharacterAggregate? {
        val character = dao.character(id) ?: return null
        val versions = dao.versionsForCharacter(id)
        val original = versions.firstOrNull { it.id == character.originalVersionId }
            ?: error("角色缺少本体版本: $id")
        check(original.kind == CharacterVersionKind.ORIGINAL) {
            "角色本体类型无效: ${original.id}"
        }
        val variants = versions.filter { it.id != original.id }
        check(variants.all { it.kind == CharacterVersionKind.VARIANT }) {
            "角色包含第二个本体版本: $id"
        }
        return CharacterAggregate(character, original, variants)
    }

    suspend fun world(id: String): WorldEntity? = dao.world(id)

    suspend fun version(id: String): CharacterVersionEntity? = dao.version(id)

    suspend fun versionsForWorld(worldId: String): List<CharacterVersionEntity> =
        dao.versionsForWorld(worldId)

    suspend fun worldsForVersion(versionId: String): List<WorldEntity> =
        dao.worldsForVersion(versionId)

    suspend fun deleteWorld(worldId: String) {
        dao.deleteWorld(worldId)
    }
}
