package com.openminis.app.ui.settings

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterCustomAttribute
import com.openminis.app.data.character.CharacterRelationship
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import java.util.UUID

/** One character-version editor session. Nothing reaches storage before [toSaveCommand]. */
internal data class CharacterEditorDraftState(
    val characterId: String?,
    val versionId: String?,
    val sourceVersionId: String?,
    val createVariant: Boolean,
    val rootName: String,
    val label: String,
    val name: String,
    val tagsText: String,
    val gender: String,
    val age: String,
    val race: String,
    val occupation: String,
    val summary: String,
    val attributesText: String,
    val relationshipsText: String,
    val baseProfile: CharacterVersionProfile,
    val modules: List<NovexModuleDraft>,
    val expandedModuleIds: Set<String> = emptySet(),
    val imageChanges: Map<MediaAssetSlot, NovexImageChange> = emptyMap(),
    val visualExpanded: Boolean = false,
) {
    val isBlank: Boolean
        get() = rootName.isBlank() || name.isBlank()

    fun profile(): CharacterVersionProfile = baseProfile.copy(
        name = name,
        tags = splitTags(tagsText),
        gender = gender,
        age = age,
        race = race,
        occupation = occupation,
        summary = summary,
        customAttributes = parseCharacterAttributes(attributesText),
        relationships = parseCharacterRelationships(relationshipsText),
    )

    fun toggleModule(moduleId: String): CharacterEditorDraftState {
        if (modules.none { it.id == moduleId }) return this
        val expanded = expandedModuleIds.toMutableSet().apply {
            if (!add(moduleId)) remove(moduleId)
        }
        return copy(expandedModuleIds = expanded)
    }

    fun addModule(
        type: ContentModuleType,
        name: String = ContentModuleCatalog.definition(type).displayName,
        moduleId: String = UUID.randomUUID().toString(),
    ): CharacterEditorDraftState {
        val definition = ContentModuleCatalog.definition(type)
        require(definition in ContentModuleCatalog.definitions(ContentModuleScope.CHARACTER_VERSION)) {
            "角色不支持${definition.displayName}"
        }
        require(definition.repeatable || modules.none { it.type == type }) {
            "${definition.displayName}已经存在"
        }
        val draft = NovexModuleDraft(
            id = moduleId,
            type = type,
            name = name,
            contentJson = ContentModuleDocumentCodec.encode(emptyDocument(type)),
            collapsed = true,
        )
        return copy(modules = modules + draft, expandedModuleIds = expandedModuleIds + draft.id)
    }

    fun updateModule(
        moduleId: String,
        name: String,
        document: ContentModuleDocument,
    ): CharacterEditorDraftState = copy(
        modules = modules.map { module ->
            if (module.id == moduleId) {
                module.copy(name = name, contentJson = ContentModuleDocumentCodec.encode(document))
            } else {
                module
            }
        },
    )

    fun moveModule(moduleId: String, toIndex: Int): CharacterEditorDraftState {
        val mutable = modules.toMutableList()
        val from = mutable.indexOfFirst { it.id == moduleId }
        if (from < 0) return this
        val moved = mutable.removeAt(from)
        mutable.add(toIndex.coerceIn(0, mutable.size), moved)
        return copy(modules = mutable)
    }

    fun removeModule(moduleId: String): CharacterEditorDraftState = copy(
        modules = modules.filterNot { it.id == moduleId },
        expandedModuleIds = expandedModuleIds - moduleId,
    )

    fun replaceImage(
        slot: MediaAssetSlot,
        bytes: ByteArray,
        mimeType: String,
    ): CharacterEditorDraftState = copy(
        imageChanges = imageChanges + (slot to NovexImageChange.Replace(slot, bytes, mimeType)),
    )

    fun removeImage(slot: MediaAssetSlot): CharacterEditorDraftState = copy(
        imageChanges = imageChanges + (slot to NovexImageChange.Remove(slot)),
    )

    fun toSaveCommand(
        worldId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): NovexCommand.SaveCharacterPage = NovexCommand.SaveCharacterPage(
        characterId = characterId,
        versionId = versionId,
        sourceVersionId = sourceVersionId,
        createVariant = createVariant,
        rootName = rootName,
        label = label,
        profileJson = profile().toJson(),
        modules = modules,
        imageChanges = imageChanges.values.toList(),
        linkWorldId = worldId,
        now = now,
    )

    companion object {
        fun create() = CharacterEditorDraftState(
            characterId = null,
            versionId = null,
            sourceVersionId = null,
            createVariant = false,
            rootName = "",
            label = "本体",
            name = "",
            tagsText = "",
            gender = "",
            age = "",
            race = "",
            occupation = "",
            summary = "",
            attributesText = "",
            relationshipsText = "",
            baseProfile = CharacterVersionProfile(""),
            modules = emptyList(),
        )

        fun from(
            aggregate: CharacterAggregate,
            source: CharacterVersionEntity,
            modules: List<ContentModuleEntity>,
            createVariant: Boolean,
            moduleIdFactory: () -> String = { UUID.randomUUID().toString() },
        ): CharacterEditorDraftState {
            val profile = CharacterVersionProfile.fromJson(source.profileJson, aggregate.character.name)
            return CharacterEditorDraftState(
                characterId = aggregate.character.id,
                versionId = source.id.takeUnless { createVariant },
                sourceVersionId = source.id.takeIf { createVariant },
                createVariant = createVariant,
                rootName = aggregate.character.name,
                label = if (createVariant) "新分身" else source.label,
                name = profile.name,
                tagsText = profile.tags.joinToString("、"),
                gender = profile.gender,
                age = profile.age,
                race = profile.race,
                occupation = profile.occupation,
                summary = profile.summary,
                attributesText = profile.customAttributes.joinToString("\n", transform = CharacterCustomAttribute::asDraftLine),
                relationshipsText = profile.relationships.joinToString("\n", transform = CharacterRelationship::asDraftLine),
                baseProfile = profile,
                modules = modules.sortedWith(
                    compareBy<ContentModuleEntity> { it.position }.thenBy { it.createdAt }.thenBy { it.id },
                ).map { module ->
                    NovexModuleDraft.from(module).let { draft ->
                        if (createVariant) draft.copy(id = moduleIdFactory()) else draft
                    }
                },
            )
        }

        private fun emptyDocument(type: ContentModuleType): ContentModuleDocument = when (type) {
            ContentModuleType.WORLD_EXPERIENCE -> ContentModuleDocument.Timeline()
            ContentModuleType.QUOTES,
            ContentModuleType.ATTRIBUTE_PANEL,
            ContentModuleType.EQUIPMENT,
            ContentModuleType.TALENT_SKILL,
            ContentModuleType.APPEARANCE_PERSONALITY,
            ContentModuleType.INTEREST,
            -> ContentModuleDocument.Collection()
            ContentModuleType.CUSTOM -> ContentModuleDocument.Article()
            else -> error("角色不支持${ContentModuleCatalog.definition(type).displayName}")
        }
    }
}

private fun splitTags(raw: String): List<String> = raw.split(Regex("[、,，\\n]"))
    .map(String::trim)
    .filter(String::isNotEmpty)

private fun CharacterCustomAttribute.asDraftLine(): String = "$name：$value"

private fun CharacterRelationship.asDraftLine(): String =
    listOf(characterName, relationship, description).joinToString("｜")
