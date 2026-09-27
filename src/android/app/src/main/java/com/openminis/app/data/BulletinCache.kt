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
 * 目录由调用方注入（Context.getFilesDir 或测试 TemporaryFolder），
 * 存放 `novex` 目录下 `bulletin-cache.json`。损坏/缺失/空内容静默
 * null。磁盘 I/O 全部走 [Dispatchers.IO]（净眼 S6 附注：面板路径
 * 原为主线程同步读写）。
 */
internal object BulletinCache {

    private const val FILE_NAME = "bulletin-cache.json"

    suspend fun load(dir: File): NovexBulletin? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(File(dir, "novex"), FILE_NAME)
            if (!file.isFile) return@runCatching null
            val root = JSONObject(file.readText())
            val announcements = readList(root.optJSONArray("announcements")) { o ->
                NovexAnnouncement(
                    versionName = o.optString("versionName"),
                    title = o.optString("title"),
                    markdown = o.optString("markdown"),
                    id = o.optString("id").ifEmpty { o.optString("versionName") },
                ).takeIf { it.title.isNotEmpty() && it.markdown.isNotEmpty() }
            }
            val releaseNotes = readList(root.optJSONArray("releaseNotes")) { o ->
                UpdateChecker.ReleaseNote(
                    versionName = o.optString("versionName"),
                    releaseName = o.optString("releaseName"),
                    changelog = o.optString("changelog"),
                ).takeIf { it.versionName.isNotEmpty() }
            }
            NovexBulletin(announcements = announcements, releaseNotes = releaseNotes, live = true)
                .takeIf { it.announcements.isNotEmpty() || it.releaseNotes.isNotEmpty() }
        }.getOrNull()
    }

    suspend fun save(dir: File, bulletin: NovexBulletin) {
        withContext(Dispatchers.IO) {
            runCatching {
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
    }

    private fun <T> readList(array: JSONArray?, map: (JSONObject) -> T?): List<T> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(map) }
    }
}
