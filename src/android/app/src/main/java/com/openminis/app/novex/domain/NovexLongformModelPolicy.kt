package com.openminis.app.novex.domain

enum class NovexLongformModelTier {
    UNKNOWN,
    LIMITED,
    STANDARD,
    EXTENDED,
}

data class NovexLongformModelStatus(
    val tier: NovexLongformModelTier,
    val effectiveWindowTokens: Int?,
    val moduleBudgetTokens: Int,
    val meetsMinimum: Boolean,
    val label: String,
    val guidance: String,
)

/**
 * Makes the selected model's real long-form boundary explicit.
 *
 * A small model is not blocked: Novex reduces structured recall through the shared budget and
 * tells the user that distillation will happen sooner. Unknown metadata is never promoted to a
 * verified 200K capability.
 */
object NovexLongformModelPolicy {
    const val MINIMUM_WINDOW_TOKENS = 200_000
    const val EXTENDED_WINDOW_TOKENS = 1_000_000

    fun evaluate(
        effectiveWindowTokens: Int?,
        occupiedTokens: Int = 0,
        reservedOutputTokens: Int = 16_000,
    ): NovexLongformModelStatus {
        require(occupiedTokens >= 0) { "现有上下文词元数不能为负数" }
        require(reservedOutputTokens >= 0) { "输出预留词元数不能为负数" }
        if (effectiveWindowTokens == null || effectiveWindowTokens <= 0) {
            return NovexLongformModelStatus(
                tier = NovexLongformModelTier.UNKNOWN,
                effectiveWindowTokens = effectiveWindowTokens,
                moduleBudgetTokens = 0,
                meetsMinimum = false,
                label = "能力未确认",
                guidance = "尚未确认当前模型的上下文上限，不能视为已支持 200K 长篇创作。",
            )
        }

        val budget = NovexContextBudgetPolicy.moduleBudget(
            effectiveWindowTokens = effectiveWindowTokens,
            occupiedTokens = occupiedTokens,
            reservedOutputTokens = reservedOutputTokens,
        )
        return when {
            effectiveWindowTokens >= EXTENDED_WINDOW_TOKENS -> NovexLongformModelStatus(
                tier = NovexLongformModelTier.EXTENDED,
                effectiveWindowTokens = effectiveWindowTokens,
                moduleBudgetTokens = budget,
                meetsMinimum = true,
                label = "1M 扩展长篇",
                guidance = "已达到 1M 扩展窗口；仍按模块检索，避免把无关资料整包发送。",
            )

            effectiveWindowTokens >= MINIMUM_WINDOW_TOKENS -> NovexLongformModelStatus(
                tier = NovexLongformModelTier.STANDARD,
                effectiveWindowTokens = effectiveWindowTokens,
                moduleBudgetTokens = budget,
                meetsMinimum = true,
                label = "200K 长篇",
                guidance = "已达到长篇创作最低建议窗口，资料仍按模块和当前路径精确引用。",
            )

            else -> NovexLongformModelStatus(
                tier = NovexLongformModelTier.LIMITED,
                effectiveWindowTokens = effectiveWindowTokens,
                moduleBudgetTokens = budget,
                meetsMinimum = false,
                label = "降级模式",
                guidance = "当前模型上下文不足 200K；Novex 会缩小资料召回范围并更早蒸馏，超长创作连续性需要额外核对。",
            )
        }
    }
}
