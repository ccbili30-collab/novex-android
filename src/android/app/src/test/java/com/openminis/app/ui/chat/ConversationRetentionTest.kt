package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationRetentionTest {
    private fun msg(i: Int, text: String = "原文$i") = LLMMessage(
        if(i % 2 == 0) LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT, text, dbMessageId = "$i")

    @Test fun `capacity retains original tail and repeated compression does not skip previously kept messages`() {
        val history = (0..19).map { msg(it) }
        val cut = ConversationRetention.cut(history, 0, 400) { 100 }!!
        assertEquals(16, cut.endExclusive)
        val rebuilt = ConversationRetention.rebuild(history, "16", "早期约定")!!
        assertFalse(rebuilt.any { it.dbMessageId == "15" })
        assertEquals((16..19).map { "$it" }, rebuilt.map { it.dbMessageId })
        assertTrue(rebuilt.first().content.contains("早期约定"))
        assertEquals("原文16", history[16].content)
        val grown = history + (20..29).map { msg(it) }
        val next = ConversationRetention.cut(grown, cut.endExclusive, 400) { 100 }!!
        assertEquals(16, next.start)
        assertEquals(26, next.endExclusive)
    }
    @Test fun `never cut a tool exchange or split a persisted row`() {
        val h = listOf(msg(0), msg(1).copy(contentParts = listOf(AgentContentPart.ToolUse("t", "read", JSONObject()))),
            msg(2).copy(contentParts = listOf(AgentContentPart.ToolResult("t", "read", "结果"))), msg(3), msg(4))
        val cut = ConversationRetention.cut(h, 0, 300) { 100 }!!
        assertEquals(1, cut.endExclusive)
        val same = h.map { it.copy(dbMessageId = "same") }
        assertNull(ConversationRetention.cut(same, 0, 100) { 100 })
    }
    @Test fun `small history stays intact and missing marker cannot fabricate context`() {
        assertNull(ConversationRetention.cut((0..3).map {msg(it)}, 0, 1000) {100})
        assertNull(ConversationRetention.rebuild(listOf(msg(0)), "missing", "摘要"))
    }
    @Test fun `readback returns paged unicode original and rejects inaccessible id`() {
        val text = "旅馆😀".repeat(1000)
        val h = listOf(msg(0, text))
        val search = JSONObject(ConversationRecall.execute("search_conversation_history", JSONObject().put("query", "旅馆"), h, 1000))
        assertEquals("0", search.getJSONArray("matches").getJSONObject(0).getString("message_id"))
        var offset = 0; val result = StringBuilder()
        do {
            val page = JSONObject(ConversationRecall.execute("read_conversation_history", JSONObject().put("message_id", "0").put("offset", offset), h, 500))
            result.append(page.getString("text"))
            offset = if(page.isNull("next_offset")) -1 else page.getInt("next_offset")
        } while(offset >= 0)
        assertEquals(ConversationCompactionPolicy.transcript(h), result.toString())
        assertThrows(IllegalArgumentException::class.java) {
            ConversationRecall.execute("read_conversation_history", JSONObject().put("message_id", "other"), h, 500)
        }
    }
    @Test fun `huge single message summary pages preserve every unicode character`() {
        val original = "早期约定😀\n".repeat(3000)
        val pages = ConversationCompactionPolicy.pages(original, 500) { it.codePointCount(0, it.length) }.toList()
        assertTrue(pages.size > 8)
        assertEquals(original, pages.joinToString(""))
        assertTrue(pages.all { it.codePointCount(0, it.length) <= 500 })
    }
    @Test fun `transcript does not duplicate text represented in both message fields`() {
        val h = listOf(msg(0, "事实").copy(contentParts = listOf(AgentContentPart.Text("事实"))))
        assertEquals("user: 事实\n", ConversationCompactionPolicy.transcript(h))
    }
}
