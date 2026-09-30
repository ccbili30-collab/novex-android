@file:Suppress("unused")

package com.openminis.app.power

import android.app.Activity
import android.content.Context
import novex.android.powerguard.OemPowerGates

/**
 * 后台保活管道的旧路径门面（P3.5c 真重写收编）。
 *
 * 机制实现在 [OemPowerGates]：电池优化豁免的查询与申请、OEM 自启
 * 设置页的组件清单与逐个尝试。[Vendor] 枚举被设置页以
 * `PowerOptimizationManager.Vendor` 形态钉住，无法经 typealias 转发——
 * 正典留此、实现侧反向引用。各 OEM ComponentName 与显示名为冻结面。
 */
object PowerOptimizationManager {

    /** 按 Build.MANUFACTURER 归的厂商家族（粗分粒度：同家族共用安全中心包）。 */
    enum class Vendor(val displayName: String) {
        XIAOMI("Xiaomi / Redmi"),
        HUAWEI("Huawei / Honor"),
        OPPO("OPPO / OnePlus / Realme"),
        VIVO("Vivo / iQOO"),
        SAMSUNG("Samsung"),
        OTHER("Other"),
        ;

        companion object {
            fun current(): Vendor = OemPowerGates.currentVendor()
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        OemPowerGates.isIgnoringBatteryOptimizations(context)

    fun requestBatteryOptimizationExemption(activity: Activity): Boolean =
        OemPowerGates.requestBatteryOptimizationExemption(activity)

    fun needsOemAutostartGuidance(): Boolean = OemPowerGates.needsOemAutostartGuidance()

    fun openOemAutostartSettings(activity: Activity): Boolean =
        OemPowerGates.openOemAutostartSettings(activity)

    fun openAppDetailsSettings(activity: Activity): Boolean =
        OemPowerGates.openAppDetailsSettings(activity)
}
