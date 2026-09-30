package com.openminis.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用级 APK 下载态：公告中心「更新」按钮、跳脸卡、设置页共用同一个持有者，
 * 关掉公告弹层不取消下载。进程存活即存活；进程被杀后靠
 * [UpdateChecker.resumablePendingFile] 的落盘记录恢复到 Downloaded（「安装」）。
 */
object NovexUpdateDownload {

    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Float) : State
        data class Downloaded(val file: File) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 开始下载；下载中重复点击忽略。进程级 scope，不随 Composable 退出取消。 */
    fun start(context: Context, update: UpdateChecker.CheckResult.UpdateAvailable) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(0f)
        val appContext = context.applicationContext
        scope.launch {
            val result = UpdateChecker.download(
                context = appContext,
                url = update.apkUrl,
                versionName = update.versionName,
            ) { p -> _state.value = State.Downloading(p) }
            _state.value = when (result) {
                is UpdateChecker.DownloadResult.Success -> State.Downloaded(result.file)
                is UpdateChecker.DownloadResult.Error -> State.Failed(result.message)
            }
        }
    }

    /**
     * 冷启动恢复：上一轮下载好的 APK 仍在盘上时直接进 Downloaded，
     * 公告中心重开即显示「安装」而不是重新下载。
     */
    fun hydrateFromPending(context: Context) {
        if (_state.value !is State.Idle) return
        UpdateChecker.resumablePendingFile(context.applicationContext)
            ?.let { _state.value = State.Downloaded(it) }
    }

    /**
     * 安装已下载的 APK。返回 true 表示系统安装器已拉起（pending 记录由
     * [UpdateChecker.installApk] 负责清）。权限未授予时返回 false，调用方
     * 应引导去 [UpdateChecker.openInstallPermissionSettings]。
     */
    fun install(context: Context): Boolean {
        val file = (_state.value as? State.Downloaded)?.file ?: return false
        if (!UpdateChecker.canInstall(context)) return false
        if (!UpdateChecker.apkSignerMatches(context, file)) {
            // 签名冲突在系统安装器里只有一句通用报错；提前拦截给出可执行提示。
            PendingUpdateStore.clearPending(context)
            _state.value = State.Failed(
                "安装包与当前版本签名不一致，无法直接升级。请卸载当前版本后，从发布页重新下载安装。",
            )
            return false
        }
        if (!UpdateChecker.installApk(context, file)) return false
        NovexUpdateMonitor.clearAvailable()
        _state.value = State.Idle
        return true
    }

    fun reset() {
        _state.value = State.Idle
    }
}
