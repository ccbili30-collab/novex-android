package novex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [T-stage2-memory] 分区核算与水位刻度守护（总纲 §3.4/3.5）：
 * 分区算术（系统区/输出预留/对话区配额）、零配额降级为满水位、
 * 刻度高水位制（跨越触发、回落不重复、90 封顶、压缩后归零重数）。
 */
class MemoryWindowBudgetTest {

    private fun reading(window: Int, system: Int, history: Int) =
        MemoryWindowBudget.Reading(windowTokens = window, systemTokens = system, historyTokens = history)

    @Test fun `partitions split into system dialogue quota and output reserve`() {
        val r = reading(window = 1_000_000, system = 120_000, history = 200_000)
        assertEquals(50_000, r.outputReserveTokens)
        assertEquals(830_000, r.dialogueQuotaTokens)
        assertEquals(24, r.waterLevelPercent) // 200k/830k
    }

    @Test fun `zero quota degrades to full water level not divide-by-zero`() {
        val r = reading(window = 100_000, system = 99_000, history = 1)
        assertEquals(0, r.dialogueQuotaTokens)
        assertEquals(100, r.waterLevelPercent)
    }

    @Test fun `tick crosses only forward past high water`() {
        // 高水位 0：水位 10% 即触发第 10 档
        assertNotNull(MemoryWindowBudget.crossedMemoryTick(0, reading(1000, 0, 100)))
        // 高水位已 10：同样读数不再触发（回落/持平不重复）
        assertNull(MemoryWindowBudget.crossedMemoryTick(10, reading(1000, 0, 100)))
        // 水位 25% 且高水位 10 → 下一档 20 触发
        assertEquals(20, MemoryWindowBudget.crossedMemoryTick(10, reading(1000, 0, 250)))
        // 水位 95% 高水位 90 → 下一档 100 超过 90 上限不触发（压缩归 next 处理）
        assertNull(MemoryWindowBudget.crossedMemoryTick(90, reading(1000, 0, 950)))
        // 水位 8% 高水位 0 → 下一档 10 未达，不触发
        assertNull(MemoryWindowBudget.crossedMemoryTick(0, reading(1000, 0, 80)))
    }
}
