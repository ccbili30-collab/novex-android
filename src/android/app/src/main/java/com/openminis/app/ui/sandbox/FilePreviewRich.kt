package com.openminis.app.ui.sandbox

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import novex.android.ui.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.mediaMimeTypeFor
import com.openminis.app.ui.components.openMediaFileExternally
import com.openminis.app.ui.components.rememberIosBounceOverscrollEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.ui.NovexIcons

// 富媒体/结构化预览：HTML WebView、PDF 原生渲染、压缩包目录、Office/音视频
// 外跳兜底、未知类型信息页。

// ── HTML（WebView）───────────────────────────────────────────────────────────

@Composable
internal fun HtmlFileBody(item: FileItem) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                // T-webview-popup-d3c6e10f: 对齐 ffc85ad 的修复——
                // `height:100vh`+`overflow:hidden` 页面在首帧 0×0 容器下
                // 会被 Blink 折叠成白屏。useWideViewPort+loadWithOverviewMode
                // 解耦 CSS viewport，post{} 延迟 loadUrl 保证布局完成后再解析。
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = WebViewClient()
                post { loadUrl("file://${item.file.absolutePath}") }
            }
        },
    )
}

// ── 音视频：外跳兜底 ─────────────────────────────────────────────────────────
// [P3.3 裁军] 内嵌播放器全家退役；信息卡 + 「用其他应用打开」经
// FileProvider 外跳系统播放器。

@Composable
internal fun ExternalOpenMediaBody(item: FileItem) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${item.formattedSize} · ${mediaMimeTypeFor(item.file)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        novex.android.ui.Button(onClick = { openMediaFileExternally(context, item.file) }) {
            Text(stringResource(R.string.filepreview_open_with_other_apps))
        }
    }
}

// ── PDF（原生 PdfRenderer）───────────────────────────────────────────────────

private const val PDF_PAGE_LIMIT = 50
private const val PDF_RENDER_WIDTH_PX = 1600

/**
 * T144: 内置 PDF 查看器。每页渲成 Bitmap（按页宽比缩放到卡片宽度）进
 * LazyColumn，百页 PDF 不 OOM。上限 50 页——完整文档走 Save-As/外跳。
 */
@Composable
internal fun PdfFileBody(item: FileItem) {
    var pages by remember(item.file) { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                pages = renderPdfPages(item.file)
            } catch (e: Exception) {
                AppLogger.warning("FilePreview", "PdfRenderer failed for ${item.name}: ${e.message}")
                error = e.message ?: "Failed to render PDF"
            }
        }
    }

    when {
        error != null -> PdfExternalFallback(item, error!!)
        pages == null -> PreviewStatusMessage(stringResource(R.string.filepreview_loading_pdf))
        pages!!.isEmpty() -> PdfExternalFallback(item, stringResource(R.string.filepreview_pdf_empty))
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val rendered = pages!!
            items(rendered.size) { idx ->
                Image(
                    bitmap = rendered[idx].asImageBitmap(),
                    contentDescription = stringResource(R.string.filepreview_page_n, idx + 1),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

private fun renderPdfPages(file: java.io.File): List<Bitmap> {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val out = ArrayList<Bitmap>(minOf(renderer.pageCount, PDF_PAGE_LIMIT))
            for (i in 0 until minOf(renderer.pageCount, PDF_PAGE_LIMIT)) {
                renderer.openPage(i).use { page ->
                    val targetH =
                        (PDF_RENDER_WIDTH_PX.toFloat() * page.height / page.width).toInt()
                    val bmp = Bitmap.createBitmap(
                        PDF_RENDER_WIDTH_PX, targetH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bmp)
                }
            }
            return out
        }
    }
}

/** T149: PDF 渲不出来时仍带元数据块的兜底页（与 Office 同一回归修复）。 */
@Composable
private fun PdfExternalFallback(item: FileItem, reason: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                NovexIcons.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.filepreview_pdf_render_failed, reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            MinisTextButton(onClick = {
                openPreviewExternally(context, item, "application/pdf")
            }) {
                Text(stringResource(R.string.filepreview_open_externally))
            }
        }
        FileMetadataBlock(item)
    }
}

// ── 压缩包（ZIP/JAR/APK 目录列表）────────────────────────────────────────────

private const val ARCHIVE_ENTRY_LIMIT = 2000

private class ArchiveEntry(val name: String, val size: Long, val isDir: Boolean)

@Composable
internal fun ArchiveFileBody(item: FileItem) {
    var entries by remember(item.file) { mutableStateOf<List<ArchiveEntry>?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val out = mutableListOf<ArchiveEntry>()
                java.util.zip.ZipFile(item.file).use { zf ->
                    val it = zf.entries()
                    while (it.hasMoreElements() && out.size < ARCHIVE_ENTRY_LIMIT) {
                        val e = it.nextElement()
                        out.add(ArchiveEntry(e.name, e.size, e.isDirectory))
                    }
                }
                entries = out.sortedBy { it.name }
            } catch (e: Exception) {
                error = e.message ?: "Failed to read archive"
            }
        }
    }

    when {
        error != null -> PreviewStatusMessage(error!!, isError = true)
        entries == null -> PreviewStatusMessage(stringResource(R.string.filepreview_loading_archive))
        else -> {
            val list = entries!!
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = "${list.size} entries  •  ${item.formattedSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list.size) { i ->
                        val e = list[i]
                        ListItem(
                            headlineContent = {
                                Text(
                                    e.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                    maxLines = 1,
                                )
                            },
                            supportingContent = if (e.isDir) null else {
                                { Text(android.text.format.Formatter.formatFileSize(null, e.size)) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ── Office（xlsx/docx/pptx 外跳）─────────────────────────────────────────────

@Composable
internal fun OfficeFileBody(item: FileItem) {
    val context = LocalContext.current
    // T149: 上半部 = 预览提示 + 外跳按钮，下半部 = 元数据块（T144 曾退化成
    // 只剩按钮）。T164: 外层 noop scrollable + overscroll 把过界增量喂给
    // iOS 橡皮筋效果（verticalScroll 自己不暴露 overscrollEffect 槽位）。
    val bounce = rememberIosBounceOverscrollEffect()
    val noopState = rememberScrollableState { 0f }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .scrollable(
                state = noopState,
                orientation = Orientation.Vertical,
                overscrollEffect = bounce,
            )
            .overscroll(bounce)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                NovexIcons.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.filepreview_office_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            MinisTextButton(onClick = {
                openPreviewExternally(context, item, mimeTypeFor(item))
            }) {
                Text(stringResource(R.string.filepreview_open_externally))
            }
        }
        FileMetadataBlock(item)
    }
}

// ── 未知类型兜底 ─────────────────────────────────────────────────────────────

@Composable
internal fun UnknownFileBody(item: FileItem) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                NovexIcons.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Preview not available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FileMetadataBlock(item)
    }
}
