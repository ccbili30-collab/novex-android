package novex.android.repo

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 技能搬运层：zip 打包/解包、GitHub URL 解析、兄弟文件递归下载。
 *
 * 与门面的分工：这里只认字节与 HTTP，不碰注册表与状态流；落库、状态发布、
 * 「SKILL.md 已导入」判断都在 [SkillRepository] 里完成。
 */

/** zip 内一个条目的快照（名字、是否目录、内容字节）。 */
internal class ArchiveEntry(val name: String, val isDirectory: Boolean, val bytes: ByteArray)

internal object SkillArchives {

    /**
     * 把整包 zip 读进内存。技能包是小体量资产（文档 + 脚本），整读最简单
     * 也最不容易在流式处理里丢条目；坏包直接抛给调用方按导入失败处理。
     */
    fun drain(input: InputStream): List<ArchiveEntry> {
        val entries = ArrayList<ArchiveEntry>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val payload = if (entry.isDirectory) {
                    ByteArray(0)
                } else {
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read <= 0) break
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
                entries.add(ArchiveEntry(entry.name, entry.isDirectory, payload))
                zip.closeEntry()
            }
        }
        return entries
    }

    /**
     * 定位包内的 SKILL.md：根目录，或恰好一层子目录（GitHub 的「Download ZIP」
     * 会多包一层 `<repo>-main/`）。其他深度不认 —— 无法确定哪个才是技能主体。
     */
    fun locateSkillMd(entries: List<ArchiveEntry>): ArchiveEntry? =
        entries.firstOrNull { entry ->
            entry.name == SkillMarkdown.FILE_NAME ||
                (entry.name.endsWith("/${SkillMarkdown.FILE_NAME}") && entry.name.count { it == '/' } == 1)
        }

    /** 把 [relPaths]（相对技能根）打进 zip 写到 [dest]。返回是否成功产出非空包。 */
    fun pack(sources: Map<String, File>, dest: File): Boolean {
        return try {
            ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                for ((relative, src) in sources) {
                    if (!src.isFile) continue
                    // 统一用 `/` 作条目分隔符：解包工具与导入端都按这个形状读。
                    zip.putNextEntry(ZipEntry(relative.replace(File.separatorChar, '/')))
                    src.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            dest.isFile && dest.length() > 0L
        } catch (e: Exception) {
            false
        }
    }
}

/** GitHub 技能地址的两种形态 → 统一的 (user, repo, branch, dir) 定位。 */
internal object GitHubLocator {

    data class Target(val user: String, val repo: String, val branch: String, val dirPath: String)

    /** 非 GitHub / raw.githubusercontent 域，或段数不足 → null。 */
    fun parse(rawUrl: String): Target? {
        val uri = split(normalize(rawUrl)) ?: return null
        return when (uri.first) {
            "raw.githubusercontent.com" -> {
                if (uri.second.size < 3) return null
                target(user = uri.second[0], repo = uri.second[1], branch = uri.second[2], rest = uri.second.drop(3))
            }
            "github.com" -> {
                if (uri.second.size < 4) return null
                target(user = uri.second[0], repo = uri.second[1], branch = uri.second[3], rest = uri.second.drop(4))
            }
            else -> null
        }
    }

    private fun target(user: String, repo: String, branch: String, rest: List<String>): Target =
        Target(user, repo, branch, rest.dropLastWhile { it.equals(SkillMarkdown.FILE_NAME, true) }.joinToString("/"))

    /** 任意 GitHub 技能地址 → 直链 SKILL.md 的 raw URL；解析不了 → null。 */
    fun rawSkillMdUrl(rawUrl: String): String? {
        val normalized = normalize(rawUrl)
        val uri = split(normalized) ?: return null
        val segments = uri.second
        return when (uri.first) {
            "raw.githubusercontent.com" -> {
                if (segments.lastOrNull()?.equals(SkillMarkdown.FILE_NAME, true) == true) {
                    normalized
                } else {
                    normalized.trimEnd('/') + "/${SkillMarkdown.FILE_NAME}"
                }
            }
            "github.com" -> {
                if (segments.size < 4) return null
                val dir = segments.drop(4).joinToString("/")
                val path = if (dir.endsWith(SkillMarkdown.FILE_NAME, true)) {
                    dir
                } else if (dir.isEmpty()) {
                    SkillMarkdown.FILE_NAME
                } else {
                    "$dir/${SkillMarkdown.FILE_NAME}"
                }
                "https://raw.githubusercontent.com/${segments[0]}/${segments[1]}/${segments[3]}/$path"
            }
            else -> null
        }
    }

    /** 补协议、去首尾空白。 */
    fun normalize(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /** → (host, 非空 path 段)；Uri 解析失败 → null。 */
    private fun split(normalized: String): Pair<String, List<String>>? {
        val uri = try {
            Uri.parse(normalized)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        return host to uri.pathSegments.filter { it.isNotEmpty() }
    }
}

/**
 * GitHub 兄弟文件同步客户端。
 *
 * 语义要点（与既有行为等价，重试纪律保留）：
 *  - 目录遍历走 contents API，深度封顶 5，防仓库环形/超深目录拖死导入；
 *  - 每个请求最多试 2 次：HTTP 403/429/5xx 与 IO 异常视为瞬态，隔 1.5s 重试；
 *    403 大概率是匿名限流，文案里点名提示等待或登录；
 *  - 结果聚合计数 + 首个失败原因（UI 只需要一条能看懂的解释，不是全量错误流）；
 *  - 内容未变的文件不重写（保 mtime，便于用户侧 diff）。
 */
internal class SkillSyncClient {

    private val logTag = "NovexSkillSync"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 一次目录树遍历的聚合结果。 */
    class WalkOutcome(val filesWritten: Int, val filesFailed: Int, val firstReason: String?) {
        val complete: Boolean get() = filesFailed == 0 && firstReason == null
    }

    private class Tally {
        var written = 0
        var failed = 0
        var reason: String? = null
        fun fail(why: String) {
            failed += 1
            if (reason == null) reason = why
        }
        fun note(why: String) {
            if (reason == null) reason = why
        }
    }

    /** 遍历 [target] 指向的远端目录，把文件写进 [destDir]。 */
    suspend fun mirrorDirectory(target: GitHubLocator.Target, destDir: File, skipRootSkillMd: Boolean): WalkOutcome {
        Log.i(logTag, "walk begin: ${target.user}/${target.repo}@${target.branch} dir='${target.dirPath}' -> $destDir")
        val tally = Tally()
        walkRemote(target, destDir, relativePrefix = "", depth = 0, tally, skipRootSkillMd)
        val outcome = WalkOutcome(tally.written, tally.failed, tally.reason)
        Log.i(
            logTag,
            "walk done: written=${outcome.filesWritten} failed=${outcome.filesFailed} reason=${outcome.firstReason ?: "none"}",
        )
        return outcome
    }

    private suspend fun walkRemote(
        target: GitHubLocator.Target,
        destDir: File,
        relativePrefix: String,
        depth: Int,
        tally: Tally,
        skipRootSkillMd: Boolean,
    ) {
        if (depth > MAX_DEPTH) {
            val why = "recursion deeper than $MAX_DEPTH at '${target.dirPath}' — bailing out"
            Log.w(logTag, why)
            tally.note(why)
            return
        }
        val listing = fetchContentsListing(contentsApiUrl(target), tally) ?: return
        val items = try {
            org.json.JSONArray(listing)
        } catch (e: Exception) {
            // 少数错误路径 200 也返回 JSON object；留一行首snippet便于排查。
            val head = listing.lineSequence().firstOrNull()?.take(160) ?: "(no body)"
            val why = "contents API returned non-array JSON at '${target.dirPath}': $head"
            Log.w(logTag, "$why (${e.javaClass.simpleName}: ${e.message})")
            tally.note(why)
            return
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val relative = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
            when (item.optString("type")) {
                "dir" -> walkRemote(
                    target = target.copy(dirPath = joinRemote(target.dirPath, name)),
                    destDir = destDir,
                    relativePrefix = relative,
                    depth = depth + 1,
                    tally = tally,
                    skipRootSkillMd = skipRootSkillMd,
                )
                "file" -> {
                    if (skipRootSkillMd && relativePrefix.isEmpty() && name.equals(SkillMarkdown.FILE_NAME, true)) {
                        continue // SKILL.md 主文件已由导入路径写好。
                    }
                    val downloadUrl = item.optString("download_url")
                    if (downloadUrl.isEmpty()) {
                        Log.w(logTag, "no download_url for '$relative', skip")
                        tally.fail("download_url missing for $relative")
                        continue
                    }
                    val payload = fetchBlob(downloadUrl)
                    if (payload == null) {
                        Log.w(logTag, "blob fetch failed: $relative")
                        tally.fail("could not download $relative")
                        continue
                    }
                    writeFileIfChanged(destDir, relative, payload, tally)
                }
            }
        }
    }

    private fun writeFileIfChanged(destDir: File, relative: String, payload: ByteArray, tally: Tally) {
        val dest = File(destDir, relative)
        dest.parentFile?.mkdirs()
        val unchanged = dest.exists() && dest.readBytes().contentEquals(payload)
        if (unchanged) {
            Log.i(logTag, "kept '$relative' (${payload.size}B, unchanged)")
        } else {
            dest.writeBytes(payload)
            Log.i(logTag, "wrote '$relative' (${payload.size}B)")
        }
        tally.written += 1
    }

    private fun joinRemote(base: String, child: String) = if (base.isEmpty()) child else "$base/$child"

    private fun contentsApiUrl(target: GitHubLocator.Target): String {
        val encoded = java.net.URLEncoder.encode(target.dirPath, "UTF-8")
            .replace("+", "%20")
            .replace("%2F", "/")
        return "https://api.github.com/repos/${target.user}/${target.repo}/contents/$encoded?ref=${target.branch}"
    }

    /** 普通文本 GET：非 2xx 或网络异常 → null（调用方决定失败文案）。 */
    suspend fun fetchText(url: String): String? = fetch(url) { body -> body }

    /**
     * 同 [fetchText]，但网络/IO 异常原样抛出 —— 供需要区分「网络错误」与
     * 「HTTP 错误」两条失败文案的调用方（检查更新）使用。
     */
    fun fetchTextStrict(url: String): String? {
        val call = http.newCall(Request.Builder().url(url).get().build())
        return call.execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    private inline fun <T> fetch(url: String, decode: (String) -> T): T? {
        return try {
            val call = http.newCall(Request.Builder().url(url).get().build())
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string() ?: return null
                decode(text)
            }
        } catch (e: Exception) {
            Log.w(logTag, "GET failed for $url: ${e.message}")
            null
        }
    }

    /**
     * contents API 列目录，含一次瞬态重试。非瞬态失败立即放弃并记录原因。
     */
    private suspend fun fetchContentsListing(apiUrl: String, tally: Tally): String? {
        var lastWhy: String? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { return it }
                        lastWhy = "contents API gave an empty body"
                    } else {
                        val transient = response.code == 403 || response.code == 429 || response.code in 500..599
                        val hint = if (response.code == 403) {
                            " (anonymous rate limit? wait an hour or sign in)"
                        } else {
                            ""
                        }
                        lastWhy = "contents API HTTP ${response.code}$hint on $apiUrl"
                        if (!transient) {
                            Log.w(logTag, "non-retryable: $lastWhy")
                            tally.note(lastWhy!!)
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastWhy = "contents API ${e.javaClass.simpleName}: ${e.message ?: "unknown"} on $apiUrl"
            }
            if (attempt == 0) {
                Log.w(logTag, "transient failure, retrying: $lastWhy")
                runCatching { delay(RETRY_DELAY_MS) }
            }
        }
        Log.w(logTag, "all attempts exhausted: $lastWhy")
        tally.note(lastWhy ?: "contents API unreachable")
        return null
    }

    /** raw blob 下载，同样的瞬态重试纪律；失败只记日志（原因由调用方汇总）。 */
    private suspend fun fetchBlob(url: String): ByteArray? {
        repeat(ATTEMPTS) { attempt ->
            try {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (response.isSuccessful) return response.body?.bytes()
                    val transient = response.code == 403 || response.code == 429 || response.code in 500..599
                    if (!transient) {
                        Log.w(logTag, "blob HTTP ${response.code} non-retryable: $url")
                        return null
                    }
                    Log.w(logTag, "blob HTTP ${response.code} on $url (try ${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w(logTag, "blob ${e.javaClass.simpleName}: ${e.message} on $url (try ${attempt + 1})")
            }
            if (attempt == 0) runCatching { delay(RETRY_DELAY_MS) }
        }
        return null
    }

    private companion object {
        const val MAX_DEPTH = 5
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1500L
    }
}
