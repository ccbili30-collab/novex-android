package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationTimelineTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `消息顺序重开保留且重试不重复追加或改写正文`() {
        val cards=CardStore(temp.newFolder().toPath())
        cards.save(ContentDocument("role",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"save")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards)
        sessions.create("chat","对话",SourceSelection("role"))
        sessions.create("other","其他对话",SourceSelection("role"))
        val journalRoot=temp.newFolder().toPath();val journal=TurnJournal(journalRoot)
        val root=temp.newFolder().toPath();val timeline=ConversationTimeline(root,sessions,journal)
        timeline.append("chat","z","第一条")
        timeline.append("chat","a","第二条")
        timeline.append("chat","z","第一条")
        timeline.append("other","z","另一段对话")
        assertThrows(IllegalArgumentException::class.java){timeline.append("chat","z","试图改写")}
        journal.claim("chat","z")
        journal.finish("chat","z",JSONObject().put("kind","network_failure"))
        val reopened=ConversationTimeline(root,sessions,TurnJournal(journalRoot))
        val turns=reopened.read("chat")
        assertEquals(listOf("z","a"),turns.map {it.id})
        assertEquals(listOf("第一条","第二条"),turns.map(reopened::input))
        assertEquals("network_failure",JSONObject(journal.text(turns.first().outcome!!)).getString("kind"))
        assertEquals(TurnState.QUEUED,turns.last().state)
        assertEquals("另一段对话",reopened.input(reopened.read("other").single()))
    }
    @Test fun `正文先保存顺序更新前中断可同编号恢复且不自动执行`() {
        val cards=CardStore(temp.newFolder().toPath())
        cards.save(ContentDocument("world",CardKind.WORLD,"世界"),null,ChangeSource.HUMAN,"save")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards)
        sessions.create("chat","对话",SourceSelection("world"))
        val journal=TurnJournal(temp.newFolder().toPath());val root=temp.newFolder().toPath()
        val timeline=ConversationTimeline(root,sessions,journal)
        assertThrows(IllegalStateException::class.java){timeline.append("chat","turn","恢复输入"){error("模拟中断")}}
        assertTrue(timeline.read("chat").isEmpty())
        assertEquals(TurnState.QUEUED,journal.read("chat","turn")!!.state)
        val reopened=ConversationTimeline(root,sessions,journal)
        reopened.append("chat","turn","恢复输入")
        assertEquals(listOf("turn"),reopened.read("chat").map {it.id})
        assertThrows(IllegalArgumentException::class.java){reopened.append("missing","turn","内容")}
        assertNull(journal.read("missing","turn"))
    }
}
