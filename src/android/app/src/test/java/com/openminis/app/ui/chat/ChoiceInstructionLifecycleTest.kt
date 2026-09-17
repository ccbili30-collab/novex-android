package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-choice-instruction-lifecycle] 测试墙：选项指令的寿命与语境标记。
 * 事故回归锚点＝2026-09-17 会话 297e156a——恢复提醒永久写进 agentHistory
 * 导致"只出选项不写正文"死循环。这里的每条用例都在钉一件事：指令只活
 * 在它该活的那一个请求里；玩家的选择永远自带语境。
 */
class ChoiceInstructionLifecycleTest {

    private fun user(text: String, vararg parts: AgentContentPart) =
        LLMMessage(LLMMessage.Role.USER, text, contentParts = parts.toList())

    private fun assistant(text: String) =
        LLMMessage(LLMMessage.Role.ASSISTANT, text)

    // ── 第一刀：恢复指令瞬态性 ────────────────────────────────

    @Test
    fun `null hint returns the same list untouched`() {
        val history = listOf(user("选项工具"), assistant("正文"))
        assertSame(history, ChoiceInstructionLifecycle.appendForcedChoiceHint(history, null))
    }

    @Test
    fun `hint lands only on the last user message of the request copy`() {
        val history = listOf(
            user("第一轮", AgentContentPart.Text("第一轮")),
            assistant("叙事"),
            user("选项工具", AgentContentPart.Text("选项工具")),
        )
        val out = ChoiceInstructionLifecycle.appendForcedChoiceHint(history, ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT)
        assertEquals(3, out.size)
        // 前两条原样（同实例），只有最后一条 user 是新副本。
        assertSame(history[0], out[0])
        assertSame(history[1], out[1])
        assertNotSame(history[2], out[2])
        val parts = out[2].contentParts
        assertEquals(2, parts.size)
        assertEquals("选项工具", (parts[0] as AgentContentPart.Text).text)
        assertEquals(
            ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT,
            (parts[1] as AgentContentPart.Text).text,
        )
    }

    @Test
    fun `agentHistory itself is never mutated - the poison regression guard`() {
        // 事故根因断言：旧实现把提醒永久写进内存历史。现在输入列表与
        // 其中每条消息在调用后必须原封不动。
        val original = user("选项工具", AgentContentPart.Text("选项工具"))
        val history = listOf(original)
        ChoiceInstructionLifecycle.appendForcedChoiceHint(history, ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT)
        assertEquals(1, original.contentParts.size)
        assertEquals(listOf<LLMMessage>(original), history)
    }

    @Test
    fun `no user message or empty history is a no-op`() {
        assertSame(
            listOf(assistant("只有助手")),
            ChoiceInstructionLifecycle.appendForcedChoiceHint(
                listOf(assistant("只有助手")), ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT,
            ),
        )
        assertSame(
            emptyList<LLMMessage>(),
            ChoiceInstructionLifecycle.appendForcedChoiceHint(
                emptyList(), ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT,
            ),
        )
    }

    @Test
    fun `recovery hint states its own expiry`() {
        // 措辞金丝雀：强制指令必须自带"仅此一条回复、随即过期"的声明，
        // 且不得再出现裸的"Do not write prose"式永久禁令措辞。
        assertTrue(ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT.startsWith("<system-reminder>"))
        assertTrue(ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT.endsWith("</system-reminder>"))
        assertTrue("expires" in ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT)
        assertTrue("this one reply only" in ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT)
    }

    @Test
    fun `loop regression - hint is gone from every subsequent request`() {
        // 事故形状复刻：第 N 轮触发恢复（本轮请求带提示），第 N+1 轮起
        // 任何请求都不得再含有该提示。
        val history = listOf(
            user("选项工具"),
            assistant("(被撤下的散文尝试)"),
        )
        val forcedRequest = ChoiceInstructionLifecycle.appendForcedChoiceHint(
            history, ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT,
        )
        assertTrue(forcedRequest.any { m ->
            m.contentParts.any { it is AgentContentPart.Text && it.text == ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT }
        })
        // 下一轮：取走即清零 → hint 为 null → 请求就是历史本身。
        val nextTurnHint: String? = null
        val nextRequest = ChoiceInstructionLifecycle.appendForcedChoiceHint(history + user("【最优】继续"), nextTurnHint)
        assertFalse(nextRequest.any { m ->
            m.contentParts.any { it is AgentContentPart.Text && "Do not write prose" in it.text }
        })
        assertFalse(nextRequest.any { m ->
            m.contentParts.any { it is AgentContentPart.Text && it.text == ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT }
        })
    }

    // ── 第二刀：选项回应语境标记 ──────────────────────────────

    @Test
    fun `selection marker is one well-formed reminder block`() {
        // UI 剥离依赖 <system-reminder>…</system-reminder> 的单块形状；
        // 嵌套或残缺会渲染进气泡或剥不干净。
        val marker = ChoiceInstructionLifecycle.SELECTION_RESPONSE_REMINDER
        assertTrue(marker.startsWith("<system-reminder>"))
        assertTrue(marker.endsWith("</system-reminder>"))
        assertEquals(1, Regex("<system-reminder>").findAll(marker).count())
        assertEquals(1, Regex("</system-reminder>").findAll(marker).count())
    }

    @Test
    fun `live card detection - assistant turn holding present_choices`() {
        assertTrue(
            ChoiceInstructionLifecycle.endsWithLiveChoicesCard(
                "assistant", listOf("register_controls", "present_choices"),
            ),
        )
        assertTrue(ChoiceInstructionLifecycle.endsWithLiveChoicesCard("assistant", listOf("present_choices")))
    }

    @Test
    fun `live card detection - anything else is not a card response`() {
        // 上一条是用户消息（已有人回应过卡）、纯正文助手回合、其它工具、
        // 空会话——都不挂标记。
        assertFalse(ChoiceInstructionLifecycle.endsWithLiveChoicesCard("user", listOf("present_choices")))
        assertFalse(ChoiceInstructionLifecycle.endsWithLiveChoicesCard("assistant", listOf("register_controls")))
        assertFalse(ChoiceInstructionLifecycle.endsWithLiveChoicesCard("assistant", emptyList()))
        assertFalse(ChoiceInstructionLifecycle.endsWithLiveChoicesCard(null, emptyList()))
    }

    @Test
    fun `marker survives as a plain text part - model sees it, content stays clean`() {
        // 标记只进 contentParts（模型与 DB 读这里），content 保持玩家原文
        // ——标题生成/摘要/latestVisibleUserRequest 等读 content 的消费方
        // 不受影响。
        val row = user("【最优】今夜派人盯郑家", AgentContentPart.Text("【最优】今夜派人盯郑家"))
        val marked = row.copy(
            contentParts = row.contentParts + AgentContentPart.Text(ChoiceInstructionLifecycle.SELECTION_RESPONSE_REMINDER),
        )
        assertEquals("【最优】今夜派人盯郑家", marked.content)
        assertTrue(marked.contentParts.any { it is AgentContentPart.Text && it.text == ChoiceInstructionLifecycle.SELECTION_RESPONSE_REMINDER })
    }
}
