package com.openminis.app.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-wide, non-blocking update state shared by cold-start and the home toolbar. */
object NovexUpdateMonitor {
    private val coldCheckStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _available = MutableStateFlow<UpdateChecker.CheckResult.UpdateAvailable?>(null)
    val available: StateFlow<UpdateChecker.CheckResult.UpdateAvailable?> = _available.asStateFlow()

    // [T-announcement-v2] 冷启动顺带拉公告；unread 只在拿到真源公告时非空
    // （内置归档回落不跳脸——否则每次冷启动弹老内容）。已读判定需要
    // Context，由 UI 层在 collect 时过滤；此处只承载原始公告列表。
    private val _announcements = MutableStateFlow<List<NovexAnnouncement>?>(null)
    val announcements: StateFlow<List<NovexAnnouncement>?> = _announcements.asStateFlow()

    fun checkOnceOnColdStart() {
        if (!coldCheckStarted.compareAndSet(false, true)) return
        scope.launch {
            refresh()
            // 公告与更新检查分道失败互不影响（公告失败静默下次再试）
            runCatching {
                val bulletin = UpdateChecker.fetchBulletin()
                // 只认活源（内置归档回落不跳脸）
                if (bulletin.live) _announcements.value = bulletin.announcements.takeIf { it.isNotEmpty() }
            }
        }
    }

    suspend fun refresh(): UpdateChecker.CheckResult {
        val result = UpdateChecker.check()
        _available.value = result as? UpdateChecker.CheckResult.UpdateAvailable
        return result
    }

    fun clearAvailable() {
        _available.value = null
    }
}
