package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-request-assembler] PR 1 装配线单测：顺序唯一、快照分段修复、两级影子指纹。 */
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
    fun `snapshot mainline goes through snapshotOrphanRepair then prepends`() {
        val use = AgentContentPart.ToolUse("c1", "read_text_block", JSONObject())
        // 快照定格在崩溃窗口：最后一条是持有未答 use 的 assistant。
        val snapshotMainline = listOf(user("主线", "m1"), assistant("", "m2", use))
        var snapshotRepairInput: List<LLMMessage>? = null
        var mainRepairCalls = 0
        val synthesized = LLMMessage(
            LLMMessage.Role.USER, "",
            contentParts = listOf(AgentContentPart.ToolResult("c1", "read_text_block", "interrupted", isError = true)),
        )
        val result = RequestAssembler.assemble(
            RequestAssembler.Inputs(
                scopedHistory = listOf(user("侧边", "s1")),
                sideSnapshotMainline = snapshotMainline,
                orphanRepair = { mainRepairCalls += 1; it },
                snapshotOrphanRepair = { segment ->
                    snapshotRepairInput = segment
                    segment + synthesized // 模拟 dropOrphanedToolParts(exemptTrailing=false) 的合成
                },
            ),
        )
        // 快照段进的是 snapshotOrphanRepair（收到原始 2 条快照段），修复产物前拼。
        assertEquals(2, snapshotRepairInput?.size)
        assertEquals(1, mainRepairCalls) // 主列表只过 orphanRepair 一次
        assertEquals(4, result.assembled.size)
        assertEquals(3, result.diagnostics.snapshotPrepended)
    }

    @Test
    fun `snapshot repair falls back to orphanRepair when not provided`() {
        var calls = 0
        val counter: (List<LLMMessage>) -> List<LLMMessage> = { calls += 1; it }
        RequestAssembler.assemble(
            RequestAssembler.Inputs(
                scopedHistory = listOf(user("侧边", "s1")),
                sideSnapshotMainline = listOf(user("主线", "m1")),
                orphanRepair = counter,
            ),
        )
        assertEquals(2, calls) // 主列表一次 + 快照段回退一次
    }

    @Test
    fun `structural ignores memory-only text parts and bridge messages`() {
        val toolHint = AgentContentPart.Text(RequestAssembler.MEMORY_ONLY_TEXT_PREFIXES.first() + "……)")
        val imageNote = AgentContentPart.Text("[attached image: /var/minis/x.png]")
        val withMemoryOnlyParts = listOf(
            user("读", "u1"),
            LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "r1", contentParts = listOf(
                AgentContentPart.ToolResult("c1", "t", "ok"), toolHint,
            )),
        )
        val withoutThem = listOf(
            user("读", "u1"),
            LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "r1", contentParts = listOf(
                AgentContentPart.ToolResult("c1", "t", "ok"),
            )),
        )
        // 内存专属 Text 部件不产生结构差异（净眼 P1-1b）。
        assertEquals(RequestAssembler.structural(withMemoryOnlyParts), RequestAssembler.structural(withoutThem))
        assertEquals(RequestAssembler.fingerprint(withMemoryOnlyParts), RequestAssembler.fingerprint(withoutThem))
        // 桥接消息（无 dbMessageId）不参与指纹。
        assertEquals(
            RequestAssembler.structural(listOf(user("甲", "u1"), assistant("(bridge)"))),
            RequestAssembler.structural(listOf(user("甲", "u1"))),
        )
    }

    @Test
    fun `structural is content-blind while fingerprint detects content divergence`() {
        val a = user("甲", "u1")
        val b = user("乙", "u1")
        // 内容不同：结构相同（弱信号），全量指纹不同（强校验）。
        assertEquals(RequestAssembler.structural(listOf(a)), RequestAssembler.structural(listOf(b)))
        assertNotEquals(RequestAssembler.fingerprint(listOf(a)), RequestAssembler.fingerprint(listOf(b)))
        // 真结构分歧：多/少一条消息、工具对缺失。
        assertNotEquals(
            RequestAssembler.structural(listOf(a, user("二", "u2"))),
            RequestAssembler.structural(listOf(a)),
        )
    }

    @Test
    fun `fingerprint detects tool pairing divergence`() {
        val use = AgentContentPart.ToolUse("c1", "read_text_block", JSONObject())
        val result = AgentContentPart.ToolResult("c1", "read_text_block", "ok")
        val paired = listOf(
            user("读", "u1"), assistant("", "a1", use),
            LLMMessage(LLMMessage.Role.USER, "", dbMessageId = "r1", contentParts = listOf(result)),
        )
        val orphanOnly = listOf(user("读", "u1"), assistant("", "a1", use))
        assertNotEquals(RequestAssembler.fingerprint(paired), RequestAssembler.fingerprint(orphanOnly))
        assertNotEquals(RequestAssembler.structural(paired), RequestAssembler.structural(orphanOnly))
    }

    @Test
    fun `fingerprint ignores image byte identity`() {
        val withImage = user("看图", "u1").copy(
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(4), "image/png")),
        )
        val sameStructureDifferentBytes = user("看图", "u1").copy(
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(9), "image/png")),
        )
        assertEquals(
            RequestAssembler.fingerprint(listOf(withImage)),
            RequestAssembler.fingerprint(listOf(sameStructureDifferentBytes)),
        )
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
