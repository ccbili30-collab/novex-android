package com.openminis.app.ui.sandbox

import com.openminis.app.R
import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import com.openminis.app.logging.AppLogger
import java.io.File

// 预览页的进程外动作：分享 / 外跳 / 打印 / 存相册 / 画廊收集。

/**
 * T142: 经系统分享面板分享任意文件（对齐 iOS UIActivityViewController）。
 * FileProvider URI 让接收方读得到字节，FLAG_GRANT_READ_URI_PERMISSION
 * 保证授权跟随 chooser 选中项（chooser 本身是独立 activity）。
 */
internal fun sharePreviewFile(context: Context, item: FileItem) {
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(item.file.extension.lowercase())
                ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.file_share_chooser_title)))
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "share failed for ${item.name}: ${e.message}")
        Toast.makeText(
            context,
            context.getString(R.string.file_share_failed_toast, e.message ?: ""),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/** FileProvider + ACTION_VIEW 外跳，预览页 PDF/Office 兜底共用。 */
internal fun openPreviewExternally(context: Context, item: FileItem, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with…"))
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "openExternally failed: ${e.message}")
        Toast.makeText(context, "No app available to open this file.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 经 Android 打印框架打印可预览文件：HTML 直接 loadUrl，文本族
 * （markdown/纯文本/json/csv）包进 <pre> 块，让单条
 * WebView.createPrintDocumentAdapter 路径覆盖全部。对齐 iOS 所有预览面
 * 汇到同一个打印控制器的做法。
 *
 * 离屏 WebView 必须活得比这个函数久：打印是异步的（onPageFinished 后才
 * 派发），用捕获的 holder 持有引用，adapter 交给 PrintManager 后清掉。
 */
internal fun printPreviewFile(context: Context, item: FileItem) {
    try {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
        }
        var holder: WebView? = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val jobName = "${context.getString(R.string.app_name)} - ${item.name}"
                (context.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(
                    jobName,
                    view.createPrintDocumentAdapter(jobName),
                    PrintAttributes.Builder().build(),
                )
                holder = null
            }
        }
        if (item.isHtmlFile) {
            webView.loadUrl("file://${item.file.absolutePath}")
        } else {
            val raw = item.file.readBytes().let { bytes ->
                val cap = minOf(bytes.size, PREVIEW_TEXT_CAP_BYTES)
                String(bytes, 0, cap, Charsets.UTF_8)
            }
            val html = "<html><head><meta charset=\"utf-8\">" +
                "<style>body{font-family:monospace;font-size:12px;white-space:pre-wrap;word-wrap:break-word;}</style>" +
                "</head><body><pre>${escapeHtmlForPrint(raw)}</pre></body></html>"
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        // holder 让 webView 在异步页面加载期间保持可达；同时压住
        // unused-assignment 告警。
        @Suppress("UNUSED_VALUE")
        holder = webView
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "print failed for ${item.name}: ${e.message}")
        Toast.makeText(
            context,
            context.getString(R.string.file_print_failed_toast, e.message ?: ""),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun escapeHtmlForPrint(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * 为被点的图片构建滑动画廊的 (items, startIndex)：枚举同目录所有图片
 * 兄弟（按名排序、忽略大小写），定位被点文件下标，包成
 * [com.openminis.app.ui.components.ImageGalleryItem]。父目录列不出来时
 * 回落成单元素列表。
 */
internal fun collectImageGallery(
    file: File,
): Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int> {
    val siblings = file.parentFile?.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in IMAGE_GALLERY_EXTENSIONS }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    val list = siblings.ifEmpty { listOf(file) }
    val startIdx = list.indexOfFirst { it.absolutePath == file.absolutePath }
        .coerceAtLeast(0)
    return list.map {
        com.openminis.app.ui.components.ImageGalleryItem(model = it, caption = it.name)
    } to startIdx
}

private val IMAGE_GALLERY_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
