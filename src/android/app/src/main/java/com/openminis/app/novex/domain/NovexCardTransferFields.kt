package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import org.json.JSONArray
import org.json.JSONObject

/** Shared portable field names and source identifiers for import and export. */
internal object NovexCardTransferFields {
    fun CharacterVersionEntity.sourceId(): String? = runCatching {
        JSONObject(profileJson).optString("_novexSourceId").takeIf(String::isNotBlank)
    }.getOrNull()

    fun CharacterVersionEntity.characterSourceId(): String? = runCatching {
        JSONObject(profileJson).optString("_novexCharacterSourceId").takeIf(String::isNotBlank)
    }.getOrNull()

    fun WorldEntity.sourceId(): String? = runCatching {
        JSONObject(legacySnapshotJson ?: "{}").optString("sourceId").takeIf(String::isNotBlank)
    }.getOrNull()

    fun WorldEntity.tagsList(): List<String> = runCatching {
        val array = JSONArray(tagsJson)
        buildList {
            repeat(array.length()) { index -> array.optString(index).takeIf(String::isNotBlank)?.let(::add) }
        }
    }.getOrDefault(emptyList())

    fun MediaAssetEntity.extension(): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "bin"
    }

    fun JSONObject.putMedia(key: String, path: String?) {
        put(key, path?.let { JSONObject().put("path", it) })
    }

    fun ContentModuleType.transferName(): String = when (this) {
        ContentModuleType.TIMELINE -> "timeline"
        ContentModuleType.ERA_EVENT -> "eraEvents"
        ContentModuleType.MAP -> "map"
        ContentModuleType.REGION -> "regions"
        ContentModuleType.FACTION -> "factions"
        ContentModuleType.RACE -> "races"
        ContentModuleType.QUOTES -> "quotes"
        ContentModuleType.WORLD_EXPERIENCE -> "worldExperience"
        ContentModuleType.ATTRIBUTE_PANEL -> "attributePanel"
        ContentModuleType.EQUIPMENT -> "equipment"
        ContentModuleType.TALENT_SKILL -> "skills"
        ContentModuleType.APPEARANCE_PERSONALITY -> "appearancePersonality"
        ContentModuleType.INTEREST -> "interests"
        ContentModuleType.ROLE_INSTRUCTIONS -> "roleInstructions"
        ContentModuleType.ROLE_PLAYER_IDENTITY -> "rolePlayerIdentity"
        ContentModuleType.GAME_ANSWER_IDENTITY -> "gameAnswerIdentity"
        ContentModuleType.GAME_PLAYER_IDENTITY -> "gamePlayerIdentity"
        ContentModuleType.GAME_OPENING -> "gameOpening"
        ContentModuleType.GAME_NARRATIVE_RULES -> "gameNarrativeRules"
        ContentModuleType.GAME_POWER_SYSTEM -> "gamePowerSystem"
        ContentModuleType.GAME_ATTRIBUTES -> "gameAttributes"
        ContentModuleType.GAME_SKILLS -> "gameSkills"
        ContentModuleType.GAME_EQUIPMENT -> "gameEquipment"
        ContentModuleType.GAME_ITEMS -> "gameItems"
        ContentModuleType.GAME_QUESTS -> "gameQuests"
        ContentModuleType.GAME_CHECKS -> "gameChecks"
        ContentModuleType.GAME_ENDINGS -> "gameEndings"
        ContentModuleType.GAME_CHARACTER_STATUS -> "gameCharacterStatus"
        ContentModuleType.GAME_QUICK_ACTIONS -> "gameQuickActions"
        ContentModuleType.CUSTOM -> "custom"
    }

    fun InteractiveFictionLaunchMode.transferName(): String = when (this) {
        InteractiveFictionLaunchMode.FIXED_IDENTITY -> "fixedIdentity"
        InteractiveFictionLaunchMode.USER_CREATED_IDENTITY -> "userCreatedIdentity"
        InteractiveFictionLaunchMode.CO_CREATE_WORLD -> "coCreateWorld"
        InteractiveFictionLaunchMode.FREE_SANDBOX -> "freeSandbox"
    }

    fun ContentModuleType.defaultPresentation(document: ContentModuleDocument): String = when (document) {
        is ContentModuleDocument.Article -> "article"
        is ContentModuleDocument.SingleImage -> "singleImage"
        is ContentModuleDocument.Timeline -> "timeline"
        is ContentModuleDocument.Collection -> when (this) {
            ContentModuleType.FACTION -> "horizontalCards"
            ContentModuleType.QUOTES -> "quoteCards"
            else -> "compactList"
        }
        is ContentModuleDocument.Unsupported -> document.presentation.orEmpty()
    }

    fun JSONArray?.objects(): List<JSONObject> = buildList {
        val array = this@objects ?: return@buildList
        repeat(array.length()) { index -> array.optJSONObject(index)?.let(::add) }
    }
}
