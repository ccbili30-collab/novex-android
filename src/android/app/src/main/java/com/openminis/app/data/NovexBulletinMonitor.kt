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

internal data class BulletinUiState(
    val manifest: BulletinManifest? = null,
    /** 已缓存正文（id→markdown），来自磁盘或网络，仅供渲染。 */
    val bodies: Map<String, String> = emptyMap(),
    val loadingBodies: Set<String> = emptySet(),
    val failedBodies: Set<String> = emptySet(),
    val readIds: Set<String> = emptySet(),
    val expandedIds: Set<String> = emptySet(),
    val update: UpdateChecker.CheckResult.UpdateAvailable? = null,
    val checkingUpdate: Boolean = false,
    val updateNotice: String? = null,
    val refreshing: Boolean = false,
    val refreshNotice: String? = null,
) {
    val unread: List<BulletinManifestEntry>
        get() = manifest?.announcements?.filter { it.id !in readIds } ?: emptyList()

    /** 红点双来源：未读公告 或 有未安装更新（保留到安装完成）。 */
    val hasBadge: Boolean
        get() = unread.isNotEmpty() || update != null
}

/**
 * [T-bulletin-v3] 公告+更新双体系统一监视器：冷启动是唯一主动拉取时机
 * （名册+未读正文+更新检查）。用户 2026-09-30：叠卡跳脸退役，冷启动只
 * 改状态（列表/红点），公告中心是唯一界面，任何时机都不主动弹窗。
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
                // 上游不可达：无提示打扰；离线兜底走缓存（面板打开时）
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
            _state.update {
                it.copy(
                    manifest = manifest,
                    readIds = read,
                    bodies = it.bodies + freshBodies,
                    update = available ?: it.update,
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

    /** 展开才加载：磁盘缓存（rev 匹配）优先，否则网络并落盘；失败保持未加载可重试。展开即已读。 */
    fun toggleExpand(id: String) {
        val wasExpanded = id in _state.value.expandedIds
        _state.update { s ->
            s.copy(expandedIds = if (wasExpanded) s.expandedIds - id else s.expandedIds + id)
        }
        if (!wasExpanded) {
            // 只有公告计已读；往期版本/新版本行共用此展开路径但不算公告。
            if (_state.value.manifest?.announcements?.any { it.id == id } == true) {
                appContext?.let { NovexAnnouncementReadStore.markRead(it, listOf(id)) }
                _state.update { it.copy(readIds = it.readIds + id) }
            }
            ensureBody(id)
        }
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

    /** 切换更新源后清掉上一源的检查结果。 */
    fun clearUpdate() {
        _state.update { it.copy(update = null, updateNotice = null) }
    }

    /** 手动检查更新：不弹窗，结果就地显示在公告中心「更新」页签。 */
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
                )
            }
            UpdateChecker.CheckResult.UpToDate -> _state.update {
                it.copy(checkingUpdate = false, updateNotice = "已是最新版本")
            }
            else -> _state.update { it.copy(checkingUpdate = false, updateNotice = "检查失败，请稍后再试") }
        }
    }
}
