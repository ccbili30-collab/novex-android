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

    private val _characters = MutableStateFlow<List<CharacterCard>>(emptyList())
    val characters: StateFlow<List<CharacterCard>> = _characters.asStateFlow()
    private val _personas = MutableStateFlow<List<PlayerPersona>>(emptyList())
    val personas: StateFlow<List<PlayerPersona>> = _personas.asStateFlow()
    private val _world = MutableStateFlow<StoryWorld?>(null)
    val world: StateFlow<StoryWorld?> = _world.asStateFlow()
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _characters.value = parseArray(prefs.getString(KEY_CHARACTERS, null)) { CharacterCard.fromJson(it) }
            .filter { it.name.isNotBlank() }
            .sortedByDescending { it.updatedAt }
        _personas.value = parseArray(prefs.getString(KEY_PERSONAS, null)) { PlayerPersona.fromJson(it) }
            .filter { it.name.isNotBlank() }
            .sortedWith(compareByDescending<PlayerPersona> { it.isDefault }.thenByDescending { it.updatedAt })
        _world.value = prefs.getString(KEY_WORLD, null)?.let {
            runCatching { StoryWorld.fromJson(JSONObject(it)) }.getOrNull()
        }
        initialized = true
    }

    fun currentWorld(context: Context): StoryWorld? {
        initialize(context)
        return _world.value
    }

    @Synchronized
    fun saveWorld(context: Context, world: StoryWorld): StoryWorld {
        initialize(context)
        val now = System.currentTimeMillis()
        val normalized = world.copy(
            name = world.name.trim().ifBlank { "我的世界" },
            createdAt = world.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
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
        return _personas.value.firstOrNull { it.isDefault } ?: _personas.value.firstOrNull()
    }

    @Synchronized
    fun saveCharacter(context: Context, card: CharacterCard): CharacterCard {
        initialize(context)
        val now = System.currentTimeMillis()
        val normalized = card.copy(
            id = card.id.ifBlank { UUID.randomUUID().toString() },
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
            name = persona.name.trim(),
            updatedAt = now,
            createdAt = persona.createdAt.takeIf { it > 0 } ?: now,
        )
        require(normalized.name.isNotBlank()) { "玩家身份名称不能为空" }
        val prior = _personas.value.filterNot { it.id == normalized.id }
            .map { if (normalized.isDefault) it.copy(isDefault = false) else it }
        val shouldDefault = normalized.isDefault || prior.isEmpty()
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
        val remaining = _personas.value.filterNot { it.id == id }.toMutableList()
        if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
            remaining[0] = remaining[0].copy(isDefault = true)
        }
        _personas.value = remaining
        persist(context)
    }

    fun importCharacter(context: Context, source: String): CharacterCard {
        val preview = SillyTavernCardParser.parseJson(source)
        return saveImportedCharacter(context, preview)
    }

    fun saveImportedCharacter(context: Context, preview: CharacterCardImportPreview): CharacterCard {
        val avatarPath = preview.avatarPng?.let { bytes ->
            val dir = File(context.filesDir, "immersive-media").apply { mkdirs() }
            File(dir, "character-avatar-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }.absolutePath
        }
        return saveCharacter(
            context,
            preview.card.copy(
                id = UUID.randomUUID().toString(),
                avatarPath = avatarPath ?: preview.card.avatarPath,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Role chats use this directory instead of the application-wide memory directory. */
    fun characterMemoryRepository(context: Context, characterId: String): MemoryRepository {
        val safeId = characterId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "unknown" }
        return MemoryRepository(File(context.filesDir, "minis-global/character-memory/$safeId"))
    }

    fun characterMemoryFileCount(context: Context, characterId: String): Int {
        val safeId = characterId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "unknown" }
        return File(context.filesDir, "minis-global/character-memory/$safeId")
            .listFiles()?.count { it.isFile && it.extension.equals("md", true) } ?: 0
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
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHARACTERS, chars.toString())
            .putString(KEY_PERSONAS, personas.toString())
            .putString(KEY_WORLD, _world.value?.toJson()?.toString())
            .apply()
    }

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
