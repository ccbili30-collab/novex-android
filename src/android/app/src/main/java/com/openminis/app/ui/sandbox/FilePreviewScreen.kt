package com.openminis.app.ui.sandbox

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import novex.android.ui.ListItem
import androidx.compose.material3.MaterialTheme
import novex.android.ui.Scaffold
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.ui.NovexIcons

internal const val PREVIEW_TEXT_CAP_BYTES = 512_000 // 500 KB

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    item: FileItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // T-android-preview-title-toggle-path: 点标题在文件名 ↔ 绝对路径之间
    // 切换，对齐 iOS 预览。长路径走 Ellipsis（TopAppBar 标题单行）。
    var showFullPath by remember(item.file) { mutableStateOf(false) }

    // T144: 非图片文件走 SAF Save-As（图片走 T142 MediaStore 入相册）。
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeTypeFor(item)),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    item.file.inputStream().use { it.copyTo(out) }
                }
                true
            } catch (e: Exception) {
                com.openminis.app.logging.AppLogger.warning(
                    "FilePreview", "Save-As failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(if (ok) R.string.file_saved_toast else R.string.file_save_failed_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // T-imgswipe-4f446d83: 图片走同目录滑动画廊；画廊自带 关闭/保存/
    // 分享/复制 chrome，所以这里完全跳过 Scaffold+TopAppBar。
    if (item.isImageFile) {
        val gallery = remember(item.file.absolutePath) { collectImageGallery(item.file) }
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = gallery.first,
            startIndex = gallery.second,
            onDismiss = onBack,
        )
        return
    }

    // T279: 跟 FileBrowserScreen 一样用裸 Scaffold+TopAppBar——之前各种
    // windowInsets/statusBar 手调都在 edge-to-edge 下留灰条，裸用法是
    // 唯一和 Activity 配置不打架的方案。
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (showFullPath) item.file.absolutePath else item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { showFullPath = !showFullPath },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // T142: 任意文件可分享——FileProvider URI + ACTION_SEND +
                    // FLAG_GRANT_READ_URI_PERMISSION，iOS parity。
                    IconButton(onClick = { sharePreviewFile(context, item) }) {
                        Icon(NovexIcons.Share, contentDescription = stringResource(R.string.filepreview_share))
                    }
                    // 可打印面：HTML 经 WebView 直接渲染，markdown/文本/json/
                    // csv 包进 <pre> 复用同一条 print adapter 路径。
                    if (item.isHtmlFile || item.isMarkdownFile || item.isTextFile ||
                        item.isJsonFile || item.isCsvFile
                    ) {
                        IconButton(onClick = { printPreviewFile(context, item) }) {
                            Icon(NovexIcons.Print, contentDescription = stringResource(R.string.action_print))
                        }
                    }
                    // 图片文件已在上面早退到画廊，这里一定是非图片 → SAF Save-As。
                    IconButton(onClick = { saveAsLauncher.launch(item.name) }) {
                        Icon(NovexIcons.Download, contentDescription = stringResource(R.string.filepreview_save_as))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PreviewBody(item)
        }
    }
}

/** 按文件类型分派预览体。顺序敏感：markdown/html 先于通用文本判定。 */
@Composable
internal fun PreviewBody(item: FileItem) {
    when {
        item.isMarkdownFile -> MarkdownFileBody(item)
        item.isHtmlFile -> HtmlFileBody(item)
        item.isAudioFile || item.isVideoFile -> ExternalOpenMediaBody(item)
        item.isPdfFile -> PdfFileBody(item)
        item.isCsvFile -> CsvFileBody(item)
        item.isJsonFile -> JsonFileBody(item)
        item.isArchiveFile -> ArchiveFileBody(item)
        item.isOfficeFile -> OfficeFileBody(item)
        item.isTextFile -> PlainTextFileBody(item)
        else -> UnknownFileBody(item)
    }
}

internal fun mimeTypeFor(item: FileItem): String =
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(item.file.extension.lowercase())
        ?: "application/octet-stream"

/** 居中的加载/错误状态块——所有预览体共用。 */
@Composable
internal fun PreviewStatusMessage(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LoadingStatus(text: String = stringResource(R.string.filepreview_loading)) {
    PreviewStatusMessage(text)
}

/**
 * T149: 共享元数据块——Name / Size / Type / Modified / Path（+ symlink
 * 目标）。任何不内嵌渲染字节的预览路径都把它垫在下面，避免退化成
 * 「只剩一个按钮」。
 */
@Composable
internal fun FileMetadataBlock(item: FileItem) {
    HorizontalDivider()
    for ((key, value) in remember(item) { buildFileAttributes(item) }) {
        ListItem(
            headlineContent = { Text(value) },
            overlineContent = { Text(key) },
        )
    }
}

private fun buildFileAttributes(item: FileItem): List<Pair<String, String>> = buildList {
    add("Name" to item.name)
    add("Size" to item.formattedSize)
    val ext = item.file.extension
    if (ext.isNotEmpty()) add("Type" to ext.uppercase())
    val modified = item.file.lastModified()
    if (modified > 0) add("Modified" to FileBrowserViewModel.formatDate(modified))
    add("Path" to item.file.absolutePath)
    if (item.isSymlink) {
        try {
            add("Link Target" to java.nio.file.Files.readSymbolicLink(item.file.toPath()).toString())
        } catch (_: Exception) { }
    }
}
