package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * `NavController.navigate` 的生命周期守卫包装（血统清剿 P3.7 就地真重写；
 * RESUMED/STARTED 守卫语义为行为冻结面）：当前 `NavBackStackEntry` 的
 * lifecycle 不在 `RESUMED` 时直接丢弃调用。
 *
 * Android Navigation 有个陈年毛刺：用户先点了一个弹栈按钮、又**立刻**点
 * 另一个按钮（比如「返回」接「设置」），第二次 `navigate` 落在源目的地还
 * 在半途弹出的当口——新目的地进了栈却没有 UI 渲染（被弹屏幕的 Composition
 * 已销毁，新屏幕因 ViewTree 在一帧内经历两次状态切换而彻底糊涂，不再装
 * 载）。用户看到一张白屏，唯一恢复办法是再按一次返回。
 *
 * Compose Multiplatform 文档与 Now-In-Android 样例给的是同一个修法：只
 * 允许 lifecycle 处于 RESUMED（完全上屏且稳定）的目的地发起 `navigate`；
 * 其余一律视为过渡窗口，丢弃调用。
 *
 * 用法：
 *
 *   onSettingsClick = { navController.safeNavigate(Routes.SETTINGS) }
 *
 * `popBackStack` 经 `safePopBackStack` 镜了同款守卫。
 */
fun NavController.safeNavigate(
    route: String,
    builder: (NavOptionsBuilder.() -> Unit)? = null,
) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    if (builder == null) navigate(route) else navigate(route, builder)
}

fun NavController.safePopBackStack(): Boolean {
    // 用户触发的弹栈（顶栏返回按钮）在瞬态过渡里绝不能被无声吞掉。
    // RESUMED 太严——ModalBottomSheet / Dialog 把 chat 的 NavBackStackEntry
    // 压到 STARTED 时，返回键会变成只闪一下涟漪的空转。MIUI 反馈报告的
    // Bug 3 正是这个案例。
    val state = currentBackStackEntry?.lifecycle?.currentState ?: return false
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return popBackStack()
}
