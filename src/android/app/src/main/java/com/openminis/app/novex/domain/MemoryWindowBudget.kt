package com.openminis.app.novex.domain

/**
 * [T-stage2-memory] 窗口分区核算与水位刻度（总纲 §3.4/3.5）。
 *
 * 名义窗口 ≠ 可用窗口：系统区（system 提示、工具定义、常量模块重注入、
 * 压缩四件套）与输出预留都不属于游玩空间。水位刻度以**对话区配额**为
 * 分母（活性基数——系统区占用每轮重算，配额随之浮动）。
 *
 * 本阶段只做记账与触发；预算强制（系统区上限挤压、resize 协议）归
 * 阶段 3——不提前扩面。
 */
object MemoryWindowBudget {

    /** 输出预留占窗口的固定比例（与发送出口 limit/20 校验同源）。 */
    private const val OUTPUT_RESERVE_FRACTION = 20

    data class Reading(
        val windowTokens: Int,
        val systemTokens: Int,
        val historyTokens: Int,
    ) {
        val outputReserveTokens: Int = windowTokens / OUTPUT_RESERVE_FRACTION
        /** 对话区配额（活性基数）。 */
        val dialogueQuotaTokens: Int = (windowTokens - systemTokens - outputReserveTokens).coerceAtLeast(0)
        /** 水位百分比（配额为零视为满——降级为"总是触发"而非除零）。 */
        val waterLevelPercent: Int =
            if (dialogueQuotaTokens <= 0) 100 else historyTokens * 100 / dialogueQuotaTokens
    }

    /**
     * 刻度高水位制（总纲 §3.5）：新读数跨过了比已记录高水位更高的整十刻度
     * 即触发，并把高水位推到该刻度；回落不重复触发（防边界抖动与删消息
     * 回涨反复整理）。压缩后由调用方显式 resetTick 归零。
     */
    fun crossedMemoryTick(highWaterTickPercent: Int, reading: Reading): Int? {
        val current = reading.waterLevelPercent
        val next = ((highWaterTickPercent / 10) + 1) * 10
        return if (current >= next && next in 10..90) next else null
    }
}
