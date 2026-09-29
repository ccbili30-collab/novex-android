package com.openminis.app.ui.noven

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class NovenTab { HOME, SESSIONS, CREATE, MESSAGES, ME }

internal enum class NovenBackAction { ClearHomeSearch, GoHome, System }

/**
 * 根导航返回策略：首页搜索框有内容先清空；不在首页回首页；否则交给系统。
 */
internal fun novenBackAction(tab: NovenTab, homeQuery: String): NovenBackAction = when {
    tab == NovenTab.HOME && homeQuery.isNotBlank() -> NovenBackAction.ClearHomeSearch
    tab != NovenTab.HOME -> NovenBackAction.GoHome
    else -> NovenBackAction.System
}

/**
 * 会话日期分组的「整组一个容器」外观，靠每一行自己的形状拼出来：
 * 首行上角 14dp、末行下角 14dp、单行四角 14dp、中间行直角。
 */
internal fun novenSessionGroupRowShape(
    index: Int,
    count: Int,
    radius: Dp = 14.dp,
): RoundedCornerShape = RoundedCornerShape(
    topStart = if (index == 0) radius else 0.dp,
    topEnd = if (index == 0) radius else 0.dp,
    bottomStart = if (index == count - 1) radius else 0.dp,
    bottomEnd = if (index == count - 1) radius else 0.dp,
)
