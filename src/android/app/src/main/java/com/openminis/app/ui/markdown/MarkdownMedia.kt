package com.openminis.app.ui.markdown

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import novex.android.ContentPaths
import novex.android.ui.NovexIcons

// Markdown 行内媒体块：图片直渲、视频缩略图卡（外跳系统播放器）、
// 音频行内播放卡。块级分派见 MarkdownText.kt。

// ── 路径解析与外链 ──────────────────────────────────────────────────────────

/** Markdown 里的媒体 URL（minis:// / file:// / 绝对路径）解析成宿主 File；
 *  查不到或文件不存在返回 null。`#` 不截断——文件名本身可能带 '#'
 *  （如 "foo #China.mp4"），只有 `?` 查询串要剥。 */
internal fun resolveMediaFile(url: String): File? {
    if (url.isBlank()) return null
    val stripped = url.substringBefore('?')
    val hostFile = when {
        stripped.startsWith("minis://") -> ContentPaths.resolveHostPath(
            "/var/minis/" + URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8"))
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> return null
    }
    return hostFile?.takeIf { it.exists() && it.isFile }
}

internal fun filenameFromUrl(url: String): String {
    val last = url.substringBefore('?').substringAfterLast('/')
    return try { URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

/** 媒体文件交给系统选择器打开（系统播放器/文件管理器），比内置全屏播放器简单。 */
private fun openMediaExternally(context: Context, file: File, mime: String) {
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    } catch (_: Throwable) {
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }) }
}

// ── 图片 ────────────────────────────────────────────────────────────────────

@Composable
internal fun InlineImageCard(block: MarkdownParser.Block.Image) {
    AsyncImage(
        model = block.url,
        contentDescription = block.alt.ifEmpty { filenameFromUrl(block.url) },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    )
}

// ── 视频（缩略图卡片，点按外跳系统播放器）────────────────────────────────────

@Composable
internal fun VideoCard(block: MarkdownParser.Block.Video) {
    val context = LocalContext.current
    val file = remember(block.url) { resolveMediaFile(block.url) }
    val filename = remember(block.url) { filenameFromUrl(block.url) }

    // 后台线程取首帧作封面。
    val thumbnail by produceState<Bitmap?>(null, file?.absolutePath) {
        val f = file ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (t: Throwable) {
                Log.w("MdMedia", "video thumbnail failed: ${t.message}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable { file?.let { openMediaExternally(context, it, "video/*") } },
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                NovexIcons.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NovexIcons.Videocam, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(
                filename,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

// ── 音频（行内播放/暂停 + 进度）───────────────────────────────────────────────

@Composable
internal fun AudioCard(block: MarkdownParser.Block.Audio) {
    val context = LocalContext.current
    val file = remember(block.url) { resolveMediaFile(block.url) }
    val filename = remember(block.url) { filenameFromUrl(block.url) }

    // 每张卡一个 MediaPlayer；离开组合即释放。
    val player = remember(file?.absolutePath) {
        file?.let {
            runCatching { MediaPlayer().apply { setDataSource(it.absolutePath); prepare() } }.getOrNull()
        }
    }
    DisposableEffect(player) {
        onDispose { runCatching { player?.release() } }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(0)
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        val listener = MediaPlayer.OnCompletionListener {
            isPlaying = false
            positionMs = 0
            runCatching { player?.seekTo(0) }
        }
        player?.setOnCompletionListener(listener)
        onDispose { runCatching { player?.setOnCompletionListener(null) } }
    }

    val tint = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMediaExternally(context, it, "audio/*") }
                } else if (isPlaying) {
                    runCatching { player.pause() }; isPlaying = false
                } else {
                    runCatching { player.start() }; isPlaying = true
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NovexIcons.Audiotrack, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                block.alt.ifEmpty { filename },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (durationMs > 0) {
                Text(
                    "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            if (isPlaying) NovexIcons.Pause else NovexIcons.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

private fun formatClock(ms: Int): String {
    if (ms <= 0) return "0:00"
    return "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
}
