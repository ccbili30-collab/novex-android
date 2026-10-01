package com.openminis.app.ui.sandbox

import android.text.format.Formatter
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import novex.android.ContentPaths

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

data class FileBrowserUiState(
    val items: List<FileItem> = emptyList(),
    val pathComponents: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isEmpty: Boolean = false,
    val canGoBack: Boolean = false,
    val currentPath: String = "",
    val errorMessage: String? = null,
    val sortKey: FileSortKey = FileSortKey.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    /**
     * Show files whose name starts with `.` (Unix dotfiles). Off by
     * default so the listing matches typical `ls` behaviour. Persisted
     * across app launches via SharedPreferences (see
     * [FileBrowserViewModel.PREFS_NAME]). T-hidden-files a3e7f1d0.
     */
    val showHidden: Boolean = false,
    /**
     * [T-android-file-context-copy-abs-path] Linux-side (PRoot) absolute path
     * of the directory currently shown, e.g. "/var/minis/workspace/foo". Null
     * when this browser isn't rooted under a Linux bind mount (a raw host-path
     * browser). The file context menu's "Copy Absolute Path" joins this with
     * the item name so the user copies the path the agent / shell sees
     * (/var/minis/…), not the opaque Android host path.
     */
    val currentLinuxPath: String? = null,
)

class FileBrowserViewModel(
    private val rootPath: File,
    initialPath: File? = null,
    private val rootLabel: String = rootPath.name,
    // linuxRootPath: 设置后目录列表走内容 bind mount 解析，让
    // /var/minis/{skills,memory,shared} 这类子目录列到真实内容
    // （filesDir/minis-global/*），而不是 Alpine rootfs 里的空占位目录。
    private val linuxRootPath: String? = null,
    // T147: 与 [appContext] 同时设置时，/var/minis/{attachments,workspace,
    // offloads,browser} 解析到本会话目录（filesDir/minis-sessions/<sid>/<subdir>）
    // 而不是全局 bindMounts 表——会话内文件浏览器能看到本 session agent
    // 产出的文件，即使最近一次拉起 shell 的是别的会话。
    private val sessionId: String? = null,
    private val appContext: android.content.Context? = null,
    // [T-android-copy-abs-path-fullpath] [rootPath] 对应的 Linux 路径，只用于
    // 计算「复制绝对路径」的显示值，与 [linuxRootPath]（同时会重路由目录列表）
    // 解耦。会话存储浏览器直接把 host 列表根设在会话目录，列表解析已经正确，
    // 不能再走 PRoot resolver（会把 /var/minis 重定向到全局空占位目录）；
    // 这个前缀只是让复制路径输出 /var/minis/workspace/foo.py 而不是
    // /data/user/0/.../minis-sessions/<sid>/workspace/foo.py。为 null 时回退
    // 用 [linuxRootPath]。
    private val displayLinuxPrefix: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    /** 未排序的原始列表缓存——重排序不用再 stat 目录。 */
    private var rawItems: List<FileItem> = emptyList()

    /** 相对 [rootPath] 的路径（如 "skills/skill-creator"）；空串 = 在根。 */
    private var relativePath: String = initialPath
        ?.takeIf { it.absolutePath.startsWith(rootPath.absolutePath) }
        ?.absolutePath
        ?.removePrefix(rootPath.absolutePath)
        ?.trim('/')
        .orEmpty()

    /**
     * T145: 打开屏幕时的 [relativePath] 快照——返回导航到这里为止
     * （goBack() 返回 false 让调用方 pop 屏幕），不再继续往 rootPath 爬。
     * 对齐 iOS FileBrowserView：初始目录就是本屏返回栈的有效根。
     */
    private val initialRelativePath: String = relativePath

    /** 当前目录的宿主侧 File（已按 PRoot bind mount 解析）。 */
    private val currentHostPath: File
        get() {
            val linuxRoot = linuxRootPath
            if (linuxRoot != null) {
                val linuxPath = if (relativePath.isEmpty()) linuxRoot
                    else "${linuxRoot.trimEnd('/')}/$relativePath"
                // T147: 知道会话 id 时优先走会话级 resolver——全局
                // bindMounts 是 last-writer-wins，指向最近拉起 PRoot 的会话。
                val ctx = appContext
                if (sessionId != null && ctx != null) {
                    ContentPaths.resolveSessionHostPath(sessionId, linuxPath, ctx)
                        ?.let { return it }
                }
                ContentPaths.resolveHostPath(linuxPath)?.let { return it }
            }
            return if (relativePath.isEmpty()) rootPath else File(rootPath, relativePath)
        }

    init {
        // 恢复持久化的 showHidden 偏好（T-hidden-files a3e7f1d0）。
        // appContext 为 null（测试环境）时回落默认 false。
        val persisted = appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.getBoolean(PREF_KEY_SHOW_HIDDEN, false)
            ?: false
        if (persisted) {
            _uiState.value = _uiState.value.copy(showHidden = true)
        }
        publishPathState()
        loadItems()
    }

    /**
     * 切换「显示隐藏文件」并立即重载当前目录。SharedPreferences 持久化，
     * 跨启动保留。T-hidden-files a3e7f1d0.
     */
    fun setShowHidden(value: Boolean) {
        if (_uiState.value.showHidden == value) return
        _uiState.value = _uiState.value.copy(showHidden = value)
        appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(PREF_KEY_SHOW_HIDDEN, value)
            ?.apply()
        loadItems()
    }

    fun navigateTo(item: FileItem) {
        if (!item.isDirectory) return
        relativePath = if (relativePath.isEmpty()) item.name else "$relativePath/${item.name}"
        publishPathState()
        loadItems()
    }

    /**
     * T145: 向上一层。返回 true = 已在内部消费（屏幕保留）；false = 已经
     * 在入口目录，调用方应退出本屏（popBackStack）。
     *
     * 没有 [initialRelativePath] 地板时用户能爬出进入点（爬到 /var/、/），
     * 让会话文件浏览器变成裸文件管理器。
     */
    fun goBack(): Boolean {
        if (relativePath == initialRelativePath) return false
        // 从入口之下的某层弹一级；即便用户绕路过（面包屑点到更深的
        // 兄弟目录），结果路径也要 clamp 在入口目录之内。
        val parent = relativePath.substringBeforeLast('/', "")
        relativePath = clampToEntry(parent)
        publishPathState()
        loadItems()
        return true
    }

    fun navigateToPathComponent(index: Int) {
        val components = _uiState.value.pathComponents
        val candidate = if (index <= 0) "" else {
            components.drop(1).take(index).joinToString("/")
        }
        // T145: 面包屑跳转同样 clamp 到入口目录，防止从路径条逃出。
        relativePath = clampToEntry(candidate)
        publishPathState()
        loadItems()
    }

    /** 把候选相对路径约束在入口目录之下；入口为空串时全放开。 */
    private fun clampToEntry(candidate: String): String {
        val initial = initialRelativePath
        return when {
            initial.isEmpty() -> candidate
            candidate == initial || candidate.startsWith("$initial/") -> candidate
            else -> initial
        }
    }

    fun deleteItem(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
                loadItems()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message)
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setSort(
        key: FileSortKey = _uiState.value.sortKey,
        ascending: Boolean = _uiState.value.sortAscending,
        foldersFirst: Boolean = _uiState.value.foldersFirst,
    ) {
        val newState = _uiState.value.copy(
            sortKey = key,
            sortAscending = ascending,
            foldersFirst = foldersFirst,
        )
        val sorted = rawItems.sortedWith(sortComparator(newState))
        _uiState.value = newState.copy(items = sorted, isEmpty = sorted.isEmpty())
    }

    private fun sortComparator(state: FileBrowserUiState): Comparator<FileItem> {
        val byName = compareBy<FileItem> { it.name.lowercase() }
        val byKey: Comparator<FileItem> = when (state.sortKey) {
            FileSortKey.NAME -> byName
            FileSortKey.MODIFIED -> compareBy<FileItem> { it.modifiedMs }.then(byName)
            FileSortKey.SIZE -> compareBy<FileItem> { it.size }.then(byName)
            FileSortKey.KIND -> compareBy<FileItem> {
                if (it.isDirectory) "" else it.file.extension.lowercase()
            }.then(byName)
        }
        val directional = if (state.sortAscending) byKey else byKey.reversed()
        // 文件夹置顶不受排序方向影响。
        return if (state.foldersFirst) {
            compareBy<FileItem> { if (it.isDirectory) 0 else 1 }.then(directional)
        } else directional
    }

    private fun loadItems() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val hostPath = currentHostPath
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 列表按符号链接目标 stat，但面包屑保留逻辑路径。
                val resolvedPath = try {
                    hostPath.toPath().toRealPath().toFile()
                } catch (_: Exception) {
                    hostPath
                }

                // 先快照 showHidden，避免 setter 与后台加载竞争读到撕裂值
                // （T-hidden-files a3e7f1d0）。
                val showHidden = _uiState.value.showHidden
                val files = resolvedPath.listFiles()
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.mapNotNull { FileItem.from(it) }
                    .orEmpty()

                rawItems = files
                val sorted = files.sortedWith(sortComparator(_uiState.value))
                _uiState.value = _uiState.value.copy(
                    items = sorted,
                    isLoading = false,
                    isEmpty = sorted.isEmpty(),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    items = emptyList(),
                    isLoading = false,
                    isEmpty = true,
                    errorMessage = e.message,
                )
            }
        }
    }

    private fun publishPathState() {
        val components = mutableListOf(rootLabel)
        if (relativePath.isNotEmpty()) {
            components += relativePath.split("/").filter { it.isNotEmpty() }
        }
        _uiState.value = _uiState.value.copy(
            pathComponents = components,
            // T145: 只有位于入口目录之下才可返回；在入口处的返回键
            // 交给调用方退出屏幕，而不是向上爬进 rootfs 包装层。
            canGoBack = relativePath != initialRelativePath,
            currentPath = currentHostPath.absolutePath,
            // [T-android-file-context-copy-abs-path] 「复制绝对路径」菜单
            // 用的 Linux 目录路径：优先 displayLinuxPrefix（会话存储浏览器），
            // 否则回退 linuxRootPath（Chat Files / 共享目录）。见构造器注释。
            currentLinuxPath = (displayLinuxPrefix ?: linuxRootPath)?.let { root ->
                if (relativePath.isEmpty()) root.trimEnd('/')
                else "${root.trimEnd('/')}/$relativePath"
            },
        )
    }

    companion object {
        val dateFormatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        /** SharedPreferences file for FileBrowser display options. */
        const val PREFS_NAME = "file_browser_prefs"
        /** Key for the "show hidden files" toggle. Persists across app launches. */
        const val PREF_KEY_SHOW_HIDDEN = "file_browser_show_hidden"

        fun formatDate(timestamp: Long): String {
            return if (timestamp > 0) dateFormatter.format(Date(timestamp)) else "—"
        }
    }
}
