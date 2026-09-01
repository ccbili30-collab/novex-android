package com.openminis.app.data.character

import org.json.JSONObject

data class CharacterConversationSnapshot(
    val worldId: String,
    val characterVersionId: String,
    val profile: ImmersiveChatProfile,
)

/** Converts mutable catalog data into the immutable legacy-compatible snapshots used by chat. */
class CharacterConversationSnapshotFactory(
    private val catalog: CharacterCatalogRepository,
    private val modules: ContentModuleRepository,
    private val media: MediaAssetRepository,
) {
    suspend fun create(
        worldId: String,
        characterVersionId: String,
        persona: PlayerPersona?,
    ): CharacterConversationSnapshot {
        val world = requireNotNull(catalog.world(worldId)) { "世界不存在" }
        val version = requireNotNull(catalog.version(characterVersionId)) { "角色版本不存在" }
        require(catalog.versionsForWorld(worldId).any { it.id == characterVersionId }) {
            "角色版本未关联到所选世界"
        }
        require(persona == null || persona.worldId == worldId) { "玩家身份不属于所选世界" }
        val root = requireNotNull(catalog.character(version.characterId)) { "角色不存在" }
        val profile = CharacterVersionProfile.fromJson(version.profileJson, root.character.name)
        val worldOwner = ModuleOwner.world(worldId)
        val versionOwner = ModuleOwner.characterVersion(characterVersionId)
        val worldModules = modules.list(worldOwner)
        val characterModules = modules.list(versionOwner)
        val worldBackground = media.assetFor(worldOwner, MediaAssetSlot.WORLD_BACKGROUND)?.managedPath
        val characterAvatar = media.assetFor(versionOwner, MediaAssetSlot.CHARACTER_AVATAR)?.managedPath
        val characterBackground = media.assetFor(
            versionOwner,
            MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
        )?.managedPath

        val legacyWorldSnapshot = world.legacySnapshotJson
            ?.let { raw -> runCatching { StoryWorld.fromJson(JSONObject(raw)) }.getOrNull() }
        val worldSnapshot = legacyWorldSnapshot
            ?.copy(
                id = world.id,
                name = world.name,
                description = combineSections(world.overview, worldModules),
                backgroundPath = worldBackground ?: legacyWorldSnapshot.backgroundPath,
                updatedAt = world.updatedAt,
            )
            ?: StoryWorld(
                id = world.id,
                name = world.name,
                description = combineSections(world.overview, worldModules),
                backgroundPath = worldBackground,
                createdAt = world.createdAt,
                updatedAt = world.updatedAt,
            )

        val imported = runCatching { CharacterCard.fromJson(JSONObject(version.profileJson)) }.getOrNull()
        val fixedProfile = fixedProfileText(profile)
        val quotes = characterModules.withType(ContentModuleType.QUOTES)
        val experience = characterModules.withType(ContentModuleType.WORLD_EXPERIENCE)
        val appearance = characterModules.withType(ContentModuleType.APPEARANCE_PERSONALITY)
        val knowledge = characterModules.filterNot {
            it.type in setOf(
                ContentModuleType.QUOTES,
                ContentModuleType.WORLD_EXPERIENCE,
                ContentModuleType.APPEARANCE_PERSONALITY,
            )
        }.moduleText()
        val resolvedCharacterBackground = characterBackground
            ?: imported?.defaultBackgroundPath
            ?: worldBackground
            ?: worldSnapshot.backgroundPath
        val characterSnapshot = (imported ?: emptyCard(version.id, world.id, profile.name, version)).copy(
            id = version.id,
            worldId = world.id,
            name = profile.name.ifBlank { root.character.name },
            summary = listOf(profile.summary, fixedProfile).filter(String::isNotBlank).joinToString("\n\n"),
            personality = listOf(imported?.personality.orEmpty(), appearance)
                .filter(String::isNotBlank).distinct().joinToString("\n\n"),
            background = listOf(imported?.background.orEmpty(), experience)
                .filter(String::isNotBlank).distinct().joinToString("\n\n"),
            greeting = quotes.ifBlank { imported?.greeting.orEmpty() },
            knowledge = listOf(imported?.knowledge.orEmpty(), knowledge)
                .filter(String::isNotBlank).distinct().joinToString("\n\n"),
            tags = profile.tags.ifEmpty { imported?.tags.orEmpty() },
            avatarPath = characterAvatar ?: imported?.avatarPath,
            coverPath = characterBackground ?: imported?.coverPath,
            defaultBackgroundPath = resolvedCharacterBackground,
            createdAt = version.createdAt,
            updatedAt = version.updatedAt,
        )
        return CharacterConversationSnapshot(
            worldId = world.id,
            characterVersionId = version.id,
            profile = ImmersiveChatProfile(
                world = worldSnapshot,
                character = characterSnapshot,
                persona = persona,
                worldId = world.id,
                characterVersionId = version.id,
                backgroundPath = resolvedCharacterBackground,
                rolePresentationEnabled = true,
            ),
        )
    }

    private fun emptyCard(
        id: String,
        worldId: String,
        name: String,
        version: CharacterVersionEntity,
    ) = CharacterCard(
        id = id,
        name = name,
        worldId = worldId,
        createdAt = version.createdAt,
        updatedAt = version.updatedAt,
    )

    private fun combineSections(overview: String, values: List<ContentModuleEntity>): String =
        buildList {
            overview.trim().takeIf(String::isNotEmpty)?.let(::add)
            values.moduleText().takeIf(String::isNotEmpty)?.let(::add)
        }.joinToString("\n\n")

    private fun List<ContentModuleEntity>.withType(type: ContentModuleType): String =
        filter { it.type == type }.moduleText()

    private fun List<ContentModuleEntity>.moduleText(): String = joinToString("\n\n") { module ->
        val text = runCatching { JSONObject(module.contentJson).optString("text") }
            .getOrElse { module.contentJson }
            .trim()
        if (text.isBlank()) "" else "${module.name}\n$text"
    }.trim()

    private fun fixedProfileText(profile: CharacterVersionProfile): String = buildList {
        profile.gender.takeIf(String::isNotBlank)?.let { add("性别：$it") }
        profile.age.takeIf(String::isNotBlank)?.let { add("年龄：$it") }
        profile.race.takeIf(String::isNotBlank)?.let { add("种族：$it") }
        profile.occupation.takeIf(String::isNotBlank)?.let { add("职业：$it") }
        profile.customAttributes.forEach { field ->
            if (field.name.isNotBlank() || field.value.isNotBlank()) add("${field.name}：${field.value}")
        }
        profile.relationships.forEach { relation ->
            val detail = listOf(relation.relationship, relation.description)
                .filter(String::isNotBlank).joinToString("；")
            if (relation.characterName.isNotBlank() || detail.isNotBlank()) {
                add("${relation.characterName}：$detail")
            }
        }
    }.joinToString("\n")
}
