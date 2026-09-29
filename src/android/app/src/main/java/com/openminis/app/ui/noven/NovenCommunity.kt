package com.openminis.app.ui.noven

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/** 卡片 → 演示作者归属。avatar 指向 noven-feed/avatars/ 下的文件名，联网后由服务端字段取代。 */
internal data class NovenAttribution(val author: String, val followed: Boolean, val avatarPath: String?)

/**
 * 读取 filesDir/noven-feed/authors.json 的演示归属表；文件不存在时归属为空，
 * 所有卡片按「本地作者」显示。联网后这一层换成真实关注关系。
 */
internal class NovenCommunity private constructor(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "noven-feed")
    private val file = File(dir, "authors.json")

    var attributions: Map<String, NovenAttribution> by mutableStateOf(load())
        private set

    fun attribution(cardId: String): NovenAttribution? = attributions[cardId]

    fun refresh() {
        attributions = load()
    }

    private fun load(): Map<String, NovenAttribution> {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        val cards = json.optJSONObject("cards") ?: return emptyMap()
        return cards.keys().asSequence().mapNotNull { id ->
            val entry = cards.optJSONObject(id) ?: return@mapNotNull null
            val author = entry.optString("author").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val avatar = entry.optString("avatar").takeIf { it.isNotBlank() }
                ?.let { File(dir, "avatars/$it") }
                ?.takeIf { it.exists() }?.absolutePath
            id to NovenAttribution(author, entry.optBoolean("followed"), avatar)
        }.toMap()
    }

    companion object {
        @Volatile
        private var instance: NovenCommunity? = null

        fun get(context: Context): NovenCommunity =
            instance ?: synchronized(this) {
                instance ?: NovenCommunity(context).also { instance = it }
            }
    }
}
