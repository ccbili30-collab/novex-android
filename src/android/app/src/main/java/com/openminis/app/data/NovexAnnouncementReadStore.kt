package com.openminis.app.data

import android.content.Context

/**
 * [T-announcement-v2] 公告已读记忆（用户口径：「有新公告就跳脸让他
 * 关闭」——关闭即已读）。键=公告 id（hub 道=索引文件名；GitHub 道默认
 * versionName）。本地 prefs，不跨设备同步（隐私原则：无上报）。
 * 同文件内容更新不重复跳脸（键是文件名不是内容哈希——作者改错字
 * 不该再骚扰全体用户）。
 */
internal object NovexAnnouncementReadStore {
    private const val PREFS = "novex_announcement_read"
    private const val KEY_READ_IDS = "read_ids"

    fun readIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_READ_IDS, emptySet()) ?: emptySet()

    fun markRead(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_READ_IDS, readIds(context) + ids.toSet())
            .apply()
    }

    /** 未读判定纯函数（JVM 可测）：保持公告列表顺序。 */
    fun unread(announcements: List<NovexAnnouncement>, readIds: Set<String>): List<NovexAnnouncement> =
        announcements.filter { it.id !in readIds }
}
