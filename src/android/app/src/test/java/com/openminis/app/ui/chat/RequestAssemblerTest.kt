package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-request-assembler] PR 1 装配线单测：顺序唯一、行为等价基线、影子指纹。 */
class RequestAssemblerTest {

    private fun user(text: String, db: String? = null) =
        LLMMessage(LLMMessage.Role.USER, text, dbMessageId = db)

    private fun assistant(text: String, db: String? = null, vararg parts: AgentContentPart) =
        LLMMessage(LLMMessage.Role.ASSISTANT, text, dbMessageId = db, contentParts = parts.toList())

    @Test
    fun `assemble runs stages in the fixed order`() {
        val calls = mutableListOf<String>()
        val result = RequestAssembler.assemble(
            RequestAssembler.Inputs(
                scopedHistory = listOf(user("a", "u1"), user("b", "u2")),
                compactRebuild = { calls += "compact"; it },
                orphanRepair = { calls += "orphan"; it },
                retentionProject = { calls += "retention"; it },
                pureChat = { calls += "pureChat"; it },
                imageBudget = { calls += "imageBudget"; it },
                injections = { calls += "injections"; it },
            ),
        )
        // A1：顺序唯一——scope(入参)→compact→blank→orphan→snapshot→retention→pureChat→imageBudget→injections
        assertEquals(listOf("compact", "orphan", "retention", "pureChat", "imageBudget", "injections"), calls)
        assertEquals(2, result.injected.size)
    }

    @Test
    fun `blank messages are dropped by the blank filter`() {
        val history = listOf(
            user("a", "u1"),
            LLMMessage(LLMMessage.Role.ASSISTANT, ""), // blank，无部件
            user("b", "u2"),
        )
        val result = RequestAssembler.assemble(RequestAssembler.Inputs(scopedHistory = history))
        assertEquals(3, result.diagnostics.afterCompact)
        assertEquals(2, result.diagnostics.afterBlank)
        assertEquals(2, result.assembled.size)
    }

    @Test
    fun `snapshot mainline is orphan-repaired then prepended`() {
        val (use, _) = AgentContentPart.ToolUse("c1", "read_text_block", JSONObject()) to
            AgentContentPart.ToolResult("c1", "read_text_block", "ok")
        // 快照定格在崩溃窗口：只有 use 没有 result。
        val snapshotMainline = listOf(user("主线", "m1"), assistant("", "m2", use))
        var repaired: List<LLMMessage>? = null
        val result = RequestAssembler.assemble(
            RequestAssembler.Inputs(
                scopedHistory = listOf(user("侧边", "s1")),
                sideSnapshotMainline = snapshotMainline,
                orphanRepair = { history ->
                    repaired = history
                    // 模拟 dropOrphanedToolParts 的合成错误结果
                    if (history === snapshotMainline) history + LLMMessage(
                        LLMMessage.Role.USER, "",
                        contentParts = listOf(AgentContentPart.ToolResult("c1", "read_text_block", "interrupted", isError = true)),
                    ) else history
                },
            ),
        )
        // P2-2 结构化：快照段先过孤儿修复（修复函数收到的是原始快照段），再前拼。
        assertEquals(3, repaired?.size)
        assertEquals(4, result.assembled.size)
        assertEquals(3, result.diagnostics.snapshotPrepended)
    }

    @Test
    fun `fingerprint ignores bridge messages and image byte identity`() {
        val withImage = user("看图", "u1").copy(
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(4), "image/png")),
        )
        val sameStructureDifferentBytes = user("看图", "u1").copy(
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(9), "image/png")),
        )
        // 桥接消息（无 dbMessageId）不参与指纹。
        val bridge = assistant("(bridge)")
        assertEquals(
            RequestAssembler.fingerprint(listOf(withImage, bridge)),
            RequestAssembler.fingerprint(listOf(sameStructureDifferentBytes)),
        )
        // 内容真分歧必须可见。
        assertNotEquals(
            RequestAssembler.fingerprint(listOf(user("甲", "u1"))),
            RequestAssembler.fingerprint(listOf(user("乙", "u1"))),
        )
    }

    @Test
    fun `fingerprint detects tool pairing divergence`() {
        val (use, result) = AgentContentPart.ToolUse("c1", "read_text_block", JSONObject()) to
            AgentContentPart.ToolResult("c1", "read_text_block", "ok")
        val paired = listOf(user("读", "u1"), assistant("", "a1", use), LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "r1", contentParts = listOf(result)))
        val orphanOnly = listOf(user("读", "u1"), assistant("", "a1", use))
        assertNotEquals(RequestAssembler.fingerprint(paired), RequestAssembler.fingerprint(orphanOnly))
    }

    @Test
    fun `substantive matches the pipeline blank predicate`() {
        assertTrue(RequestAssembler.substantive(user("x", "u1")))
        assertEquals(
            1,
            listOf(user("a", "u1"), LLMMessage(LLMMessage.Role.ASSISTANT, ""))
                .count(RequestAssembler::substantive),
        )
    }

    @Test
    fun `diagnostics report stage counts end to end`() {
        val result = RequestAssembler.assemble(
            RequestAssembler.Inputs(
                scopedHistory = listOf(user("a", "u1"), LLMMessage(LLMMessage.Role.ASSISTANT, ""), user("b", "u2")),
                pureChat = { it },
                imageBudget = { it },
                injections = { it },
            ),
        )
        assertEquals(3, result.diagnostics.afterCompact)
        assertEquals(2, result.diagnostics.afterBlank)
        assertEquals(2, result.diagnostics.assembled)
        assertEquals(0, result.diagnostics.snapshotPrepended)
        assertEquals(2, result.diagnostics.afterInjections)
    }
}
