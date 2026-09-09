package com.openminis.app.novex.adapter

import com.openminis.app.data.character.NovexCardPackagePreview
import com.openminis.app.novex.domain.canonicalRevisionJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Writes a complete immutable card directory before its pointer can join the caller's DB transaction.
 * A rollback leaves an unreferenced directory, never a partly updated current card. Readers resolve
 * only the committed pointer; no independently writable mirror or filesystem 'current' symlink exists.
 */
internal class NovexCardDirectoryStore(
    root: File,
    private val syncDirectory: (File) -> Unit = ::syncAndroidDirectory,
    private val beforeWrite: (String) -> Unit = {},
) {
    private val root = root.canonicalFile
    data class Revision(val ownerKey: String, val directory: String, val digest: String)

    private data class Input(val bytes: ByteArray? = null, val file: File? = null) {
        fun open(): java.io.InputStream = bytes?.inputStream() ?: requireNotNull(file).inputStream()
        val length: Long get() = bytes?.size?.toLong() ?: requireNotNull(file).length()
    }

    fun prepare(ownerKey: String, card: NovexCardPackagePreview, rawSnapshot: String,
        sources: Map<String, File> = emptyMap(), previous: Revision? = null): Revision {
        require(ownerKey.isNotBlank()) { "卡片归属不能为空" }
        val document = JSONObject(card.documentJson)
        val files = linkedMapOf<String, Input>()
        card.media.forEach { media ->
            require(media.path.startsWith("media/")) { "卡片图片须属于本卡图片目录" }
            require(!files.containsKey(media.path)) { "卡片图片路径重复" }
            val source = sources[media.path]
            if (source != null) require(source.isFile && digest(source.inputStream()) == media.sha256) { "来源图片缺失或已改变" }
            files[media.path] = if (source == null) Input(bytes = media.bytes) else Input(file = source)
        }
        // Original imported text belongs to this card, never a separate surprise library item.
        com.openminis.app.novex.domain.NovexExternalCardImport.original(document)?.let { (_, bytes) ->
            files["source/original"] = Input(bytes = bytes)
        }
        // Native payload remains directly readable; the raw snapshot retains every local field and ID.
        files["card.json"] = Input(bytes = canonicalRevisionJson(document).toByteArray(Charsets.UTF_8))
        files["local.json"] = Input(bytes = canonicalRevisionJson(JSONObject(rawSnapshot)).toByteArray(Charsets.UTF_8))
        val manifest = JSONObject().put("version", 1).put("owner", ownerKey)
            .put("kind", card.kind.name).put("name", card.displayName).put("packageId", card.packageId)
            .put("files", JSONArray(files.map { (path, input) -> JSONObject().put("path", path)
                .put("length", input.length).put("sha256", digest(input.open())) }))
        val manifestBytes = canonicalRevisionJson(manifest).toByteArray(Charsets.UTF_8)
        val revisionHash = digest(manifestBytes)
        val owner = File(root, digest(ownerKey.toByteArray(Charsets.UTF_8)))
        val generation = File(owner, UUID.randomUUID().toString())
        check(generation.mkdirs()) { "无法准备卡片目录，请检查存储空间" }
        val prior = previous?.takeIf { it.ownerKey == ownerKey }?.let { runCatching { verify(it) }.getOrNull() }
        val priorHashes = prior?.let { JSONObject(File(it, "manifest.json").readText()).getJSONArray("files") }?.let { values ->
            (0 until values.length()).associate { values.getJSONObject(it).let { entry -> entry.getString("path") to entry.getString("sha256") } }
        }.orEmpty()
        val hashes = manifest.getJSONArray("files").let { values -> (0 until values.length()).associate { values.getJSONObject(it).let { entry -> entry.getString("path") to entry.getString("sha256") } } }
        files["manifest.json"] = Input(bytes = manifestBytes)
        try {
            files.forEach { (path, input) ->
                val target = safeFile(generation, path)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法准备图片目录" }
                beforeWrite(path)
                // Reuse only immutable images from this same card. Cross-card copies remain independent.
                val linked = path.startsWith("media/") && prior != null && priorHashes[path] == hashes[path] &&
                    runCatching { java.nio.file.Files.createLink(target.toPath(), safeFile(prior, path).toPath()) }.isSuccess
                if (!linked) FileOutputStream(target).use { stream -> input.open().use { it.copyTo(stream, 64 * 1024) }; stream.fd.sync() }
            }
            generation.walkBottomUp().filter(File::isDirectory).forEach(syncDirectory)
            syncDirectory(owner); syncDirectory(root); root.parentFile?.let(syncDirectory)
            val revision = Revision(ownerKey, generation.relativeTo(root).invariantSeparatorsPath, revisionHash)
            verify(revision)
            return revision
        } catch (failure: Throwable) {
            generation.deleteRecursively()
            throw failure
        }
    }

    /** Missing/corrupt committed content is an error, never an empty success. */
    fun verify(revision: Revision): File {
        val directory = safeFile(root, revision.directory)
        val manifestBytes = File(directory, "manifest.json").readBytes()
        require(digest(manifestBytes) == revision.digest) { "卡片目录校验失败" }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.getString("owner") == revision.ownerKey) { "卡片目录归属不一致" }
        val files = manifest.getJSONArray("files")
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val target = safeFile(directory, file.getString("path"))
            require(target.length() == file.getLong("length") && digest(target.inputStream()) == file.getString("sha256")) { "卡片正文或图片不完整" }
        }
        return directory
    }

    /** Called under the database transaction lock, using every committed pointer. */
    fun reclaimUnreferenced(keep: Set<String>, olderThan: Long) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) }.forEach { owner ->
            owner.listFiles().orEmpty().filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }.forEach { generation ->
                val relative = generation.relativeTo(root).invariantSeparatorsPath
                if (relative !in keep && generation.lastModified() < olderThan) generation.deleteRecursively()
            }
            if (owner.listFiles().isNullOrEmpty()) owner.delete()
        }
    }

    private fun safeFile(parent: File, path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "卡片文件路径无效" }
        return File(parent, path).canonicalFile.also {
            require(it.path.startsWith(parent.canonicalPath + File.separator)) { "卡片文件不能超出所属目录" }
        }
    }

    private fun digest(stream: java.io.InputStream): String = stream.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { value -> "%02x".format(value) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun syncAndroidDirectory(directory: File) {
    require(directory.isDirectory) { "卡片保存目录不存在" }
    val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
    try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
}
