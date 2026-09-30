package novex.android.powerguard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import novex.android.logkit.RunLog

/**
 * 后台保活的两块 Android 专属管道（P3.5c 自 power/PowerOptimizationManager
 * 真重写；iOS 没有等价物——它根本不允许任意时长的后台工作）：
 *
 *  1. 电池优化（Doze）豁免：原生 Android 13+ 光有前台服务不够，应用
 *     退后台就进 App Standby / Doze；查询用户是否已把应用加进豁免名单。
 *  2. OEM 自启/后台运行权限：几家国产 ROM（MIUI、EMUI/鸿蒙、ColorOS、
 *     OriginOS、OneUI）在原生之上再叠一层“自启”开关——没进白名单的
 *     应用退后台几分钟就被杀，前台服务也保不住。无公开 API 查状态，
 *     只有各厂商各版本各自的设置页入口；逐个试、全败则落应用详情页。
 *
 * 返回值刻意弱化：引导文案是设置 UI 的职责，这里只管机制。
 * OEM 组件清单（冻结面）：各厂商的 ComponentName 逐字保留，错一个
 * 拼写就打不开对应设置页。
 */
/** 厂商家族正典在旧路径门面（UI 钉 `PowerOptimizationManager.Vendor`，
 *  嵌套类型无法经 typealias 转发），此处反向引用。 */
typealias Vendor = com.openminis.app.power.PowerOptimizationManager.Vendor

object OemPowerGates {
    private const val CATEGORY = "OemPowerGates"

    /** 是否已在用户的电池优化豁免名单里。M 之下恒 true（无此 API，系统也不激进 Doze）。 */
    fun isIgnoringBatteryOptimizations(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * 打开可授予豁免的系统设置页：Manifest 声明了对应权限时用
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（直达对话框），否则落
     * ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS（豁免列表页）。返回
     * 是否真的拉起了页面。
     */
    fun requestBatteryOptimizationExemption(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val target = Uri.parse("package:${activity.packageName}")
        // Manifest 声明了对应权限时直达对话框；否则落系统豁免列表页。
        val pages = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(target) to
                "battery-opt direct dialog launched",
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) to
                "battery-opt list page launched (fallback)",
        )
        for ((page, note) in pages) {
            if (launch(activity, page)) { RunLog.info(CATEGORY, note); return true }
        }
        RunLog.warning(CATEGORY, "no battery-opt settings page available on this device")
        return false
    }

    fun currentVendor(): Vendor = com.openminis.app.power.PowerOptimizationManager.Vendor.current()

    /**
     * 设备出自已知会叠自启限制的厂商（用户几乎必然要去授那个 OEM 权限，
     * 后台才保得住）。OTHER（Pixel / 通用 AOSP 等）返回 false——原生
     * 前台服务保证已足够。
     */
    fun needsOemAutostartGuidance(): Boolean = Vendor.current() != Vendor.OTHER

    /**
     * 打开 OEM 自启/后台运行设置页：按厂商试一组已知组件名。任一成功
     * 返回 true；全败返回 false，调用方应回落 [openAppDetailsSettings]。
     */
    fun openOemAutostartSettings(activity: Activity): Boolean {
        val entries = autostartEntries[Vendor.current()].orEmpty()
        for (component in entries) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(activity, intent)) {
                RunLog.info(CATEGORY, "OEM autostart settings launched: ${component.flattenToShortString()}")
                return true
            }
        }
        RunLog.warning(CATEGORY, "no OEM autostart activity matched on ${Build.MANUFACTURER}")
        return false
    }

    private fun component(pkg: String, entry: String) = ComponentName(pkg, entry)

    /** 各厂商自启设置页的已知入口（冻结面：拼写错一个就打不开对应页面）。 */
    private val autostartEntries: Map<Vendor, List<ComponentName>> = mapOf(
        // MIUI 12+ 与旧 MIUI 兜底
        Vendor.XIAOMI to listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        ),
        // EMUI 9+ / HarmonyOS
        Vendor.HUAWEI to listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        // ColorOS 7+ / OxygenOS / RealmeUI
        Vendor.OPPO to listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        Vendor.VIVO to listOf(
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ),
        // OneUI 5+
        Vendor.SAMSUNG to listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ),
    )

    /** 系统应用详情页——最后的兜底。 */
    fun openAppDetailsSettings(activity: Activity) = launch(
        activity,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${activity.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun launch(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent); true
    } catch (e: SecurityException) {
        RunLog.warning(CATEGORY, "startActivity SecurityException: ${e.message}"); false
    } catch (_: ActivityNotFoundException) {
        false
    }
}
