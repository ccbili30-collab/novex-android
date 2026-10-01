package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 环境变量仓库：值加密、元数据明文 JSON（血统清剿 P3.7 就地真重写；
 * env-vars.json 字段集、加密 prefs 名、键名正则为持久化契约冻结面）。
 *
 * 分工：键名/ID/备注等元数据落 `filesDir/env-vars.json`；值本体落
 * EncryptedSharedPreferences（AES256-GCM）。对齐 iOS EnvVarStore。
 */
class EnvVarRepository(private val context: Context) {

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * 该变量用途的可读描述，缺省空串。存明文 JSON 元数据（非机密），
         * 对齐 iOS EnvVarEntry.note。
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())

    /** 条目元数据的可观察快照（不含值）。 */
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val vault: SharedPreferences by lazy {
        // T-android-keystore-aead-fail：带自愈的封装。
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
    }

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE)

    init {
        loadMetadata()
    }

    // -- 校验 --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    /**
     * 值白名单：可打印 ASCII（0x20-0x7E）+ 制表符（0x09）。iOS 粘贴会带进
     * 不可见控制标量（如 \u009B），会弄坏 shell 环境注入。
     */
    private fun sanitizeValue(value: String): String =
        value.filter { ch -> ch.code == 0x09 || ch.code in 0x20..0x7E }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.id != excludeId && it.key.equals(key, ignoreCase = true) }

    // -- 增删改 --

    fun add(key: String, value: String, note: String = ""): Boolean {
        val name = key.trim().uppercase()
        if (!isValidKey(name) || isDuplicateKey(name)) return false

        _entries.value = _entries.value + EnvVarEntry(key = name, note = note.trim())
        vault.edit().putString(name, sanitizeValue(value)).apply()
        persistMetadata()
        Log.i(TAG, "Added env var: $name")
        return true
    }

    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        val name = newKey.trim().uppercase()
        if (!isValidKey(name)) return false

        val existing = _entries.value.find { it.id == id } ?: return false
        if (isDuplicateKey(name, excludeId = id)) return false

        // 键名变了：旧键位先从加密仓里清掉。
        if (existing.key != name) {
            vault.edit().remove(existing.key).apply()
        }
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(key = name, note = newNote.trim()) else it
        }
        vault.edit().putString(name, sanitizeValue(newValue)).apply()
        persistMetadata()
        Log.i(TAG, "Updated env var: ${existing.key} → $name")
        return true
    }

    fun delete(id: String) {
        val victim = _entries.value.find { it.id == id } ?: return
        vault.edit().remove(victim.key).apply()
        _entries.value = _entries.value.filter { it.id != id }
        persistMetadata()
        Log.i(TAG, "Deleted env var: ${victim.key}")
    }

    fun getValue(key: String): String? = vault.getString(key, null)

    /**
     * 供沙箱注入用的全量 Map。直读存储而非内存态，线程安全。
     */
    fun allAsDict(): Map<String, String> =
        _entries.value.mapNotNull { entry ->
            vault.getString(entry.key, null)?.let { entry.key to it }
        }.toMap()

    // -- 元数据持久化 --

    private fun persistMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val row = JSONObject()
                    .put("id", entry.id)
                    .put("key", entry.key)
                    .put("createdAt", entry.createdAt)
                if (entry.note.isNotEmpty()) row.put("note", entry.note)
                array.put(row)
            }
            metadataFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) return
            val rows = JSONArray(metadataFile.readText())
            val restored = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                EnvVarEntry(
                    id = row.optString("id", UUID.randomUUID().toString()),
                    key = row.optString("key", ""),
                    note = row.optString("note", ""),
                    createdAt = row.optLong("createdAt", 0),
                )
            }
            _entries.value = restored
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}
