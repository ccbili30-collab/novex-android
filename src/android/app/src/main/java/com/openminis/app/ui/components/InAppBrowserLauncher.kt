package com.openminis.app.ui.components

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * [P3.3 裁军] 内置浏览器全家（browser/ + ui/browser/ + ui/preview/ +
 * UrlPreviewSheet + InAppBrowserHost/LocalInAppBrowserLauncher）按用户裁决
 * 整体退役。消息里的链接点击一律改走系统浏览器/系统处理器：
 *
 *  - [openExternalUrl] — http(s)/mailto/tel/geo 等 URL 的 ACTION_VIEW 外跳
 *    （原先 UrlPreviewSheet 的内部预览路径与 BrowserExternalSchemeHandler
 *    的外部 scheme 路由都收口到这里）。
 *  - [openMediaFileExternally] — 会话内媒体文件（视频/音频链接、附件预览
 *    的外部打开入口）经 FileProvider 交给系统播放器（替代被裁的
 *    ui/media/InlineMediaPlayer 内嵌播放器）。
 */
fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** Guess a mime type for [file] from its extension; video/audio fallbacks. */
fun mediaMimeTypeFor(file: File): String {
    val ext = file.extension.lowercase()
    val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    return when {
        fromMap != null -> fromMap
        ext in setOf("mp4", "mov", "mkv", "webm", "avi", "m4v") -> "video/*"
        ext in setOf("mp3", "wav", "aac", "flac", "ogg", "m4a", "opus") -> "audio/*"
        else -> "application/octet-stream"
    }
}

/**
 * Hand a local media file to the system player via ACTION_VIEW + FileProvider.
 * The provider's declared roots cover per-session files (minis-sessions/) and
 * global storage; anything outside them falls back to a file:// URI, and an
 * unresolvable intent is swallowed (same contract as the retired player's
 * share path — never crash the chat over a missing player).
 */
fun openMediaFileExternally(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    } catch (_: Throwable) {
        file.toUri()
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mediaMimeTypeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
