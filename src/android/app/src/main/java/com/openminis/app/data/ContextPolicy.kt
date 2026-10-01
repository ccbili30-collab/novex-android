package com.openminis.app.data

/**
 * 上下文压力纯逻辑策略（血统清剿 P3.7 就地真重写；构造字段名/枚举/阈值
 * 语义为消费方与测试依赖面，冻结）。
 *
 * 输入一个 token 估计值与模型上下文窗口，回答 agent 循环该走哪条路：
 * 大工具结果落盘（offload）、触发压缩/摘要（compact）、还是向 UI 报
 * 「上下文耗尽」。四档阈值按窗口规模取档，内嵌的没有任何摘要算法——真正的
 * 压缩与落盘执行在 agent 循环里，本结构只回答「现在处于什么状态」。
 *
 * 阈值刻意留出余量（天花板之下 10k/20k/40k），保证用户看到任何打断之前
 * 还塞得下再一整轮 agent 往返。
 */
data class ContextPolicy(
    /** 越过此 token 数，下一个工具结果改写磁盘。0 = 关闭。 */
    val offloadThreshold: Int,
    /** 落盘后把上下文收缩到的目标位（低于阈值）。 */
    val offloadTarget: Int,
    /** 越过此值触发压缩/摘要。0 = 关闭。 */
    val compactThreshold: Int,
    /** true 表示该档窗口太小不适合自动压缩，只会报 .exhausted。 */
    val exhaustedOnly: Boolean,
    /** UI 是否提供「立即压缩」按钮。 */
    val manualCompactAllowed: Boolean,
) {
    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    /**
     * 判定当前回合的 token 压力，优先级：
     *   1. 压缩可用且已越过 [compactThreshold] → NEEDS_COMPACT；
     *   2. 小窗档（[exhaustedOnly]）且越过落盘线或窗口九成 → EXHAUSTED；
     *   3. 其余 → OK。
     *
     * 注意：NEEDS_COMPACT 永远先判——EXHAUSTED 只可能出自 compactThreshold
     * 刻意为 0 的小窗档，循环侧见 EXHAUSTED 必须停手而不是「抢救式」压缩。
     */
    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        if (exhaustedOnly && estimatedTokens >= exhaustLine(contextWindow)) {
            return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    /** 下一个工具结果是否应落盘。 */
    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    /** 小窗档的耗尽线：有落盘线用落盘线，否则按窗口九成。 */
    private fun exhaustLine(contextWindow: Int): Int =
        if (offloadThreshold > 0) offloadThreshold else contextWindow * 9 / 10

    companion object {
        /**
         * 按窗口规模取档：
         *   - `<32K`    → 落盘/压缩全关；UI 引导用户新开聊天；
         *   - `32K–64K` → 仅落盘；耗尽线 = 窗口 − 10k；
         *   - `64K–128K`→ 落盘 + 压缩；压缩余量 10k；
         *   - `≥128K`   → 宽裕落盘 + 压缩；余量 20k。
         */
        fun forContextWindow(contextWindow: Int): ContextPolicy {
            val tier = tierFor(contextWindow)
            val offloadLine = if (tier.offloadEnabled) contextWindow - tier.offloadMargin else 0
            return ContextPolicy(
                offloadThreshold = offloadLine,
                offloadTarget = if (tier.offloadEnabled) contextWindow - tier.offloadTargetDepth else 0,
                compactThreshold = if (tier.compactCapable) contextWindow - tier.compactMargin else 0,
                exhaustedOnly = !tier.compactCapable,
                manualCompactAllowed = tier.manualCompact,
            )
        }

        /**
         * 档位参数：落盘使能与余量/落盘目标深度/压缩能力与余量/手动压缩按钮。
         * 最小档全部能力关闭，由 [forContextWindow] 统一钳到 0。
         */
        private class Tier(
            val offloadEnabled: Boolean,
            val offloadMargin: Int,
            val offloadTargetDepth: Int,
            val compactMargin: Int,
            val compactCapable: Boolean,
            val manualCompact: Boolean,
        )

        private fun tierFor(window: Int): Tier = when {
            window < 32_000 ->
                Tier(offloadEnabled = false, offloadMargin = 0, offloadTargetDepth = 0,
                    compactMargin = 0, compactCapable = false, manualCompact = false)
            window < 64_000 ->
                Tier(offloadEnabled = true, offloadMargin = 10_000, offloadTargetDepth = 15_000,
                    compactMargin = 0, compactCapable = false, manualCompact = true)
            window < 128_000 ->
                Tier(offloadEnabled = true, offloadMargin = 20_000, offloadTargetDepth = 30_000,
                    compactMargin = 10_000, compactCapable = true, manualCompact = true)
            else ->
                Tier(offloadEnabled = true, offloadMargin = 40_000, offloadTargetDepth = 60_000,
                    compactMargin = 20_000, compactCapable = true, manualCompact = true)
        }
    }
}
