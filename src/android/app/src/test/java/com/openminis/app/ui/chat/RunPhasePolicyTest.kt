package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-run-phase] PR 3 测试墙：状态机转换矩阵（E1 纯 JVM）+ 装配线端到端场景矩阵。
 * 矩阵来源：PR2c 净眼审查确认的现实序列（含唯一非法合法序列 IDLE→AWAITING_RESUME）。
 */
class RunPhasePolicyTest {

    // ── 转换矩阵 ──────────────────────────────────────────────

    @Test
    fun `legal transitions match the audited reality`() {
        assertTrue(RunPhase.IDLE.isLegalTransitionTo(RunPhase.STREAMING))
        assertTrue(RunPhase.STREAMING.isLegalTransitionTo(RunPhase.IDLE))
        assertTrue(RunPhase.STREAMING.isLegalTransitionTo(RunPhase.AWAITING_RESUME))
        assertTrue(RunPhase.AWAITING_RESUME.isLegalTransitionTo(RunPhase.STREAMING))
        assertTrue(RunPhase.AWAITING_RESUME.isLegalTransitionTo(RunPhase.IDLE))
        // 自环一律合法（fallback 轮换 / 重复声明不产生第二次转换）。
        RunPhase.entries.forEach { assertTrue(it.isLegalTransitionTo(it)) }
    }

    @Test
    fun `idle to awaiting resume is the only flagged sequence`() {
        // cancelStream 后 clearChat 交叠的迟到 unwind——合法行为但审计标记，
        // PR3 执法化数据不得把它当缺陷。
        assertFalse(RunPhase.IDLE.isLegalTransitionTo(RunPhase.AWAITING_RESUME))
    }

    @Test
    fun `end phase suppression covers wipe overlap and idle takeover`() {
        // 清空进行中：迟到尸体不改写审计轨迹。
        assertTrue(RunPhase.shouldSuppressEndPhase(RunPhase.STREAMING, wipingInProgress = true))
        // clearChat 已把相位置 IDLE：后续迟到终点全部抑制。
        assertTrue(RunPhase.shouldSuppressEndPhase(RunPhase.IDLE, wipingInProgress = false))
        // 正常终点：流在跑（STREAMING）且无清空——照记。
        assertFalse(RunPhase.shouldSuppressEndPhase(RunPhase.STREAMING, wipingInProgress = false))
        assertFalse(RunPhase.shouldSuppressEndPhase(RunPhase.AWAITING_RESUME, wipingInProgress = false))
    }

    // ── 装配线端到端场景矩阵（九段全启用）────────────────────

    private fun user(text: String, db: String? = null) = LLMMessage(LLMMessage.Role.USER, text, dbMessageId = db)

    @Test
    fun `full pipeline composes every stage in the fixed order`() {
        val use = AgentContentPart.ToolUse("c1", "read_text_block", JSONObject())
        val result = AgentContentPart.ToolResult("c1", "read_text_block", "ok")
        val snapshotMainline = listOf(user("主线", "m1"))
        val calls = mutableListOf<String>()
        val out = RequestAssembler.assemble(
            RequestAssembler.Inputs(
                scopedHistory = listOf(
                    user("读", "u1"),
                    LLMMessage(LLMMessage.Role.ASSISTANT, "", dbMessageId = "a1", contentParts = listOf(use)),
                    LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "r1", contentParts = listOf(result)),
                    LLMMessage(LLMMessage.Role.ASSISTANT, ""), // blank → 过滤
                ),
                sideSnapshotMainline = snapshotMainline,
                compactRebuild = { calls += "compact"; it },
                orphanRepair = { calls += "orphan"; it },
                retentionProject = { calls += "retention"; it },
                pureChat = { calls += "pureChat"; it },
                imageBudget = { calls += "imageBudget"; it },
                injections = { calls += "injections"; it },
            ),
        )
        assertEquals(listOf("compact", "orphan", "retention", "pureChat", "imageBudget", "injections"), calls)
        // 快照前拼 + blank 过滤 + 工具对保留：1(快照) + 3(实质) = 4。
        assertEquals(4, out.assembled.size)
        assertEquals(out.assembled, out.request)
        assertEquals(out.request, out.bounded)
        assertEquals(out.bounded, out.injected)
        assertEquals(1, out.diagnostics.snapshotPrepended)
        assertEquals(1, out.diagnostics.afterCompact - out.diagnostics.afterBlank)
    }

    @Test
    fun `conv9 shape is refused by the I1 contract end to end`() {
        // 内存只剩当前轮、DB 基线完整 → I1 必拒（本重整的立身场景）。
        val memoryOnly = listOf(user("看这张图", "u9"))
        val violation = PreSendContract.firstViolation(
            assembled = memoryOnly,
            requestHistory = memoryOnly,
            boundedHistory = memoryOnly,
            expectedFromDb = 9,
            compactInProgress = false,
        )
        assertEquals("I1", violation!!.invariant)
        // 压缩进行中同形状豁免（日志不拦截）。
        assertNull(
            PreSendContract.firstViolation(
                assembled = memoryOnly,
                requestHistory = memoryOnly,
                boundedHistory = memoryOnly,
                expectedFromDb = 9,
                compactInProgress = true,
            ),
        )
    }
}
