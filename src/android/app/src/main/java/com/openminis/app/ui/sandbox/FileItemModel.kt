package com.openminis.app.ui.sandbox

// 文件条目模型与扩展名分类表：icon 路由/预览类型判定/日期格式化共享
// 这些集合，VM 只管目录导航与排序状态。

import android.text.format.Formatter
import androidx.compose.runtime.Immutable
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Mirrors iOS FileSortKey. */
enum class FileSortKey { NAME, MODIFIED, SIZE, KIND }

// 扩展名分类表：同一个 FileItem 的 icon/预览路由/文本判定共享这些集合，
// 不再在每个 getter 里内联 setOf。
private val TEXT_EXTS = setOf(
    "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
    "log", "csv", "sh", "bash", "zsh", "fish",
    "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
    "rs", "go", "rb", "php", "lua", "pl", "html", "css", "scss",
    "toml", "env", "gitignore", "dockerfile", "makefile",
)
private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
private val MARKDOWN_EXTS = setOf("md", "markdown", "mdown", "mkd")
private val HTML_EXTS = setOf("html", "htm", "xhtml")
private val AUDIO_EXTS = setOf("mp3", "wav", "aac", "flac", "ogg", "m4a")
private val VIDEO_EXTS = setOf("mp4", "mov", "avi", "mkv", "webm")
private val CSV_EXTS = setOf("csv", "tsv")
private val ARCHIVE_EXTS = setOf("zip", "jar", "apk", "aar")
private val OFFICE_EXTS = setOf(
    "xlsx", "xls", "docx", "doc", "pptx", "ppt", "odt", "ods", "odp",
)

private val ICON_BY_GROUP = listOf(
    setOf(
        "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
        "log", "csv",
    ) to "text",
    setOf("sh", "bash", "zsh", "fish") to "terminal",
    setOf(
        "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
        "rs", "go", "rb", "php", "lua", "pl",
    ) to "code",
    IMAGE_EXTS + "svg" to "image",
    AUDIO_EXTS to "audio",
    VIDEO_EXTS to "video",
    setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar") to "archive",
    setOf("pdf") to "pdf",
    setOf("apk", "deb", "rpm") to "package",
    setOf("db", "sqlite", "sqlite3") to "database",
    setOf("so", "dylib", "a") to "library",
)

/**
 * File item model — corresponds to iOS FileItem.
 */
@Immutable
data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** lastModified() in epoch ms, 0 when unavailable. */
    val modifiedMs: Long = 0L,
) {
    private val ext: String get() = file.extension.lowercase()

    val iconRes: String
        get() = if (isDirectory) "folder"
            else ICON_BY_GROUP.firstOrNull { ext in it.first }?.second ?: "file"

    val formattedSize: String
        get() = Formatter.formatFileSize(null, size)

    // T144 — file-type flags driving FilePreviewScreen renderers.
    val isTextFile: Boolean get() = ext.isEmpty() || ext in TEXT_EXTS
    val isImageFile: Boolean get() = ext in IMAGE_EXTS
    val isMarkdownFile: Boolean get() = ext in MARKDOWN_EXTS
    val isHtmlFile: Boolean get() = ext in HTML_EXTS
    val isAudioFile: Boolean get() = ext in AUDIO_EXTS
    val isVideoFile: Boolean get() = ext in VIDEO_EXTS
    val isPdfFile: Boolean get() = ext == "pdf"
    val isCsvFile: Boolean get() = ext in CSV_EXTS
    val isJsonFile: Boolean get() = ext == "json"
    val isArchiveFile: Boolean get() = ext in ARCHIVE_EXTS
    val isOfficeFile: Boolean get() = ext in OFFICE_EXTS

    /** Mirrors iOS FileItem.formattedDate: today=time, yesterday="Yesterday", <7d=weekday, else short date. */
    val formattedDate: String
        get() {
            if (modifiedMs <= 0L) return ""
            val then = Calendar.getInstance().apply { time = Date(modifiedMs) }
            val now = Calendar.getInstance()
            fun sameDay(a: Calendar, b: Calendar) =
                a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            val date = then.time
            if (sameDay(now, then)) return timeFormatter.format(date)
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            if (sameDay(yesterday, then)) return "Yesterday"
            val daysAgo = (now.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)
            if (daysAgo in 0..6) return weekdayFormatter.format(date)
            return shortDateFormatter.format(date)
        }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        private val shortDateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun from(file: File): FileItem? {
            if (!file.exists()) return null
            val isSymlink = Files.isSymbolicLink(file.toPath())
            // 符号链接：stat 目标决定目录性/大小/时间，条目本身保留逻辑路径。
            val resolved = if (!isSymlink) file else try {
                val target = Files.readSymbolicLink(file.toPath()).toFile()
                if (target.isAbsolute) target else File(file.parentFile, target.path)
            } catch (_: Exception) {
                file
            }
            return FileItem(
                file = file,
                name = file.name,
                isDirectory = resolved.isDirectory,
                isSymlink = isSymlink,
                size = if (resolved.isDirectory) 0L else resolved.length(),
                modifiedMs = resolved.lastModified(),
            )
        }
    }
}
