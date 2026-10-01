package com.openminis.app.data.storage

import android.content.Context
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import novex.android.data.model.MediaRef

/**
 * 媒体附件落盘（血统清剿 P3.7 就地真重写；`media/yyyy/MM/dd/<sid>/<uuid>.<ext>`
 * 路径布局与扩展名推导为契约冻结面）。
 */
class MediaStore(context: Context) {

    val mediaBaseDir: File = File(context.filesDir, "media")

    fun saveMedia(
        data: ByteArray,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef {
        val (id, relativePath, file) = allocateSlot(mimeType, sessionId, originalFileName)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return refOf(id, relativePath, mimeType, originalFileName)
    }

    /**
     * 流式变体：把 [source] 拷进与 [saveMedia] 相同的日期分桶布局，全程不
     * 把整个文件读进内存。非图片附件（APK、压缩包、大二进制）用它——低内存
     * 设备上 `readBytes()` 对 100MB 以上的东西就是 OOM。
     *
     * IO 失败返回 null；调用方按「跳过这个附件」处理，不打断发送。
     */
    fun saveMediaStreamed(
        source: InputStream,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef? {
        val (id, relativePath, file) = allocateSlot(mimeType, sessionId, originalFileName)
        file.parentFile?.mkdirs()
        return try {
            file.outputStream().use { source.copyTo(it) }
            refOf(id, relativePath, mimeType, originalFileName)
        } catch (t: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    fun loadMedia(ref: MediaRef): ByteArray? {
        val file = File(mediaBaseDir, ref.relativePath)
        return if (file.exists()) file.readBytes() else null
    }

    fun deleteSessionMedia(sessionId: String) {
        mediaBaseDir.walkTopDown().forEach { entry ->
            if (entry.isDirectory && entry.name == sessionId) {
                entry.deleteRecursively()
            }
        }
    }

    // -- 小件 ----------------------------------------------------------------

    /** 新媒体槽位：(uuid, 相对路径, 目标文件)。 */
    private fun allocateSlot(
        mimeType: String,
        sessionId: String,
        originalFileName: String?,
    ): Triple<String, String, File> {
        val id = UUID.randomUUID().toString()
        val dated = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val ext = originalFileName?.let(::safeExtensionOf) ?: extensionFor(mimeType)
        val relativePath = "$dated/$sessionId/$id.$ext"
        return Triple(id, relativePath, File(mediaBaseDir, relativePath))
    }

    private fun refOf(id: String, relativePath: String, mimeType: String, originalFileName: String?) =
        MediaRef(
            id = id,
            relativePath = relativePath,
            mimeType = mimeType,
            originalFileName = originalFileName,
        )

    /** 原文件名后缀：1-10 位字母数字才算数，小写化；否则 null。 */
    private fun safeExtensionOf(originalFileName: String): String? =
        originalFileName
            .substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.lowercase()

    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("png") -> "png"
        mimeType.contains("gif") -> "gif"
        mimeType.contains("webp") -> "webp"
        mimeType.contains("pdf") -> "pdf"
        else -> "bin"
    }
}
