package com.openminis.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * [T-announcement-v2]（用户 2026-09-27 聊天定稿：「普通公告互通/新公告
 * 跳脸/版本公告两通道独立/平时主要管通用公告」）hub 形态公告源。
 *
 * 同一解析器服务两个 host（双源同源收敛）：
 * - Gitee 道：gitee.com/ccbili/novex/raw/main/（默认源，国内直连）
 * - GitHub 道：raw.githubusercontent.com/ccbili30-collab/novex/main/
 *   （hub 的 GitHub 镜像；海外顺连。此前 GitHub 道读 release 正文，
 *   互通对它不成立——一处写作（hub）两道同源后成立）
 *
 * 索引条目可选 `channel`（stable|preview）：缺省/非法=通用（双版本
 * 互通）；通道条目只下发对应通道（[filterForChannel]）。
 *
 * 护栏沿 v1：file 限定 announcements/*.md 且拒 `..` 段；≤5 条截尾保头；
 * 任一环节失败整体 null——调用方回落内置归档，绝不阻塞。
 */
internal object GiteeAnnouncementSource {

    private const val TAG = "GiteeAnnouncement"
    internal const val GITEE_RAW_BASE = "https://gitee.com/ccbili/novex/raw/main/"
    internal const val GITHUB_MIRROR_RAW_BASE = "https://raw.githubusercontent.com/ccbili30-collab/novex/main/"
    private const val ENTRY_DIR = "announcements/"
    internal const val MAX_ENTRIES = 5

    internal data class AnnouncementEntry(
        val file: String,
        val title: String,
        val date: String,
        val channel: String? = null,
    )

    /** 纯解析：新到旧索引 → 受信条目（路径校验+通道字段+上限）；非法/空整体 null。 */
    internal fun parseAnnouncementsIndex(body: String): List<AnnouncementEntry>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val items = runCatching { root.getJSONArray("announcements") }.getOrNull() ?: return null
        val entries = buildList {
            for (i in 0 until items.length()) {
                val e = items.optJSONObject(i) ?: continue
                val file = e.optString("file").trim()
                // 拒目录穿越：OkHttp 会规范化 ".." 段逃出白名单目录
                if (!file.startsWith(ENTRY_DIR) || !file.endsWith(".md") || file.contains("..")) continue
                val title = e.optString("title").trim()
                if (title.isEmpty()) continue
                val channel = e.optString("channel").trim().lowercase()
                    .takeIf { it == "stable" || it == "preview" }
                add(AnnouncementEntry(file = file, title = title, date = e.optString("date").trim(), channel = channel))
            }
        }
        // 空索引或全部条目无效 → 整体 null（调用方回落内置归档）
        return entries.take(MAX_ENTRIES).takeIf { it.isNotEmpty() }
    }

    /** 通用（channel=null）双版本互通；通道条目只下发对应通道。 */
    internal fun filterForChannel(
        entries: List<AnnouncementEntry>,
        channel: UpdateChannel,
    ): List<AnnouncementEntry> = entries.filter { it.channel == null || it.channel == channel.wireName }

    internal fun rawUrl(base: String, file: String): String = base + file

    /**
     * 拉本通道可见公告（索引+逐条原文），[base] 选 host。任何失败返回
     * null（调用方回落内置归档）；单条正文失败跳过而非整体失败。
     * 通道判定以 BuildConfig.UPDATE_CHANNEL 为准（纯逻辑走
     * [parseAnnouncementsIndex]+[filterForChannel] 组合测试）。
     */
    internal suspend fun fetchAnnouncements(client: OkHttpClient, base: String): List<NovexAnnouncement>? =
        withContext(Dispatchers.IO) {
            val lane = UpdateChannel.entries.firstOrNull { c ->
                c.wireName.equals(com.openminis.app.BuildConfig.UPDATE_CHANNEL, ignoreCase = true)
            } ?: UpdateChannel.STABLE
            val entries = runCatching {
                client.newCall(Request.Builder().url(rawUrl(base, "announcements.json")).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    parseAnnouncementsIndex(resp.body?.string().orEmpty())
                }
            }.getOrNull() ?: return@withContext null
            val visible = filterForChannel(entries, lane)
            if (visible.isEmpty()) return@withContext null
            val announcements = visible.mapNotNull { entry ->
                runCatching {
                    client.newCall(Request.Builder().url(rawUrl(base, entry.file)).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() }?.let { markdown ->
                            NovexAnnouncement(versionName = entry.date, title = entry.title, markdown = markdown, id = entry.file)
                        }
                    }
                }.getOrNull()
            }
            if (announcements.isEmpty()) return@withContext null
            com.openminis.app.logging.AppLogger.info(TAG, "announcements loaded=${announcements.size} base=${base.take(40)}")
            announcements
        }
}
