package com.openminis.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-bulletin-v3]（2026-09-28 定稿：名册驱动 + 懒取 + 卡片交叠）
 * 名册（manifest）是列表的唯一权威：客户端显示什么、什么顺序，完全照它。
 * 正文懒取：用户展开/跳脸才拉，拉回落地 per-item 磁盘缓存；条目 rev 变了
 * 才重拉；名册移除（下线）即列表消失、缓存作废。跳脸只在冷启动出现，
 * 修改已读公告不重跳。
 */
internal data class BulletinManifestEntry(
    val id: String,
    val title: String,
    val date: String,
    val rev: Int = 1,
)

internal data class BulletinManifest(
    val announcements: List<BulletinManifestEntry>,
    val releaseNotes: List<BulletinManifestEntry>,
) {
    val allIds: Set<String> get() = announcements.map { it.id }.toSet() + releaseNotes.map { it.id }.toSet()
}

internal object BulletinHub {

    private const val DIR = "novex/bulletin"
    private const val MANIFEST_FILE = "manifest.json"
    private const val BODIES_FILE = "bodies.json"

    fun rawBase(source: UpdateSource): String = when (source) {
        UpdateSource.GITEE -> GiteeAnnouncementSource.GITEE_RAW_BASE
        UpdateSource.GITHUB -> GiteeAnnouncementSource.GITHUB_MIRROR_RAW_BASE
    }

    /**
     * 拉名册：公告节复用 hub 索引解析（路径护栏/通道过滤/≤5 条沿用），
     * 新增可选 `releaseNotes` 节（往期更新说明，通道条目同样过滤）。
     * 任一失败整体 null——调用方回落缓存且不跳脸。
     */
    suspend fun fetchManifest(client: OkHttpClient, base: String): BulletinManifest? =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(base + "announcements.json").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    parseManifest(resp.body?.string().orEmpty())
                }
            }.getOrNull()
        }

    /** 纯解析（JVM 可测）：失败/空返回 null。 */
    internal fun parseManifest(body: String): BulletinManifest? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val lane = UpdateChannel.entries.firstOrNull { c ->
            c.wireName.equals(com.openminis.app.BuildConfig.UPDATE_CHANNEL, ignoreCase = true)
        } ?: UpdateChannel.STABLE
        val announcements = GiteeAnnouncementSource.parseAnnouncementsIndex(body)
            ?.let { GiteeAnnouncementSource.filterForChannel(it, lane) }
            ?.map { BulletinManifestEntry(it.file, it.title, it.date, it.rev) }
            ?: return null
        val notes = root.optJSONArray("releaseNotes")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").trim()
                if (!file.startsWith("announcements/") && !file.startsWith("notes/")) return@mapNotNull null
                if (file.endsWith(".md").not() || file.contains("..")) return@mapNotNull null
                val channel = o.optString("channel").trim().lowercase()
                    .takeIf { it == "stable" || it == "preview" }
                if (channel != null && channel != lane.wireName) return@mapNotNull null
                val title = o.optString("version").ifEmpty { o.optString("title") }.trim()
                if (title.isEmpty()) return@mapNotNull null
                BulletinManifestEntry(
                    id = file,
                    title = title,
                    date = o.optString("date").trim(),
                    rev = o.optString("rev").toIntOrNull()?.coerceAtLeast(1) ?: 1,
                )
            }
        }.orEmpty()
        return BulletinManifest(announcements = announcements, releaseNotes = notes)
    }

    /** 拉单条正文；失败 null，绝不抛。落盘由调用方以名册 rev 执行 [storeBody]。 */
    suspend fun fetchBody(client: OkHttpClient, base: String, id: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(base + id).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }

    // ------------------------------------------------------------------
    // 磁盘：manifest.json（名册缓存，离线兜底）+ bodies.json（per-item 正文+rev）
    // ------------------------------------------------------------------
    fun manifestDir(filesDir: File): File = File(filesDir, DIR)

    fun saveManifest(filesDir: File, manifest: BulletinManifest) {
        runCatching {
            val parent = manifestDir(filesDir).apply { mkdirs() }
            fun arr(entries: List<BulletinManifestEntry>): JSONArray = JSONArray().apply {
                entries.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("title", it.title)
                            .put("date", it.date)
                            .put("rev", it.rev),
                    )
                }
            }
            File(parent, MANIFEST_FILE).writeText(
                JSONObject()
                    .put("savedAt", System.currentTimeMillis())
                    .put("announcements", arr(manifest.announcements))
                    .put("releaseNotes", arr(manifest.releaseNotes))
                    .toString(),
            )
            reconcile(filesDir, manifest)
        }
    }

    fun loadManifest(filesDir: File): BulletinManifest? = runCatching {
        val file = File(manifestDir(filesDir), MANIFEST_FILE)
        if (!file.isFile) return@runCatching null
        parseCachedManifest(file.readText())
    }.getOrNull()

    /** 与在线解析同构，但不做通道过滤（缓存的就是本通道视角）。 */
    internal fun parseCachedManifest(body: String): BulletinManifest? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        fun read(key: String): List<BulletinManifestEntry> = root.optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                BulletinManifestEntry(id, o.optString("title"), o.optString("date"),
                    o.optString("rev").toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }
        }.orEmpty()
        val announcements = read("announcements")
        val notes = read("releaseNotes")
        return BulletinManifest(announcements, notes)
            .takeIf { it.announcements.isNotEmpty() || it.releaseNotes.isNotEmpty() }
    }

    /** 名册对账：下线条目的正文缓存一并作废。 */
    fun reconcile(filesDir: File, manifest: BulletinManifest) {
        runCatching {
            val file = File(manifestDir(filesDir), BODIES_FILE)
            if (!file.isFile) return
            val root = JSONObject(file.readText())
            val keep = manifest.allIds
            root.keys().asSequence().toList().forEach { id -> if (id !in keep) root.remove(id) }
            file.writeText(root.toString())
        }
    }

    fun cachedBody(filesDir: File, id: String): String? = bodies(filesDir)?.optJSONObject(id)
        ?.takeIf { it.optString("body").isNotEmpty() }?.optString("body")

    fun cachedRev(filesDir: File, id: String): Int? = bodies(filesDir)?.optJSONObject(id)
        ?.optInt("rev")?.takeIf { it > 0 }

    fun storeBody(filesDir: File, id: String, rev: Int, body: String) {
        runCatching {
            val parent = manifestDir(filesDir).apply { mkdirs() }
            val root = runCatching { JSONObject(File(parent, BODIES_FILE).readText()) }.getOrElse { JSONObject() }
            root.put(id, JSONObject().put("rev", rev).put("body", body))
            File(parent, BODIES_FILE).writeText(root.toString())
        }
    }

    private fun bodies(filesDir: File): JSONObject? = runCatching {
        val file = File(manifestDir(filesDir), BODIES_FILE)
        if (!file.isFile) return@runCatching null
        JSONObject(file.readText())
    }.getOrNull()
}
