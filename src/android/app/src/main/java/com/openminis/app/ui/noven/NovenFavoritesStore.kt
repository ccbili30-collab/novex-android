package com.openminis.app.ui.noven

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 本地珍藏：SharedPreferences 里的卡片 id 集合，对外是可观察状态。 */
internal class NovenFavoritesStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("noven_favorites", Context.MODE_PRIVATE)

    var favorites by mutableStateOf(load())
        private set

    private fun load(): Set<String> =
        prefs.getStringSet("card_ids", emptySet()).orEmpty().toSet()

    fun isFavorite(cardId: String): Boolean = cardId in favorites

    fun toggle(cardId: String) {
        val next = favorites.toMutableSet()
        if (!next.remove(cardId)) next += cardId
        prefs.edit().putStringSet("card_ids", next).apply()
        favorites = next
    }

    companion object {
        @Volatile
        private var instance: NovenFavoritesStore? = null

        /** 进程内单例：各 tab 的状态被 SaveableStateHolder 保留，用同一个实例才能互通。 */
        fun get(context: Context): NovenFavoritesStore =
            instance ?: synchronized(this) {
                instance ?: NovenFavoritesStore(context).also { instance = it }
            }
    }
}
