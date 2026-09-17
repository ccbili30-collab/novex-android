package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** [T-presend-contract] PR 0 出口三断言单测（任务书不变量逐条对应）。 */
class PreSendContractTest {

    private fun user(text: String, db: String? = null) =
        LLMMessage(LLMMessage.Role.USER, text, dbMessageId = db)

    private fun assistant(text: String, vararg parts: AgentContentPart) =
        LLMMessage(LLMMessage.Role.ASSISTANT, text, contentParts = parts.toList())

    private fun toolPair(id: String): Pair<AgentContentPart.ToolUse, AgentContentPart.ToolResult> =
        AgentContentPart.ToolUse(id, "read_text_block", JSONObject()) to
            AgentContentPart.ToolResult(id, "read_text_block", "ok")

    // ── I1 历史守恒 ────────────────────────────────────────────────

    @Test
    fun `I1 passes when assembled matches expected`() {
        val history = listOf(user("你好", "u1"), assistant("在"), user("继续", "u2"), assistant("好"))
        assertNull(PreSendContract.historyConservation(history, expectedFromDb = 4, compactInProgress = false))
    }

    @Test
    fun `I1 passes when assembled exceeds expected mid tool loop`() {
        val history = listOf(user("你好", "u1"), assistant("在"), user("继续", "u2"), assistant("调用中"))
        assertNull(PreSendContract.historyConservation(history, expectedFromDb = 2, compactInProgress = false))
    }

    @Test
    fun `I1 violates when assembled loses history`() {
        // conv9 队列注入形状：DB 有完整活跃路径，内存只剩当前轮。
        val history = listOf(user("看这张图", "u9"))
        val violation = PreSendContract.historyConservation(history, expectedFromDb = 9, compactInProgress = false)
        assertNotNull(violation)
        assertEquals("I1", violation!!.invariant)
    }

    @Test
    fun `I1 exempts tiny conversations`() {
        val history = listOf(user("你好", "u1"))
        assertNull(PreSendContract.historyConservation(history, expectedFromDb = 1, compactInProgress = false))
    }

    @Test
    fun `I1 exempts compaction in progress`() {
        val history = listOf(user("压缩后新消息", "u2"))
        assertNull(PreSendContract.historyConservation(history, expectedFromDb = 9, compactInProgress = true))
    }

    @Test
    fun `I1 exempts unavailable baseline`() {
        assertNull(PreSendContract.historyConservation(listOf(user("x")), expectedFromDb = null, compactInProgress = false))
    }

    // ── I2 图片守恒 ────────────────────────────────────────────────

    @Test
    fun `I2 passes when image survives assembly`() {
        val withImage = user("看图", "u1").copy(
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(8), "image/png")),
        )
        assertNull(PreSendContract.imageConservation(listOf(withImage), listOf(withImage)))
    }

    @Test
    fun `I2 violates when last user image is dropped`() {
        val withImage = user("看图", "u1").copy(
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(8), "image/png")),
        )
        val stripped = user("看图", "u1")
        val violation = PreSendContract.imageConservation(listOf(withImage), listOf(stripped))
        assertNotNull(violation)
        assertEquals("I2", violation!!.invariant)
    }

    @Test
    fun `I2 ignores turns without user images`() {
        assertNull(PreSendContract.imageConservation(listOf(user("纯文本", "u1")), listOf(user("纯文本", "u1"))))
    }

    @Test
    fun `I2 accepts inline ImageData part`() {
        val withInline = user("看图", "u1").copy(
            contentParts = listOf(AgentContentPart.ImageData(ByteArray(8), "image/png")),
        )
        assertNull(PreSendContract.imageConservation(listOf(withInline), listOf(withInline)))
    }

    // ── I3 工具配对 ────────────────────────────────────────────────

    @Test
    fun `I3 passes for fully paired history`() {
        val (use, result) = toolPair("call-1")
        val history = listOf(
            user("读一下", "u1"),
            assistant("", use),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result)),
        )
        assertNull(PreSendContract.toolPairing(history))
    }

    @Test
    fun `I3 violates for unpaired tool use`() {
        val (use, _) = toolPair("call-1")
        val violation = PreSendContract.toolPairing(listOf(user("读一下", "u1"), assistant("", use)))
        assertNotNull(violation)
        assertEquals("I3", violation!!.invariant)
    }

    @Test
    fun `I3 violates for orphaned tool result`() {
        val (_, result) = toolPair("call-1")
        val violation = PreSendContract.toolPairing(
            listOf(LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result))),
        )
        assertNotNull(violation)
        assertEquals("I3", violation!!.invariant)
    }

    @Test
    fun `I3 passes for pure text history`() {
        assertNull(PreSendContract.toolPairing(listOf(user("你好", "u1"), assistant("在"))))
    }

    // ── 组合与计数基线 ──────────────────────────────────────────────

    @Test
    fun `firstViolation checks in order and stops at first`() {
        val history = listOf(user("看这张图", "u9"))
        val violation = PreSendContract.firstViolation(
            assembled = history,
            requestHistory = history,
            boundedHistory = history,
            expectedFromDb = 9,
            compactInProgress = false,
        )
        assertEquals("I1", violation!!.invariant)
    }

    @Test
    fun `substantiveCount mirrors pipeline blank filter`() {
        val blank = LLMMessage(LLMMessage.Role.ASSISTANT, "")
        val counted = listOf(user("a", "u1"), blank, user("b", "u2"))
        assertEquals(2, PreSendContract.substantiveCount(counted))
        // 带部件的空文本消息仍算实质消息（与 effectiveAgentHistory 过滤同构）。
        val partOnly = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.Text("x")))
        assertEquals(1, PreSendContract.substantiveCount(listOf(partOnly)))
    }
}
