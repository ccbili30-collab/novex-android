package com.openminis.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide update state shared by cold-start and the home toolbar.
 * 公告拉取与弹窗在 2026-09-30 后全部收敛到 NovexBulletinMonitor/公告中心，
 * 这里只保留「已检测到的更新」这一个信号（驱动入口红点）。
 */
object NovexUpdateMonitor {
    private val _available = MutableStateFlow<UpdateChecker.CheckResult.UpdateAvailable?>(null)
    val available: StateFlow<UpdateChecker.CheckResult.UpdateAvailable?> = _available.asStateFlow()

    suspend fun refresh(): UpdateChecker.CheckResult {
        val result = UpdateChecker.check()
        _available.value = result as? UpdateChecker.CheckResult.UpdateAvailable
        return result
    }

    fun clearAvailable() {
        _available.value = null
    }
}
