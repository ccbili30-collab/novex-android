package com.openminis.app.novex.adapter

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A local candidate is fully downloaded and validated before the existing AttachImage command. */
internal class NovexCardImageSource(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(40, TimeUnit.SECONDS).build()) {
    data class Image(val bytes: ByteArray, val mimeType: String, val source: String)

    suspend fun fromUri(context: Context, uri: Uri): Image = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择设备中的图片" }
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded) ?: error("无法读取这张图片")
        validate(bytes, "设备图片")
    }

    suspend fun fromFile(file: File): Image = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..MAX_BYTES) { "图片不存在或超过 32 MB（兆字节）" }
        validate(file.inputStream().use(::readBounded), "创作库")
    }

    suspend fun fromLink(raw: String): Image = withContext(Dispatchers.IO) {
        val url = requireNotNull(raw.trim().toHttpUrlOrNull()) { "请输入图片的完整直链" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "图片链接不能包含账户口令" }
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            require(response.isSuccessful) { "图片下载失败，请检查链接或稍后重试" }
            val body = requireNotNull(response.body) { "图片下载内容为空" }
            require(body.contentLength() <= MAX_BYTES) { "图片超过 32 MB（兆字节）" }
            validate(body.byteStream().use(::readBounded), url.toString())
        }
    }

    internal fun validate(bytes: ByteArray, source: String): Image {
        require(bytes.size.toLong() in 1..MAX_BYTES) { "图片为空或超过 32 MB（兆字节）" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth.toLong() * options.outHeight <= 40_000_000L) { "这不是可用图片，或图片像素过大；分享网页请改用图片直链" }
        val mime = options.outMimeType
        require(mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "请选择常见格式的图片" }
        return Image(bytes, requireNotNull(mime), source)
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "图片超过 32 MB（兆字节）" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object { private const val MAX_BYTES = 32L * 1024 * 1024 }
}
