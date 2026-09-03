package com.openminis.app.ui.settings

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterCustomAttribute
import com.openminis.app.data.character.CharacterRelationship
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.ui.novex.ContentModuleDraftList
import com.openminis.app.ui.novex.NovexImageDraft
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
    val contentModules: ContentModuleDraftList,
    val images: NovexImageDraft = NovexImageDraft.empty(),
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

    val modules: List<NovexModuleDraft>
        get() = contentModules.modules

    val imageChanges: Map<MediaAssetSlot, NovexImageChange>
        get() = images.changes

    fun editModules(edit: ContentModuleDraftList.() -> ContentModuleDraftList): CharacterEditorDraftState =
        copy(contentModules = contentModules.edit())

    fun replaceImage(
        slot: MediaAssetSlot,
        bytes: ByteArray,
        mimeType: String,
    ): CharacterEditorDraftState = copy(images = images.replace(slot, bytes, mimeType))

    fun removeImage(slot: MediaAssetSlot): CharacterEditorDraftState = copy(images = images.remove(slot))

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
            contentModules = ContentModuleDraftList.empty(ContentModuleScope.CHARACTER_VERSION),
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
                contentModules = ContentModuleDraftList.fromSaved(
                    scope = ContentModuleScope.CHARACTER_VERSION,
                    modules = modules,
                    moduleId = { module -> if (createVariant) moduleIdFactory() else module.id },
                ),
            )
        }
    }
}

private fun splitTags(raw: String): List<String> = raw.split(Regex("[、,，\\n]"))
    .map(String::trim)
    .filter(String::isNotEmpty)

private fun CharacterCustomAttribute.asDraftLine(): String = "$name：$value"

private fun CharacterRelationship.asDraftLine(): String =
    listOf(characterName, relationship, description).joinToString("｜")
