package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T180-bg-notif：后台相关开关的持久层（血统清剿 P3.7 就地真重写；
 * prefs 名与键集冻结）。目前住着三只布尔位 + 一对悬浮窗坐标：
 *
 * - 「任务通知」——对齐 iOS `EnhancedBackgroundSettingsView` 绑定的
 *   `BackgroundKeepAliveManager.backgroundNotificationsEnabled`。默认 true
 *   与 iOS 一致：首装即开，Live Activity 与任务完成通知开箱可用，用户可
 *   从设置里关。
 * - 「后台悬浮工具态」——默认关：悬浮窗要 SYSTEM_ALERT_WINDOW，那是独立
 *   的系统权限流程，用户不主动开就不出现。
 * - 「灵动岛实况」（Android 16 Live Updates）——默认关：仅 Android 16+
 *   且按应用授权才存在，开启时**替代**悬浮窗（AgentForegroundService.
 *   applyOverlayState 里互斥），不能让升级用户的行为被静默改写。响应式：
 *   翻转即重驱动前台服务的合成流，悬浮窗出现/消失不用重启应用。
 *
 * 布尔位全部以 StateFlow 对外——设置页拨开关，通知器/前台服务状态文案
 * 等每个消费点立刻跟上。
 */
class BackgroundSettingsRepository(context: Context) {

    private val store: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读持久值建初值 + 翻转即落盘的布尔位小骨架。 */
    private fun persistedFlag(key: String, default: Boolean) =
        MutableStateFlow(store.getBoolean(key, default))

    private fun MutableStateFlow<Boolean>.persist(key: String) =
        store.edit().putBoolean(key, value).apply()

    private val _taskNotifications = persistedFlag(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS)

    /** 任务完成通知开关（见类注释，默认开）。 */
    val taskNotificationsEnabled: StateFlow<Boolean> = _taskNotifications.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        _taskNotifications.value = value
        _taskNotifications.persist(KEY_TASK_NOTIFICATIONS)
    }

    private val _backgroundOverlay = persistedFlag(KEY_BG_OVERLAY_ENABLED, false)

    /** 后台悬浮工具态开关（默认关，需用户先过系统权限）。 */
    val backgroundOverlayEnabled: StateFlow<Boolean> = _backgroundOverlay.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        _backgroundOverlay.value = value
        _backgroundOverlay.persist(KEY_BG_OVERLAY_ENABLED)
    }

    private val _dynamicIsland = persistedFlag(KEY_DYNAMIC_ISLAND_ENABLED, false)

    /** 灵动岛实况开关（默认关，开启即替代悬浮窗）。 */
    val dynamicIslandEnabled: StateFlow<Boolean> = _dynamicIsland.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        _dynamicIsland.value = value
        _dynamicIsland.persist(KEY_DYNAMIC_ISLAND_ENABLED)
    }

    /**
     * 上次拖拽后记住的悬浮窗位置（窗口像素坐标）。-1 = 没记过——让悬浮
     * 控制器选默认位（左下角、各离边 10 dp，[T-bg-overlay-polish]）。
     */
    fun getOverlayX(): Int = store.getInt(KEY_BG_OVERLAY_X, -1)
    fun getOverlayY(): Int = store.getInt(KEY_BG_OVERLAY_Y, -1)

    fun setOverlayPosition(x: Int, y: Int) {
        store.edit().putInt(KEY_BG_OVERLAY_X, x).putInt(KEY_BG_OVERLAY_Y, y).apply()
    }

    companion object {
        private const val PREFS_NAME = "background_settings"
        private const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        private const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"
    }
}
