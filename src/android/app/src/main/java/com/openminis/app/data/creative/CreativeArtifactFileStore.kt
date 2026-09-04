package com.openminis.app.data.creative

import java.io.File
import java.security.MessageDigest

data class StoredCreativeArtifactBlob(
    val storageKey: String,
    val contentHash: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** A content-addressed byte store; metadata, versions and references remain in Room. */
class CreativeArtifactFileStore(
    private val root: File,
) {
    fun put(bytes: ByteArray, mimeType: String): StoredCreativeArtifactBlob {
        require(bytes.isNotEmpty()) { "创作成果内容不能为空" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val existing = root.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$hash.") }
        val file = existing ?: File(root, "$hash.${extensionFor(mimeType)}").also { target ->
            root.mkdirs()
            val temporary = File(root, ".$hash.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                error("无法保存创作成果")
            }
        }
        return StoredCreativeArtifactBlob(
            storageKey = file.name,
            contentHash = hash,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = bytes.size.toLong(),
        )
    }

    fun read(storageKey: String): ByteArray = resolve(storageKey).readBytes()

    fun delete(storageKey: String): Boolean = resolve(storageKey).delete()

    fun file(storageKey: String): File = resolve(storageKey)

    private fun resolve(storageKey: String): File {
        require(storageKey.isNotBlank()) { "创作成果存储编号不能为空" }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, storageKey).canonicalFile
        require(target.parentFile == canonicalRoot) { "创作成果路径越界" }
        return target
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "text/markdown" -> "md"
        "text/plain" -> "txt"
        "text/html" -> "html"
        "application/pdf" -> "pdf"
        "application/json" -> "json"
        "application/zip" -> "zip"
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        else -> "bin"
    }
}
