package com.openminis.app.data.character

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import org.json.JSONArray
import org.json.JSONObject

data class LegacyCatalogMigrationReport(
    val worldCount: Int,
    val characterCount: Int,
    val membershipCount: Int,
    val alreadyCompleted: Boolean,
)

/** Copies the v1 SharedPreferences library into the normalized catalog. */
object LegacyCharacterCatalogMigrator {
    private const val PREFS = "novex_character_cards"
    private const val KEY_CHARACTERS = "characters"
    private const val KEY_PERSONAS = "personas"
    private const val KEY_WORLD = "story_world"
    private const val KEY_WORLDS = "story_worlds"
    private const val MIGRATION_ID = "legacy-character-catalog-v1"

    suspend fun migrate(
        context: Context,
        database: AppDatabase,
        now: Long = System.currentTimeMillis(),
    ): LegacyCatalogMigrationReport {
        val dao = database.characterCatalogDao()
        dao.migrationState(MIGRATION_ID)?.let { state ->
            return LegacyCatalogMigrationReport(
                worldCount = state.worldCount,
                characterCount = state.characterCount,
                membershipCount = state.membershipCount,
                alreadyCompleted = true,
            )
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyWorld = prefs.getString(KEY_WORLD, null)?.let { raw ->
            runCatching { StoryWorld.fromJson(JSONObject(raw)) }.getOrNull()
        }
        val storedWorlds = parseArray(prefs.getString(KEY_WORLDS, null), StoryWorld::fromJson)
        val cards = parseArray(prefs.getString(KEY_CHARACTERS, null), CharacterCard::fromJson)
            .filter { it.name.isNotBlank() }
            .distinctBy(CharacterCard::id)
        val personas = parseArray(prefs.getString(KEY_PERSONAS, null), PlayerPersona::fromJson)

        val needsFallbackWorld = storedWorlds.isEmpty() && legacyWorld == null &&
            (cards.isNotEmpty() || personas.isNotEmpty())
        val fallbackWorld = if (needsFallbackWorld) {
            StoryWorld(
                id = "default-world",
                name = "我的世界",
                description = "",
                createdAt = now,
                updatedAt = now,
            )
        } else null
        val sourceWorlds = (storedWorlds + listOfNotNull(legacyWorld, fallbackWorld))
            .distinctBy(StoryWorld::id)
        val fallbackWorldId = sourceWorlds.firstOrNull()?.id ?: "default-world"

        val worlds = sourceWorlds.map { world ->
            WorldEntity(
                id = world.id,
                name = world.name,
                overview = world.description,
                legacySnapshotJson = world.toJson().toString(),
                createdAt = world.createdAt,
                updatedAt = world.updatedAt,
            )
        }
        val characters = cards.map { card ->
            CharacterEntity(
                id = card.id,
                name = card.name,
                // The old card id remains the original-version id so existing
                // session references and role-memory directories keep their key.
                originalVersionId = card.id,
                createdAt = card.createdAt,
                updatedAt = card.updatedAt,
            )
        }
        val versions = cards.map { card ->
            CharacterVersionEntity(
                id = card.id,
                characterId = card.id,
                kind = CharacterVersionKind.ORIGINAL,
                label = "本体",
                profileJson = card.toJson().toString(),
                createdAt = card.createdAt,
                updatedAt = card.updatedAt,
            )
        }
        val knownWorldIds = worlds.mapTo(hashSetOf(), WorldEntity::id)
        val membershipPositions = mutableMapOf<String, Int>()
        val memberships = cards.mapNotNull { card ->
            val worldId = card.worldId.takeIf { it in knownWorldIds }
                ?: fallbackWorldId.takeIf { it in knownWorldIds }
                ?: return@mapNotNull null
            val position = membershipPositions.getOrDefault(worldId, 0)
            membershipPositions[worldId] = position + 1
            WorldCharacterVersionEntity(
                worldId = worldId,
                characterVersionId = card.id,
                position = position,
                createdAt = card.createdAt,
            )
        }
        val sessionReferences = database.chatDao().listSessions().map { session ->
            val snapshotWorldId = session.worldSnapshotJson?.let(::worldIdFromSnapshot)
                ?: session.characterSnapshotJson?.let(::worldIdFromCharacterSnapshot)
            CatalogSessionReference(
                sessionId = session.id,
                worldId = snapshotWorldId,
                characterVersionId = session.characterId,
            )
        }
        val state = CatalogMigrationStateEntity(
            id = MIGRATION_ID,
            completedAt = now,
            worldCount = worlds.size,
            characterCount = characters.size,
            membershipCount = memberships.size,
        )
        val imported = dao.importLegacyCatalogIfNeeded(
            state = state,
            worlds = worlds,
            characters = characters,
            versions = versions,
            memberships = memberships,
            sessionReferences = sessionReferences,
        )
        return LegacyCatalogMigrationReport(
            worldCount = state.worldCount,
            characterCount = state.characterCount,
            membershipCount = state.membershipCount,
            alreadyCompleted = !imported,
        )
    }

    private fun worldIdFromSnapshot(raw: String): String? = runCatching {
        JSONObject(raw).optString("id").trim().ifBlank { null }
    }.getOrNull()

    private fun worldIdFromCharacterSnapshot(raw: String): String? = runCatching {
        JSONObject(raw).optString("worldId").trim().ifBlank { null }
    }.getOrNull()

    private fun <T> parseArray(raw: String?, parser: (JSONObject) -> T): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { json ->
                        runCatching { parser(json) }.getOrNull()?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
