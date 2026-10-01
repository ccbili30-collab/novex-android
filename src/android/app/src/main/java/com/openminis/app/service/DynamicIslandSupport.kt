package com.openminis.app.service

import android.content.Context
import novex.android.runtime.LiveUpdatesProbe

/**
 * 灵动岛（Android 16 Live Updates）能力探针的门面（P3.5b 重写）。
 * 实现在 [novex.android.runtime.LiveUpdatesProbe]；方法名是设置页
 * 以全限定名引用的冻结面，原名转发。
 */
object DynamicIslandSupport {

    @JvmStatic
    fun isDynamicIslandCapable(context: Context): Boolean =
        LiveUpdatesProbe.capable(context)

    @JvmStatic
    fun isDynamicIslandActive(context: Context, userEnabled: Boolean): Boolean =
        LiveUpdatesProbe.engaged(context, userEnabled)
}
