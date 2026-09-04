package com.openminis.app.ui.sessions

import android.content.Context
import org.json.JSONArray

internal fun mergeNovexManualOrder(
    sourceIds: List<String>,
    savedIds: List<String>,
): List<String> {
    val available = sourceIds.toSet()
    val ordered = savedIds.filter(available::contains).distinct().toMutableList()
    sourceIds.filterNot(ordered::contains).forEach(ordered::add)
    return ordered
}

internal fun moveNovexOrderedId(
    ids: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String> {
    if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return ids
    return ids.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

internal enum class NovexManualOrderKind(val preferenceKey: String) {
    CONVERSATIONS("conversations"),
    WORLDS("worlds"),
    CHARACTERS("characters"),
    INTERACTIVE_FICTION("interactive_fiction"),
}

internal class NovexManualOrderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "novex_manual_order",
        Context.MODE_PRIVATE,
    )

    fun read(kind: NovexManualOrderKind): List<String> {
        val raw = preferences.getString(kind.preferenceKey, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())
    }

    fun write(kind: NovexManualOrderKind, ids: List<String>) {
        preferences.edit().putString(kind.preferenceKey, JSONArray(ids).toString()).apply()
    }
}
