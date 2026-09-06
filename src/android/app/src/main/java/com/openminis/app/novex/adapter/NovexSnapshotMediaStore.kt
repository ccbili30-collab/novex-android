package com.openminis.app.novex.adapter

import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.novex.domain.NovexRetainedMedia
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A private retained copy owned by adopted settings, independent of the original card's media row. */
class NovexSnapshotMediaStore(private val root: File) {
    @Synchronized
    fun retain(asset: MediaAssetEntity): NovexRetainedMedia {
        require(asset.contentHash.matches(Regex("[0-9a-f]{64}"))) { "图片缺少有效的内容校验值" }
        require(asset.mimeType.startsWith("image/")) { "采用的媒体必须是图片" }
        require(root.isDirectory || root.mkdirs()) { "无法建立采用图片的保存目录" }
        val destination = File(root, "${asset.contentHash}.media")
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!destination.isFile || destination.length() > MAX_BYTES || hash(destination.readBytes()) != asset.contentHash) {
            val original = File(asset.managedPath)
            require(original.isFile && original.length() in 1..MAX_BYTES) { "来源图片不存在或超过保存上限" }
            val bytes = original.readBytes()
            require(hash(bytes) == asset.contentHash) { "来源图片已经改变，不能当作原修订采用" }
            val pending = File.createTempFile("adoption-", ".pending", root)
            try {
                FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
                try {
                    Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { pending.delete() }
        }
        return NovexRetainedMedia(asset.id, destination.absolutePath, asset.mimeType, asset.contentHash, asset.managedPath)
    }

    private companion object { const val MAX_BYTES = 64L * 1024 * 1024 }
}
