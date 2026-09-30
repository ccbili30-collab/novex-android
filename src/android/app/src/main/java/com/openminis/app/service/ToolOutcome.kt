package com.openminis.app.service

/**
 * 一次工具调用的收尾定性。
 *
 * 会话循环在工具块落定（成功 / 抛错 / 超时 / 用户打断）时把该值交
 * 给 [SessionActivityTracker.clearToolRunning]；悬浮胶囊与前台通知
 * 再按值渲染对勾、叉号或干脆不出图形。[Unknown] 表示"没有可信信
 * 号"——宁可留白也不猜。
 *
 * 常量名与次序是可观察事实面（`values()`/`ordinal`/跨层 FQN 引用，
 * 含 UI 层的 `com.openminis.app.service.ToolOutcome.*` 直引），故枚
 * 举钉在此处不动，实现侧（novex.android.runtime）反向引用它。
 */
enum class ToolOutcome {
    Success,
    Error,
    Timeout,
    Cancelled,
    Unknown,
}
