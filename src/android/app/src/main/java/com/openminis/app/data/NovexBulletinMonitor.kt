package com.openminis.app.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** 跳脸叠卡：公告在上、更新在下；单新单卡。关闭公告=已读，关闭更新=本进程不再跳（红点保留）。 */
internal sealed interface BulletinStackCard {
    data class Announcements(val entries: List<BulletinManifestEntry>) : BulletinStackCard
    data class Update(val available: UpdateChecker.CheckResult.UpdateAvailable) : BulletinStackCard
}

internal data class BulletinUiState(
    val manifest: BulletinManifest? = null,
    /** 已缓存正文（id→markdown），来自磁盘或网络，仅供渲染。 */
    val bodies: Map<String, String> = emptyMap(),
    val loadingBodies: Set<String> = emptySet(),
    val failedBodies: Set<String> = emptySet(),
    val readIds: Set<String> = emptySet(),
    val expandedIds: Set<String> = emptySet(),
    val stack: List<BulletinStackCard> = emptyList(),
    val update: UpdateChecker.CheckResult.UpdateAvailable? = null,
    val dismissedUpdateVersion: String? = null,
    val checkingUpdate: Boolean = false,
    val updateNotice: String? = null,
    val refreshing: Boolean = false,
    val refreshNotice: String? = null,
    val coldStartNotice: String? = null,
) {
    val unread: List<BulletinManifestEntry>
        get() = manifest?.announcements?.filter { it.id !in readIds } ?: emptyList()

    /** 红点双来源：未读公告 或 有未安装更新（更新跳脸关掉也保留，直到安装）。 */
    val hasBadge: Boolean
        get() = unread.isNotEmpty() || update != null
}

/**
 * [T-bulletin-v3] 公告+更新双体系统一监视器：冷启动是唯一主动拉取时机
 * （名册+未读正文+更新检查），也是唯一产出跳脸叠卡的时机；会话内只有
 * 用户点刷新/检查更新才联网，且绝不弹窗——只改状态（列表/红点）。
 */
internal object NovexBulletinMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coldStarted = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().build()

    @Volatile private var appContext: android.content.Context? = null

    fun attachContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private val _state = MutableStateFlow(BulletinUiState())
    val state: StateFlow<BulletinUiState> = _state.asStateFlow()

    // 关卡防重入：按钮 onClick 与 Dialog onDismissRequest 偶发双发，
    // 双发会把后卡一并吞掉。350ms 内的第二次关闭忽略。
    @Volatile private var lastDismissAt = 0L

    /** 冷启动：更新半边沿用 [NovexUpdateMonitor]（旧入口共享 _available）。 */
    fun coldStartOnAppStart() {
        if (!coldStarted.compareAndSet(false, true)) return
        scope.launch {
            val context = appContext ?: return@launch
            val available = runCatching {
                NovexUpdateMonitor.refresh()
            }.getOrNull() as? UpdateChecker.CheckResult.UpdateAvailable

            val base = BulletinHub.rawBase(UpdateSourceStore.current())
            val manifest = runCatching {
                BulletinHub.fetchManifest(client, base)
            }.getOrNull()
            if (manifest == null) {
                // 上游不可达：不跳脸、无提示打扰；离线兜底走缓存（面板打开时）
                _state.update { it.copy(coldStartNotice = null) }
                return@launch
            }
            BulletinHub.saveManifest(context.filesDir, manifest)
            val read = NovexAnnouncementReadStore.readIds(context)
            val unread = manifest.announcements.filter { it.id !in read }
            val freshBodies = mutableMapOf<String, String>()
            for (entry in unread) {
                val body = BulletinHub.fetchBody(client, base, entry.id)
                if (body != null) {
                    BulletinHub.storeBody(context.filesDir, entry.id, entry.rev, body)
                    freshBodies[entry.id] = body
                }
            }
            val stack = buildList {
                if (unread.isNotEmpty()) add(BulletinStackCard.Announcements(unread))
                if (available != null) add(BulletinStackCard.Update(available))
            }
            _state.update {
                it.copy(
                    manifest = manifest,
                    readIds = read,
                    bodies = it.bodies + freshBodies,
                    update = available ?: it.update,
                    stack = it.stack + stack,
                    coldStartNotice = if (stack.isEmpty()) "没有新内容" else null,
                )
            }
        }
    }

    /** 手动刷新：名册对账（下线消失/缓存作废）+ rev 变了的已缓存正文重拉；绝不弹窗。 */
    fun refresh() = scope.launch {
        val context = appContext ?: return@launch
        if (_state.value.refreshing) return@launch
        _state.update { it.copy(refreshing = true, refreshNotice = null) }
        val base = BulletinHub.rawBase(UpdateSourceStore.current())
        val fresh = runCatching { BulletinHub.fetchManifest(client, base) }.getOrNull()
        if (fresh == null) {
            _state.update { it.copy(refreshing = false, refreshNotice = "刷新失败：网络不可用（已保留缓存）") }
            return@launch
        }
        BulletinHub.saveManifest(context.filesDir, fresh)
        val old = _state.value.manifest
        val removed = (old?.allIds ?: emptySet()) - fresh.allIds
        val refetched = mutableMapOf<String, String>()
        for (entry in fresh.announcements + fresh.releaseNotes) {
            val cachedRev = BulletinHub.cachedRev(context.filesDir, entry.id)
            val interested = entry.id in _state.value.bodies
            if (interested && (cachedRev == null || cachedRev != entry.rev)) {
                BulletinHub.fetchBody(client, base, entry.id)?.let {
                    BulletinHub.storeBody(context.filesDir, entry.id, entry.rev, it)
                    refetched[entry.id] = it
                }
            }
        }
        _state.update {
            it.copy(
                manifest = fresh,
                readIds = NovexAnnouncementReadStore.readIds(context),
                bodies = (it.bodies - removed) + refetched,
                refreshing = false,
                refreshNotice = buildString {
                    if (removed.isNotEmpty()) append("已下线 ${removed.size} 条；")
                    if (refetched.isNotEmpty()) append("重拉 ${refetched.size} 条修订")
                    if (isEmpty()) append("名册无变化")
                },
            )
        }
    }

    /** 展开才加载：磁盘缓存（rev 匹配）优先，否则网络并落盘；失败保持未加载可重试。 */
    fun toggleExpand(id: String) {
        val wasExpanded = id in _state.value.expandedIds
        _state.update { s ->
            s.copy(expandedIds = if (wasExpanded) s.expandedIds - id else s.expandedIds + id)
        }
        if (!wasExpanded) ensureBody(id)
    }

    fun ensureBody(id: String) = scope.launch {
        val context = appContext ?: return@launch
        val entry = _state.value.manifest?.let { m ->
            (m.announcements + m.releaseNotes).firstOrNull { it.id == id }
        } ?: return@launch
        val cachedRev = BulletinHub.cachedRev(context.filesDir, id)
        val cached = BulletinHub.cachedBody(context.filesDir, id)
        if (cached != null && cachedRev == entry.rev) {
            _state.update { it.copy(bodies = it.bodies + (id to cached)) }
            return@launch
        }
        if (id in _state.value.loadingBodies) return@launch
        _state.update { it.copy(loadingBodies = it.loadingBodies + id, failedBodies = it.failedBodies - id) }
        val base = BulletinHub.rawBase(UpdateSourceStore.current())
        val body = runCatching { BulletinHub.fetchBody(client, base, id) }.getOrNull()
        if (body != null) {
            BulletinHub.storeBody(context.filesDir, id, entry.rev, body)
            _state.update { it.copy(loadingBodies = it.loadingBodies - id, bodies = it.bodies + (id to body)) }
        } else {
            _state.update { it.copy(loadingBodies = it.loadingBodies - id, failedBodies = it.failedBodies + id) }
        }
    }

    /** 关闭叠卡首张：公告=关闭即已读；更新=本进程不再跳，红点保留到安装。 */
    fun dismissFront() {
        val now = System.currentTimeMillis()
        if (now - lastDismissAt < 350) return
        lastDismissAt = now
        val front = _state.value.stack.firstOrNull() ?: return
        val context = appContext
        _state.update { s ->
            when (front) {
                is BulletinStackCard.Announcements -> {
                    context?.let { NovexAnnouncementReadStore.markRead(it, front.entries.map { e -> e.id }) }
                    s.copy(
                        readIds = s.readIds + front.entries.map { it.id },
                        stack = s.stack.drop(1),
                    )
                }
                is BulletinStackCard.Update -> s.copy(
                    dismissedUpdateVersion = front.available.versionName,
                    stack = s.stack.drop(1),
                )
            }
        }
    }

    /** 手动检查更新：不弹窗，结果就地显示；更新卡只在未被本进程略过时入叠卡。 */
    fun manualCheckUpdate() = scope.launch {
        if (_state.value.checkingUpdate) return@launch
        _state.update { it.copy(checkingUpdate = true, updateNotice = null) }
        val result = runCatching { NovexUpdateMonitor.refresh() }.getOrNull()
        when (result) {
            is UpdateChecker.CheckResult.UpdateAvailable -> _state.update { s ->
                s.copy(
                    checkingUpdate = false,
                    update = result,
                    updateNotice = "发现新版本 ${result.versionName}",
                    stack = if (s.dismissedUpdateVersion == result.versionName) s.stack else {
                        val hasUpdateCard = s.stack.any { it is BulletinStackCard.Update }
                        if (hasUpdateCard) s.stack else s.stack + BulletinStackCard.Update(result)
                    },
                )
            }
            UpdateChecker.CheckResult.UpToDate -> _state.update {
                it.copy(checkingUpdate = false, updateNotice = "已是最新版本")
            }
            else -> _state.update { it.copy(checkingUpdate = false, updateNotice = "检查失败，请稍后再试") }
        }
    }
}
