package com.openminis.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * [T-announcement-system]（用户 2026-09-27：「公告也从这里获取，更新来源
 * 同更新源」+「公告我来调整」）Gitee 道公告源：读 novex-hub 仓库根的
 * announcements.json 索引（新到旧），再逐条拉 announcements/ 下的
 * markdown 原文。作者侧零门槛：写 md 文件 + 索引加一行 + push，无需
 * 发版。GitHub 道公告继续走 release 正文（NovexBulletinPolicy），两道
 * 各自独立、随 [UpdateSource] 切换。
 *
 * 护栏：索引条目 file 必须以 "announcements/" 开头且 .md 结尾（防任意
 * 路径抓取）；最多取 [MAX_ENTRIES] 条（索引顺序即优先级）；任一环节
 * 失败整体返回 null——调用方回落 [NovexBulletinDefaults]（公告是
 * 潜在收益，绝不因外部内容故障阻塞更新检查）。
 */
internal object GiteeAnnouncementSource {

    private const val TAG = "GiteeAnnouncement"
    private const val RAW_BASE = "https://gitee.com/ccbili/novex/raw/main/"
    internal const val INDEX_URL = "${RAW_BASE}announcements.json"
    private const val ENTRY_DIR = "announcements/"
    internal const val MAX_ENTRIES = 5

    internal data class AnnouncementEntry(
        val file: String,
        val title: String,
        val date: String,
    )

    /** 纯解析：新到旧索引 → 受信条目（路径校验+上限）；非法整体 null。 */
    internal fun parseAnnouncementsIndex(body: String): List<AnnouncementEntry>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val items = runCatching { root.getJSONArray("announcements") }.getOrNull() ?: return null
        val entries = buildList {
            for (i in 0 until items.length()) {
                val e = items.optJSONObject(i) ?: continue
                val file = e.optString("file").trim()
                // [净眼 S1] 拒目录穿越：OkHttp 会规范化 ".." 段逃出白名单目录
                if (!file.startsWith(ENTRY_DIR) || !file.endsWith(".md") || file.contains("..")) continue
                val title = e.optString("title").trim()
                if (title.isEmpty()) continue
                add(AnnouncementEntry(file = file, title = title, date = e.optString("date").trim()))
            }
        }
        // [净眼 S1] 空索引或全部条目无效 → 整体 null（调用方回落内置归档）
        return entries.take(MAX_ENTRIES).takeIf { it.isNotEmpty() }
    }

    internal fun rawUrl(file: String): String = RAW_BASE + file

    /**
     * 拉全量公告（索引+逐条原文）。任何失败返回 null（调用方回落内置
     * 归档）；正文为空的条目跳过而非整体失败。
     */
    internal suspend fun fetchBulletin(client: OkHttpClient): NovexBulletin? = withContext(Dispatchers.IO) {
        val entries = runCatching {
            client.newCall(Request.Builder().url(INDEX_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                parseAnnouncementsIndex(resp.body?.string().orEmpty())
            }
        }.getOrNull() ?: return@withContext null
        if (entries.isEmpty()) return@withContext null
        val announcements = entries.mapNotNull { entry ->
            runCatching {
                client.newCall(Request.Builder().url(rawUrl(entry.file)).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() }?.let { markdown ->
                        NovexAnnouncement(versionName = entry.date, title = entry.title, markdown = markdown)
                    }
                }
            }.getOrNull()
        }
        if (announcements.isEmpty()) return@withContext null
        com.openminis.app.logging.AppLogger.info(TAG, "hub announcements loaded=${announcements.size}")
        NovexBulletin(announcements = announcements, releaseNotes = emptyList())
    }
}
