package com.openminis.app.ui.chat

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import novex.android.ContentPaths
import novex.android.ui.NovexIcons

// Markdown 内嵌媒体：minis:// / file:// / 绝对路径 → 宿主 File 解析
// （会话级 bindMounts 优先，全局兜底，再扫各会话目录按 basename 找回），
// 视频缩略图卡（外跳系统播放器）与音频行内播放卡。

// ─── Media helpers ──────────────────────────────────────────────────────────

/**
 * Resolve a markdown media URL (`minis://attachments/foo.mp4`, file://, or
 * plain absolute path) to a host File.
 *
 * First tries `ContentPaths.resolveHostPath` (same as MinisImageFetcher). If
 * that fails — e.g. bind mounts are pointing at a different session, or the
 * file was written under a `__new__...` draft id that predates
 * `ensureSession()` rename — we fall back to scanning all per-session
 * attachment directories for a file of the same basename. Mirrors the iOS
 * attach-path resolution which walks the session cache when the primary
 * lookup misses.
 */
internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String? = null): File? {
    if (url.isBlank()) return null
    // Strip a real query (`?`), but NOT `#` — attachment filenames legitimately
    // contain '#' (hashtags). `minis://` URLs don't carry fragments anyway,
    // and truncating here would hide the '.mp4' extension and the file's real
    // name from the resolver.
    val stripped = url.substringBefore('?')
    val primary: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            val linuxPath = "/var/minis/$decoded"
            // Prefer the session-scoped resolver when the caller supplied a
            // sessionId: the global `bindMounts` map is overwritten every time
            // another session boots its shell, so without sessionId we'd route
            // this chat's attachment lookup to whichever session happened to
            // boot last.
            if (sessionId != null) ContentPaths.resolveSessionHostPath(sessionId, linuxPath, context)
            else ContentPaths.resolveHostPath(linuxPath)
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    if (primary?.let { it.exists() && it.isFile } == true) {
        return primary
    }

    // Fallback: search every minis-sessions/<id>/{attachments,workspace,offloads,browser}
    // subtree for a file whose basename matches. Handles leftover files from a
    // draft session whose bind mount has already switched over, and the case
    // where `resolveHostPath`'s global bindMounts map points at a different
    // session than the one owning this message.
    if (!stripped.startsWith("minis://")) {
        return null
    }
    val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
    val basename = decoded.substringAfterLast('/')
    val subdir = decoded.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: "attachments"
    val root = File(context.filesDir, "minis-sessions")
    if (root.isDirectory) {
        root.listFiles()?.forEach { sessionDir ->
            val candidate = File(sessionDir, "$subdir/$basename")
            if (candidate.exists() && candidate.isFile) {
                return candidate
            }
        }
    }
    // Also probe `minis-global/<subdir>` for shared/memory/skills buckets.
    val globalCandidate = File(context.filesDir, "minis-global/$subdir/$basename")
    if (globalCandidate.exists() && globalCandidate.isFile) {
        return globalCandidate
    }
    android.util.Log.w("MdStream", "resolveMdMediaFile url=$url -> NOT FOUND (primary=${primary?.absolutePath})")
    return null
}

private fun filenameFromMdUrl(url: String): String {
    // Keep '#' — it's a legitimate character in attachment filenames.
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

private fun openMdMediaExternally(context: Context, file: File, mime: String) {
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
        android.util.Log.w("MdStream", "FileProvider failed: ${t.message}")
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(chooser) } catch (t: Throwable) {
        android.util.Log.w("MdStream", "startActivity failed: ${t.message}")
    }
}

private fun formatMdMediaMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
internal fun RenderMdVideo(block: MdBlock.Video) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bmp
            } catch (t: Throwable) {
                android.util.Log.w("MdStream", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    // [P3.3 裁军] 全屏内嵌视频播放器（MinisFullscreenVideoPlayer）退役；
    // 点击改经 FileProvider 外跳系统播放器。

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = file != null) {
                file?.let { com.openminis.app.ui.components.openMediaFileExternally(context, it) }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                imageVector = novex.android.ui.NovexIcons.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = novex.android.ui.NovexIcons.Videocam,
                contentDescription = null,
                tint = colors.blockquote,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            MdText(
                text = AnnotatedString(filename),
                fontSize = 12.sp,
                color = colors.blockquote,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun RenderMdAudio(block: MdBlock.Audio) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
        } catch (t: Throwable) {
            android.util.Log.w("MdStream", "audio prepare failed: ${t.message}")
            null
        }
    }
    DisposableEffect(player) {
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player.seekTo(0) } catch (_: Throwable) {}
        }
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = colors.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMdMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) { try { player.pause() } catch (_: Throwable) {} ; isPlaying = false }
                    else { try { player.start(); isPlaying = true } catch (_: Throwable) {} }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = novex.android.ui.NovexIcons.Audiotrack,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            MdText(
                text = AnnotatedString(block.alt.ifEmpty { filename }),
                fontSize = 13.sp,
                color = colors.text,
                maxLines = 1,
            )
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (durationMs > 0) {
                MdText(
                    text = AnnotatedString("${formatMdMediaMs(positionMs)} / ${formatMdMediaMs(durationMs)}"),
                    fontSize = 11.sp,
                    color = colors.blockquote,
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) novex.android.ui.NovexIcons.Pause else novex.android.ui.NovexIcons.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}
