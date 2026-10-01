package com.openminis.app.ui

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import novex.android.ContentPaths
import okio.buffer
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder

/**
 * Coil 装载器：把 `minis://` URI 解析到沙箱宿主文件。
 *
 * `minis://attachments/foo.jpg` → `/var/minis/attachments/foo.jpg` → 宿主路径。
 *
 * 注册两个工厂：String 版是兜底（Coil 的 StringMapper 通常先把字符串转成
 * Uri），Uri 版是 Markdown 图片实际命中的路径。两个 Keyer 把文件 mtime
 * 编入缓存键，原地覆盖写入（如同名附件重新生成）后能顶掉旧位图。
 */
class MinisImageFetcher private constructor(
    private val uri: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val hostFile = resolveMinisFile(uri)
            ?: throw IllegalArgumentException("Cannot resolve path: $uri")
        if (!hostFile.exists()) {
            throw FileNotFoundException("File not found: ${hostFile.absolutePath}")
        }
        return SourceResult(
            source = ImageSource(source = hostFile.source().buffer(), context = options.context),
            mimeType = mimeTypeFor(hostFile),
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.takeIf { it.startsWith(MINIS_PREFIX) }?.let { MinisImageFetcher(it, options) }
    }

    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.takeIf { it.scheme == MINIS_SCHEME }?.let { MinisImageFetcher(it.toString(), options) }
    }

    class MtimeKeyer : Keyer<Uri> {
        override fun key(data: Uri, options: Options): String? =
            if (data.scheme == MINIS_SCHEME) mtimeKey(data.toString()) else null
    }

    class StringMtimeKeyer : Keyer<String> {
        override fun key(data: String, options: Options): String? =
            if (data.startsWith(MINIS_PREFIX)) mtimeKey(data) else null
    }

    companion object {
        private const val MINIS_SCHEME = "minis"
        private const val MINIS_PREFIX = "minis://"
        private const val SANDBOX_ROOT = "/var/minis/"

        /** `minis://` URI → 宿主文件；decode 掉 query 和百分号编码，解析失败返回 null。 */
        private fun resolveMinisFile(uri: String): File? {
            val stripped = uri.removePrefix(MINIS_PREFIX).substringBefore('?')
            val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
            return ContentPaths.resolveHostPath("$SANDBOX_ROOT$decoded")
        }

        private fun mimeTypeFor(file: File): String? = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> null
        }

        /** 缓存键带 mtime：同名文件原地重写后 Coil 不会继续吐旧位图。 */
        private fun mtimeKey(uri: String): String {
            val mtime = runCatching { resolveMinisFile(uri)?.lastModified() ?: 0L }.getOrDefault(0L)
            return "$uri?mt=$mtime"
        }
    }
}
