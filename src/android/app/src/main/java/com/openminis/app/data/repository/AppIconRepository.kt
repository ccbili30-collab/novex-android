package com.openminis.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log

/**
 * T-android-dynamic-app-icon：启动器图标的别名切换器（血统清剿 P3.7 就地
 * 真重写；prefs 名/键、别名 FQN、id 串为契约冻结面）。
 *
 * 对齐 iOS `UIApplication.setAlternateIconName`：用户在 设置 → 外观 →
 * 应用图标 里挑变体，我们经 PackageManager 启/禁对应的 `<activity-alias>`。
 *
 * 接线：
 *  - 三个别名都指向 MainActivity（AndroidManifest 声明）；
 *  - 任一时刻恰好一个别名启用——选新变体即启用它、禁掉其余；
 *  - DONT_KILL_APP 保住切换期间的当前 Activity；启动器自己的图标缓存
 *    可能要几秒才刷新（启动器侧的缓存细节，不受我们控制）。
 *
 * 当前选择同步落 prefs——外观屏要在 PackageManager 状态回读（切换后可能
 * 滞后）之前就渲染出正确的勾选位。
 */
object AppIconRepository {
    private const val TAG = "AppIconRepository"
    private const val PREFS = "app_icon_prefs"
    private const val KEY_SELECTED_ID = "selected_icon_id"
    private const val PACKAGE_NAME = "com.openminis.app"

    enum class Variant(val id: String, val aliasClass: String) {
        Auto("auto", "$PACKAGE_NAME.MainActivityIconAuto"),
        ClassicLight("classic_light", "$PACKAGE_NAME.MainActivityIconLight"),
        ClassicDark("classic_dark", "$PACKAGE_NAME.MainActivityIconDark"),
        ;

        companion object {
            /** 未知 id 一律回落 Auto。 */
            fun fromId(id: String?): Variant = entries.firstOrNull { it.id == id } ?: Auto
        }
    }

    private fun store(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): Variant =
        Variant.fromId(store(context).getString(KEY_SELECTED_ID, Variant.Auto.id))

    /**
     * 把 [target] 设为当前启动器图标：一遍 PackageManager 往返里启目标、
     * 禁其余，然后持久化选择。已是当前值时空转返回 false——PackageManager
     * 调用不便宜（写包状态），启动器也不喜欢无变化的重复切换。
     */
    fun apply(context: Context, target: Variant): Boolean {
        val ctx = context.applicationContext
        val from = current(ctx)
        if (from == target) return false
        try {
            for (variant in Variant.entries) {
                val state = if (variant == target) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx, variant.aliasClass),
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
            store(ctx).edit().putString(KEY_SELECTED_ID, target.id).apply()
            Log.i(TAG, "icon switched ${from.id} → ${target.id}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "icon switch failed: ${t.message}", t)
            return false
        }
    }
}
