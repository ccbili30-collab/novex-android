package com.openminis.app.novex.domain

import kotlin.math.min

/** One shared retrieval budget across every mounted world, character and game module. */
object NovexContextBudgetPolicy {
    private const val MAX_MODULE_TOKENS = 128_000

    fun moduleBudget(
        effectiveWindowTokens: Int,
        occupiedTokens: Int,
        reservedOutputTokens: Int,
    ): Int {
        require(effectiveWindowTokens > 0) { "有效上下文窗口必须大于零" }
        require(occupiedTokens >= 0) { "现有上下文词元数不能为负数" }
        require(reservedOutputTokens >= 0) { "输出预留词元数不能为负数" }
        val available = (effectiveWindowTokens - occupiedTokens - reservedOutputTokens).coerceAtLeast(0)
        val proportional = (effectiveWindowTokens * 0.30).toInt()
        return min(available, min(proportional, MAX_MODULE_TOKENS))
    }
}
