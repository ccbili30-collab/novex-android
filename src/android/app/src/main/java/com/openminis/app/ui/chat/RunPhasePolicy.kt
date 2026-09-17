package com.openminis.app.ui.chat

/**
 * [T-run-phase] PR 3：状态机转换策略的纯函数抽取（测试墙 E1——审计判定逻辑
 * 可测化，ChatViewModel 只做记录不做判定）。
 *
 * 合法转换矩阵（PR2c 净眼审查确认的现实序列）：
 * - IDLE → STREAMING（新流入场：发送/重试/恢复/drain）
 * - STREAMING → IDLE（流正常结束且无可恢复断点）
 * - STREAMING → AWAITING_RESUME（流结束但有可恢复状态）
 * - AWAITING_RESUME → STREAMING（恢复）/ → IDLE（清空/重试收尾）
 * - 自环一律合法（fallback 轮换、重复声明不产生第二次转换）
 */
internal enum class RunPhase {
    IDLE, STREAMING, AWAITING_RESUME;

    fun isLegalTransitionTo(next: RunPhase): Boolean =
        this == next || when (this) {
            IDLE -> next == STREAMING
            STREAMING -> next == IDLE || next == AWAITING_RESUME
            AWAITING_RESUME -> next == STREAMING || next == IDLE
        }

    companion object {
        /**
         * 终点相位是否应被抑制（PR2c 净眼残留项：迟到 unwind 降级）。
         * 两种情形：清空进行中（clearChat 已置 IDLE，迟到尸体不得再改写）；
         * 或已被新流接管（PR2c 的 stale-job 判定在 ViewModel 层做身份比对，
         * 这里兜底相位层校验）。
         */
        fun shouldSuppressEndPhase(current: RunPhase, wipingInProgress: Boolean): Boolean =
            wipingInProgress || current == IDLE
    }
}
