package com.openminis.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-bulletin-cache]（用户 2026-09-27：「用户打开应用点开公告按钮第一时间
 * 就应该看到关闭前的公告缓存，有新的再刷新」）公告面板的磁盘缓存：
 * 每次成功拉取（live）后落盘，点开面板先显缓存再后台刷新；刷新失败
 * 保留缓存不覆盖（fetchBulletin 的空回落即失败信号）。
 *
 * 目录由调用方注入（[Context.getFilesDir] 或测试 TemporaryFolder），
 * 存放 `novex/bulletin-cache.json`。损坏/缺失静默 null。
 */
internal object BulletinCache {

    private const val FILE_NAME = "bulletin-cache.json"

    fun load(dir: File): NovexBulletin? = runCatching {
        val file = File(File(dir, "novex"), FILE_NAME)
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        fun announcements(): List<NovexAnnouncement> {
            val a = root.optJSONArray("announcements") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                NovexAnnouncement(
                    versionName = o.optString("versionName"),
                    title = o.optString("title"),
                    markdown = o.optString("markdown"),
                    id = o.optString("id").ifEmpty { o.optString("versionName") },
                ).takeIf { it.title.isNotEmpty() && it.markdown.isNotEmpty() }
            }
        }
        fun releaseNotes(): List<UpdateChecker.ReleaseNote> {
            val a = root.optJSONArray("releaseNotes") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                UpdateChecker.ReleaseNote(
                    versionName = o.optString("versionName"),
                    releaseName = o.optString("releaseName"),
                    changelog = o.optString("changelog"),
                ).takeIf { it.versionName.isNotEmpty() }
            }
        }
        NovexBulletin(announcements = announcements(), releaseNotes = releaseNotes(), live = true)
            .takeIf { it.announcements.isNotEmpty() || it.releaseNotes.isNotEmpty() }
    }.getOrNull()

    fun save(dir: File, bulletin: NovexBulletin) = runCatching<Unit> {
        val parent = File(dir, "novex").apply { mkdirs() }
        val root = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("announcements", JSONArray(bulletin.announcements.map {
                JSONObject()
                    .put("id", it.id)
                    .put("versionName", it.versionName)
                    .put("title", it.title)
                    .put("markdown", it.markdown)
            }))
            .put("releaseNotes", JSONArray(bulletin.releaseNotes.map {
                JSONObject()
                    .put("versionName", it.versionName)
                    .put("releaseName", it.releaseName)
                    .put("changelog", it.changelog)
            }))
        File(parent, FILE_NAME).writeText(root.toString())
    } // 失败静默：缓存是增强，绝不阻塞公告链路
}
