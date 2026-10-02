package com.openminis.app.ui.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import novex.android.ContentPaths

/**
 * 文件浏览器 ViewModel（P4 骨架件重写）。
 *
 * 目录导航状态机：权威态是 [relativePath]（相对 [rootPath] 的逻辑路径），
 * 派生态经 [publishPathState] 投影进 [uiState]；目录内容经 [loadItems] 在
 * IO 上读取。T145 起入口目录 [initialRelativePath] 是返回栈的地板——
 * [goBack] 与面包屑跳转都被 [clampToEntry] 约束在入口之下。
 *
 * [FileBrowserUiState] 的字段集与构造器参数是 UI 消费契约（冻结面）；
 * [PREFS_NAME]/[PREF_KEY_SHOW_HIDDEN] 是持久化键（冻结面）。
 */
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
    /** 显示点开头的 Unix 隐藏文件；默认关（对齐 ls）。持久化见 [FileBrowserViewModel.PREFS_NAME]。T-hidden-files a3e7f1d0。 */
    val showHidden: Boolean = false,
    /**
     * [T-android-file-context-copy-abs-path] 当前目录的 Linux 侧（PRoot）
     * 绝对路径，如 "/var/minis/workspace/foo"。浏览器不扎根在 bind mount
     * 上时为 null。文件菜单「复制绝对路径」用它拼 item 名，用户复制到的
     * 是 agent / shell 看到的 /var/minis/… 而非宿主路径。
     */
    val currentLinuxPath: String? = null,
)

class FileBrowserViewModel(
    private val rootPath: File, initialPath: File? = null,
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
    private val appContext: Context? = null,
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

    /** 当前目录的宿主侧 File（已按内容 bind mount 解析）。 */
    private val currentHostPath: File
        get() = resolveCurrentHostPath()

    init {
        restoreHiddenFilePreference()
        publishPathState()
        loadItems()
    }

    /** 恢复持久化的 showHidden 偏好（T-hidden-files a3e7f1d0）；无 Context 默认 false。 */
    private fun restoreHiddenFilePreference() {
        val persisted = hiddenFilePrefs()?.getBoolean(PREF_KEY_SHOW_HIDDEN, false) ?: false
        if (persisted) _uiState.value = _uiState.value.copy(showHidden = true)
    }

    private fun hiddenFilePrefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 切换「显示隐藏文件」并立即重载当前目录。SharedPreferences 持久化，
     * 跨启动保留。T-hidden-files a3e7f1d0.
     */
    fun setShowHidden(value: Boolean) {
        if (_uiState.value.showHidden == value) return
        _uiState.value = _uiState.value.copy(showHidden = value)
        hiddenFilePrefs()?.edit()?.putBoolean(PREF_KEY_SHOW_HIDDEN, value)?.apply()
        loadItems()
    }

    // ─── 导航状态机 ────────────────────────────────────────────────────────

    fun navigateTo(item: FileItem) {
        if (!item.isDirectory) return
        descend(item.name)
    }

    /** 把 [segment] 接到当前相对路径之后并重载。 */
    private fun descend(segment: String) {
        relativePath = listOf(relativePath.takeIf { it.isNotEmpty() }, segment)
            .filterNotNull()
            .joinToString("/")
        refreshAfterNavigation()
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
        relativePath = clampToEntry(relativePath.substringBeforeLast('/', ""))
        refreshAfterNavigation()
        return true
    }

    fun navigateToPathComponent(index: Int) {
        val components = _uiState.value.pathComponents
        // 面包屑第 0 段是 rootLabel，不是路径段；index<=0 即回逻辑根。
        val candidate = if (index <= 0) "" else {
            components.drop(1).take(index).joinToString("/")
        }
        // T145: 面包屑跳转同样 clamp 到入口目录，防止从路径条逃出。
        relativePath = clampToEntry(candidate)
        refreshAfterNavigation()
    }

    private fun refreshAfterNavigation() {
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

    /** 把 Linux 侧根前缀与 [relativePath] 拼成当前目录的 Linux 路径。 */
    private fun linuxDirectoryPath(): String? {
        val linuxRoot = displayLinuxPrefix ?: linuxRootPath ?: return null
        val rel = relativePath.takeIf { it.isNotEmpty() }
        return if (rel == null) linuxRoot.trimEnd('/') else "${linuxRoot.trimEnd('/')}/$rel"
    }

    /** 相对路径 → 段列表（空段滤掉）。 */
    private fun pathSegments(): List<String> =
        relativePath.split("/").filter { it.isNotEmpty() }

    private fun publishPathState() {
        _uiState.value = _uiState.value.copy(
            pathComponents = buildList {
                add(rootLabel)
                addAll(pathSegments())
            },
            // T145: 只有位于入口目录之下才可返回；在入口处的返回键
            // 交给调用方退出屏幕，而不是向上爬进 rootfs 包装层。
            canGoBack = relativePath != initialRelativePath,
            currentPath = currentHostPath.absolutePath,
            // [T-android-file-context-copy-abs-path] 「复制绝对路径」菜单
            // 用的 Linux 目录路径：优先 displayLinuxPrefix（会话存储浏览器），
            // 否则回退 linuxRootPath（Chat Files / 共享目录）。见构造器注释。
            currentLinuxPath = linuxDirectoryPath(),
        )
    }

    /** 宿主路径解析：先内容 bind mount（会话级 → 全局），再回退 rootPath 布局。 */
    private fun resolveCurrentHostPath(): File {
        val linuxRoot = linuxRootPath ?: return hostPathUnderRoot()
        val linuxPath = linuxDirectoryPath() ?: return hostPathUnderRoot()
        // T147: 知道会话 id 时优先走会话级 resolver——全局 bindMounts 是
        // last-writer-wins，指向最近拉起 shell 的会话。
        val ctx = appContext
        if (sessionId != null && ctx != null) {
            ContentPaths.resolveSessionHostPath(sessionId, linuxPath, ctx)?.let { return it }
        }
        ContentPaths.resolveHostPath(linuxPath)?.let { return it }
        return hostPathUnderRoot()
    }

    private fun hostPathUnderRoot(): File =
        relativePath.takeIf { it.isNotEmpty() }?.let { File(rootPath, it) } ?: rootPath

    // ─── 目录内容与排序 ────────────────────────────────────────────────────

    fun deleteItem(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (item.isDirectory) item.file.deleteRecursively() else item.file.delete()
            }.onSuccess {
                loadItems()
            }.onFailure { e ->
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
            sortKey = key, sortAscending = ascending, foldersFirst = foldersFirst,
        )
        val sorted = rawItems.sortedWith(sortComparator(newState))
        _uiState.value = newState.copy(items = sorted, isEmpty = sorted.isEmpty())
    }

    /** 名称兜底的字典序（小写口径）。 */
    private val nameOrder: Comparator<FileItem> =
        compareBy { it.name.lowercase() }

    /** 各排序键的主比较器；平键时名称兜底。 */
    private fun primaryOrder(state: FileBrowserUiState): Comparator<FileItem> = when (state.sortKey) {
        FileSortKey.NAME -> nameOrder
        FileSortKey.MODIFIED -> compareBy<FileItem> { it.modifiedMs }.then(nameOrder)
        FileSortKey.SIZE -> compareBy<FileItem> { it.size }.then(nameOrder)
        FileSortKey.KIND -> compareBy<FileItem> {
            if (it.isDirectory) "" else it.file.extension.lowercase()
        }.then(nameOrder)
    }

    private fun sortComparator(state: FileBrowserUiState): Comparator<FileItem> {
        val directional = primaryOrder(state).let {
            if (state.sortAscending) it else it.reversed()
        }
        // 文件夹置顶不受排序方向影响。
        return if (state.foldersFirst) {
            compareBy<FileItem> { if (it.isDirectory) 0 else 1 }.then(directional)
        } else {
            directional
        }
    }

    /** 读目录并落盘到 uiState；成功/失败两路终态都清掉 isLoading。 */
    private fun loadItems() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val hostPath = currentHostPath
        viewModelScope.launch(Dispatchers.IO) {
            // 先快照 showHidden，避免 setter 与后台加载竞争读到撕裂值
            // （T-hidden-files a3e7f1d0）。
            val includeHidden = _uiState.value.showHidden
            val snapshot = runCatching { readDirectorySnapshot(hostPath, includeHidden) }
            _uiState.value = snapshot.fold(
                onSuccess = { files ->
                    rawItems = files
                    val sorted = files.sortedWith(sortComparator(_uiState.value))
                    _uiState.value.copy(items = sorted, isLoading = false, isEmpty = sorted.isEmpty())
                },
                onFailure = { e ->
                    _uiState.value.copy(
                        items = emptyList(), isLoading = false,
                        isEmpty = true, errorMessage = e.message,
                    )
                },
            )
        }
    }

    /** 列表按符号链接目标 stat，但面包屑保留逻辑路径。 */
    private fun readDirectorySnapshot(hostPath: File, includeHidden: Boolean): List<FileItem> {
        val listingRoot = runCatching { hostPath.toPath().toRealPath().toFile() }
            .getOrElse { hostPath }
        return listingRoot.listFiles()
            .orEmpty()
            .filter { includeHidden || !it.name.startsWith(".") }
            .mapNotNull { FileItem.from(it) }
    }

    companion object {
        val dateFormatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        /** SharedPreferences file for FileBrowser display options. */
        const val PREFS_NAME = "file_browser_prefs"

        /** Key for the "show hidden files" toggle. Persists across app launches. */
        const val PREF_KEY_SHOW_HIDDEN = "file_browser_show_hidden"

        fun formatDate(timestamp: Long): String =
            if (timestamp > 0) dateFormatter.format(Date(timestamp)) else "—"
    }
}
