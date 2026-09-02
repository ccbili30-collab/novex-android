package com.openminis.app.data.character

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

enum class NovexCardKind(
    val packageType: String,
    val entryName: String,
    val extension: String,
) {
    WORLD("novex.world.package", "world.json", "novexworld"),
    CHARACTER("novex.character.package", "character.json", "novexcharacter"),
}

data class NovexCardMedia(
    val path: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sha256: String = bytes.sha256(),
)

data class NovexCardPackagePreview(
    val kind: NovexCardKind,
    val packageId: String,
    val displayName: String,
    val documentJson: String,
    val media: List<NovexCardMedia>,
    /** Manifest extensions survive an import/export cycle even when Novex does not know them yet. */
    val manifestJson: String = "{}",
)

/**
 * Native Novex card container boundary.
 *
 * It validates a complete archive before returning a preview and never writes files or the
 * database. Domain import receives only a successfully validated preview.
 */
object NovexCardPackageCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENTRY_COUNT = 128
    private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L

    fun decode(bytes: ByteArray): NovexCardPackagePreview {
        require(bytes.isNotEmpty()) { "卡包内容为空" }
        val entries = readEntries(bytes)
        val manifestBytes = requireNotNull(entries["manifest.json"]) { "卡包缺少 manifest.json" }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.optInt("schemaVersion") == SCHEMA_VERSION) { "不支持的卡包版本" }
        val kind = NovexCardKind.entries.firstOrNull {
            it.packageType == manifest.optString("packageType")
        } ?: error("不是 Novex 世界卡或角色卡")
        require(manifest.optString("entry") == kind.entryName) { "卡包主文档路径无效" }
        val documentBytes = requireNotNull(entries[kind.entryName]) { "卡包缺少 ${kind.entryName}" }
        JSONObject(documentBytes.toString(Charsets.UTF_8))

        val declaredMedia = manifest.optJSONArray("media").objects().map { item ->
            val path = requireSafePath(item.getString("path"))
            require(path != "manifest.json" && path != kind.entryName) { "媒体路径与卡包主文件冲突" }
            val mimeType = item.optString("mimeType")
            requireSupportedImageMimeType(mimeType)
            val mediaBytes = requireNotNull(entries[path]) { "卡包缺少媒体：$path" }
            require(mediaBytes.size.toLong() == item.optLong("byteLength", -1L)) { "媒体长度校验失败：$path" }
            val expectedHash = item.optString("sha256").lowercase()
            require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "媒体摘要格式无效：$path" }
            require(mediaBytes.sha256() == expectedHash) { "媒体摘要校验失败：$path" }
            requireImageHeader(mediaBytes, mimeType, path)
            NovexCardMedia(path, mimeType, mediaBytes, expectedHash)
        }
        require(declaredMedia.map(NovexCardMedia::path).distinct().size == declaredMedia.size) {
            "卡包媒体路径不能重复"
        }
        val expectedPaths = setOf("manifest.json", kind.entryName) + declaredMedia.map(NovexCardMedia::path)
        require(entries.keys == expectedPaths) { "卡包包含未声明文件" }

        val packageId = manifest.optString("packageId").trim()
        val displayName = manifest.optString("displayName").trim()
        require(packageId.isNotBlank()) { "卡包编号不能为空" }
        require(displayName.isNotBlank()) { "卡包名称不能为空" }
        return NovexCardPackagePreview(
            kind = kind,
            packageId = packageId,
            displayName = displayName,
            documentJson = documentBytes.toString(Charsets.UTF_8),
            media = declaredMedia,
            manifestJson = manifest.toString(),
        )
    }

    fun encode(preview: NovexCardPackagePreview): ByteArray {
        require(preview.packageId.isNotBlank()) { "卡包编号不能为空" }
        require(preview.displayName.isNotBlank()) { "卡包名称不能为空" }
        JSONObject(preview.documentJson)
        val normalizedMedia = preview.media.map { media ->
            val path = requireSafePath(media.path)
            requireSupportedImageMimeType(media.mimeType)
            requireImageHeader(media.bytes, media.mimeType, path)
            media.copy(path = path, sha256 = media.bytes.sha256())
        }
        require(normalizedMedia.map(NovexCardMedia::path).distinct().size == normalizedMedia.size) {
            "卡包媒体路径不能重复"
        }
        val manifest = runCatching { JSONObject(preview.manifestJson) }.getOrDefault(JSONObject()).apply {
            put("packageType", preview.kind.packageType)
            put("schemaVersion", SCHEMA_VERSION)
            put("packageId", preview.packageId)
            put("displayName", preview.displayName)
            put("entry", preview.kind.entryName)
            put("media", JSONArray().apply {
                normalizedMedia.forEach { media ->
                    put(
                        JSONObject()
                            .put("path", media.path)
                            .put("mimeType", media.mimeType)
                            .put("byteLength", media.bytes.size)
                            .put("sha256", media.sha256),
                    )
                }
            })
        }
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putStableEntry("manifest.json", manifest.toString(2).toByteArray())
                zip.putStableEntry(preview.kind.entryName, preview.documentJson.toByteArray())
                normalizedMedia.sortedBy(NovexCardMedia::path).forEach { media ->
                    zip.putStableEntry(media.path, media.bytes)
                }
            }
            output.toByteArray()
        }
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    requireSafePath(entry.name.removeSuffix("/"))
                    zip.closeEntry()
                    continue
                }
                val path = requireSafePath(entry.name)
                require(path !in entries) { "卡包文件路径不能重复：$path" }
                require(entries.size < MAX_ENTRY_COUNT) { "卡包文件数量过多" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_TOTAL_BYTES) { "卡包解压后过大" }
                    output.write(buffer, 0, count)
                }
                entries[path] = output.toByteArray()
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "不是有效的 ZIP 压缩归档" }
        return entries
    }

    private fun requireSafePath(raw: String): String {
        val path = raw.trim()
        require(path.isNotBlank()) { "卡包文件路径不能为空" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "卡包文件路径不能是绝对路径" }
        require('\\' !in path) { "卡包文件路径必须使用正斜杠" }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "卡包文件路径不安全" }
        return path
    }

    private fun requireSupportedImageMimeType(mimeType: String) {
        require(mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
            "卡包只允许常见图片媒体"
        }
    }

    private fun requireImageHeader(bytes: ByteArray, mimeType: String, path: String) {
        val valid = when (mimeType) {
            "image/png" -> bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "image/jpeg" -> bytes.startsWith(0xff, 0xd8, 0xff)
            "image/gif" -> bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a")
            "image/webp" -> bytes.startsWithAscii("RIFF") && bytes.drop(8).toByteArray().startsWithAscii("WEBP")
            else -> false
        }
        require(valid) { "媒体文件头与类型不符：$path" }
    }

    private fun ZipOutputStream.putStableEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(bytes)
        closeEntry()
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xff == prefix[index] }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        size >= prefix.length && copyOfRange(0, prefix.length).toString(Charsets.US_ASCII) == prefix

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        val array = this@objects ?: return@buildList
        repeat(array.length()) { index -> array.optJSONObject(index)?.let(::add) }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
