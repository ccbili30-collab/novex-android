package com.openminis.app.data.character

import android.content.Context
import android.net.Uri
import com.openminis.app.data.repository.MemoryRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local role-playing profile library. Chat sessions persist snapshots separately. */
object CharacterCardStore {
    private const val PREFS = "novex_character_cards"
    private const val KEY_CHARACTERS = "characters"
    private const val KEY_PERSONAS = "personas"
    private const val KEY_WORLD = "story_world"
    private const val KEY_WORLDS = "story_worlds"
    private const val KEY_CURRENT_WORLD_ID = "current_story_world_id"

    private val _characters = MutableStateFlow<List<CharacterCard>>(emptyList())
    val characters: StateFlow<List<CharacterCard>> = _characters.asStateFlow()
    private val _personas = MutableStateFlow<List<PlayerPersona>>(emptyList())
    val personas: StateFlow<List<PlayerPersona>> = _personas.asStateFlow()
    private val _world = MutableStateFlow<StoryWorld?>(null)
    val world: StateFlow<StoryWorld?> = _world.asStateFlow()
    private val _worlds = MutableStateFlow<List<StoryWorld>>(emptyList())
    val worlds: StateFlow<List<StoryWorld>> = _worlds.asStateFlow()
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyWorld = prefs.getString(KEY_WORLD, null)?.let {
            runCatching { StoryWorld.fromJson(JSONObject(it)) }.getOrNull()
        }
        val loadedWorlds = parseArray(prefs.getString(KEY_WORLDS, null)) { StoryWorld.fromJson(it) }
        val loadedCharacters = parseArray(prefs.getString(KEY_CHARACTERS, null)) { CharacterCard.fromJson(it) }
        val loadedPersonas = parseArray(prefs.getString(KEY_PERSONAS, null)) { PlayerPersona.fromJson(it) }
        val migrationWorld = if (loadedWorlds.isEmpty() && legacyWorld == null &&
            (loadedCharacters.isNotEmpty() || loadedPersonas.isNotEmpty())
        ) {
            val now = System.currentTimeMillis()
            StoryWorld(
                id = "default-world",
                name = "我的世界",
                description = "",
                createdAt = now,
                updatedAt = now,
            )
        } else null
        _worlds.value = (loadedWorlds.ifEmpty { listOfNotNull(legacyWorld) })
            .let { worlds -> if (worlds.isEmpty()) listOfNotNull(migrationWorld) else worlds }
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
        val fallbackWorldId = _worlds.value.firstOrNull()?.id ?: "default-world"
        _characters.value = loadedCharacters
            .filter { it.name.isNotBlank() }
            .map { if (_worlds.value.none { world -> world.id == it.worldId }) it.copy(worldId = fallbackWorldId) else it }
            .sortedByDescending { it.updatedAt }
        _personas.value = loadedPersonas
            .map { if (_worlds.value.none { world -> world.id == it.worldId }) it.copy(worldId = fallbackWorldId) else it }
            .sortedWith(compareByDescending<PlayerPersona> { it.isDefault }.thenByDescending { it.updatedAt })
        val selectedId = prefs.getString(KEY_CURRENT_WORLD_ID, null)
        _world.value = _worlds.value.firstOrNull { it.id == selectedId } ?: _worlds.value.firstOrNull()
        initialized = true
        if (loadedWorlds.isEmpty() && (legacyWorld != null || migrationWorld != null)) persist(context)
    }

    fun currentWorld(context: Context): StoryWorld? {
        initialize(context)
        return _world.value
    }

    fun world(context: Context, id: String?): StoryWorld? {
        initialize(context)
        return _worlds.value.firstOrNull { it.id == id }
    }

    fun worldForCharacter(context: Context, characterId: String?): StoryWorld? {
        val character = character(context, characterId) ?: return null
        return world(context, character.worldId)
    }

    fun charactersForWorld(context: Context, worldId: String): List<CharacterCard> {
        initialize(context)
        return _characters.value.filter { it.worldId == worldId }
    }

    fun personasForWorld(context: Context, worldId: String): List<PlayerPersona> {
        initialize(context)
        return _personas.value.filter { it.worldId == worldId }
    }

    @Synchronized
    fun selectWorld(context: Context, worldId: String) {
        initialize(context)
        _world.value = _worlds.value.firstOrNull { it.id == worldId } ?: return
        persist(context)
    }

    @Synchronized
    fun saveWorld(context: Context, world: StoryWorld): StoryWorld {
        initialize(context)
        val now = System.currentTimeMillis()
        val normalized = world.copy(
            id = world.id.ifBlank { UUID.randomUUID().toString() },
            name = world.name.trim().ifBlank { "我的世界" },
            createdAt = world.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        _worlds.value = (_worlds.value.filterNot { it.id == normalized.id } + normalized)
            .sortedByDescending { it.updatedAt }
        _world.value = normalized
        persist(context)
        return normalized
    }

    fun character(context: Context, id: String?): CharacterCard? {
        initialize(context)
        return _characters.value.firstOrNull { it.id == id }
    }

    fun persona(context: Context, id: String?): PlayerPersona? {
        initialize(context)
        return _personas.value.firstOrNull { it.id == id }
    }

    fun defaultPersona(context: Context): PlayerPersona? {
        initialize(context)
        val worldId = _world.value?.id
        val candidates = if (worldId == null) _personas.value else _personas.value.filter { it.worldId == worldId }
        return candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull()
    }

    @Synchronized
    fun saveCharacter(context: Context, card: CharacterCard): CharacterCard {
        initialize(context)
        val now = System.currentTimeMillis()
        val normalized = card.copy(
            id = card.id.ifBlank { UUID.randomUUID().toString() },
            worldId = card.worldId.ifBlank { _world.value?.id ?: "default-world" },
            name = card.name.trim(),
            updatedAt = now,
            createdAt = card.createdAt.takeIf { it > 0 } ?: now,
        )
        require(normalized.name.isNotBlank()) { "角色名称不能为空" }
        _characters.value = (_characters.value.filterNot { it.id == normalized.id } + normalized)
            .sortedByDescending { it.updatedAt }
        persist(context)
        return normalized
    }

    @Synchronized
    fun savePersona(context: Context, persona: PlayerPersona): PlayerPersona {
        initialize(context)
        val now = System.currentTimeMillis()
        val normalized = persona.copy(
            id = persona.id.ifBlank { UUID.randomUUID().toString() },
            worldId = persona.worldId.ifBlank { _world.value?.id ?: "default-world" },
            name = persona.name.trim().ifBlank { "玩家" },
            updatedAt = now,
            createdAt = persona.createdAt.takeIf { it > 0 } ?: now,
        )
        val prior = _personas.value.filterNot { it.id == normalized.id }
            .map {
                if (normalized.isDefault && it.worldId == normalized.worldId) it.copy(isDefault = false) else it
            }
        val shouldDefault = normalized.isDefault || prior.none { it.worldId == normalized.worldId }
        _personas.value = (prior + normalized.copy(isDefault = shouldDefault))
            .sortedWith(compareByDescending<PlayerPersona> { it.isDefault }.thenByDescending { it.updatedAt })
        persist(context)
        return _personas.value.first { it.id == normalized.id }
    }

    @Synchronized
    fun deleteCharacter(context: Context, id: String) {
        initialize(context)
        _characters.value = _characters.value.filterNot { it.id == id }
        persist(context)
    }

    @Synchronized
    fun deletePersona(context: Context, id: String) {
        initialize(context)
        val deleted = _personas.value.firstOrNull { it.id == id }
        val remaining = _personas.value.filterNot { it.id == id }.toMutableList()
        if (deleted?.isDefault == true) {
            val replacementIndex = remaining.indexOfFirst { it.worldId == deleted.worldId }
            if (replacementIndex >= 0 && remaining.none { it.worldId == deleted.worldId && it.isDefault }) {
                remaining[replacementIndex] = remaining[replacementIndex].copy(isDefault = true)
            }
        }
        _personas.value = remaining
        persist(context)
    }

    fun importCharacter(context: Context, source: String): CharacterCard {
        val preview = SillyTavernCardParser.parseJson(source)
        return saveImportedCharacter(context, preview)
    }

    fun saveImportedCharacter(
        context: Context,
        preview: CharacterCardImportPreview,
        worldId: String = currentWorld(context)?.id ?: "default-world",
    ): CharacterCard {
        val avatarPath = preview.avatarPng?.let { bytes ->
            val dir = File(context.filesDir, "immersive-media").apply { mkdirs() }
            File(dir, "character-avatar-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }.absolutePath
        }
        return saveCharacter(
            context,
            preview.card.copy(
                id = UUID.randomUUID().toString(),
                worldId = worldId,
                avatarPath = avatarPath ?: preview.card.avatarPath,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Role chats use this directory instead of the application-wide memory directory. */
    fun characterMemoryRepository(
        context: Context,
        characterId: String,
        worldId: String? = null,
        personaId: String? = null,
    ): MemoryRepository {
        val safeCharacter = safePathId(characterId)
        if (worldId == null && personaId == null) {
            return MemoryRepository(File(context.filesDir, "minis-global/character-memory/$safeCharacter"))
        }
        val target = File(
            context.filesDir,
            "minis-global/role-memory/${safePathId(worldId)}/${safePathId(personaId)}/$safeCharacter",
        )
        val legacy = File(context.filesDir, "minis-global/character-memory/$safeCharacter")
        if (!target.exists() && legacy.isDirectory) {
            target.mkdirs()
            legacy.listFiles()?.filter { it.isFile }?.forEach { source ->
                runCatching { source.copyTo(File(target, source.name), overwrite = false) }
            }
        }
        return MemoryRepository(target)
    }

    fun characterMemoryFileCount(context: Context, characterId: String): Int {
        val safeId = safePathId(characterId)
        val legacyCount = File(context.filesDir, "minis-global/character-memory/$safeId")
            .walkTopDown().count { it.isFile && it.extension.equals("md", true) }
        val scopedCount = File(context.filesDir, "minis-global/role-memory")
            .walkTopDown().count { it.isFile && it.parentFile?.name == safeId && it.extension.equals("md", true) }
        return legacyCount + scopedCount
    }

    fun copyMedia(context: Context, uri: Uri, kind: String): String {
        val dir = File(context.filesDir, "immersive-media").apply { mkdirs() }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = when {
            mime.endsWith("png") -> "png"
            mime.endsWith("webp") -> "webp"
            mime.endsWith("gif") -> "gif"
            else -> "jpg"
        }
        val target = File(dir, "${kind}-${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选图片" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    private fun persist(context: Context) {
        val chars = JSONArray().apply { _characters.value.forEach { put(it.toJson()) } }
        val personas = JSONArray().apply { _personas.value.forEach { put(it.toJson()) } }
        val worlds = JSONArray().apply { _worlds.value.forEach { put(it.toJson()) } }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHARACTERS, chars.toString())
            .putString(KEY_PERSONAS, personas.toString())
            .putString(KEY_WORLD, _world.value?.toJson()?.toString())
            .putString(KEY_WORLDS, worlds.toString())
            .putString(KEY_CURRENT_WORLD_ID, _world.value?.id)
            .apply()
    }

    private fun safePathId(value: String?): String = value.orEmpty()
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)
        .ifBlank { "default" }

    private fun <T> parseArray(raw: String?, parser: (JSONObject) -> T): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { json -> runCatching { parser(json) }.getOrNull()?.let(::add) }
                }
            }
        }.getOrDefault(emptyList())
    }
}
