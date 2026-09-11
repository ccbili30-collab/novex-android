package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationTranscriptTest {
    @get:Rule val temp=TemporaryFolder()
    private fun timeline():ConversationTimeline {
        val cards=CardStore(temp.newFolder().toPath())
        cards.save(ContentDocument("role",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"save")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards)
        sessions.create("chat","互动",SourceSelection("role"))
        return ConversationTimeline(temp.newFolder().toPath(),sessions,TurnJournal(temp.newFolder().toPath()))
    }
    @Test fun `等待批准恢复到原消息且读取不重复执行或重复生成回复`() {
        val timeline=timeline();timeline.append("chat","turn","修改设定")
        val directory=temp.newFolder().toPath();val journal=TurnJournal(directory)
        val namespace="4:chat4:turn"
        val view=ConversationTranscript(timeline,journal)
        assertEquals(ReplyState.QUEUED,view.read("chat").single().state)
        journal.enqueue(namespace,"initial","请求意图");journal.claim(namespace,"initial")
        assertEquals(ReplyState.RUNNING,view.read("chat").single().state)
        journal.finish(namespace,"initial",JSONObject().put("kind","paused").put("version","approval"))
        assertEquals("approval",view.read("chat").single().pauseVersion)
        journal.enqueue(namespace,"approval","批准选项")
        assertEquals(ReplyState.PAUSED,view.read("chat").single().state)
        journal.claim(namespace,"approval")
        assertEquals(ReplyState.RUNNING,view.read("chat").single().state)
        journal.finish(namespace,"approval",JSONObject().put("kind","finished").put("requests",2).put("result",JSONObject().put("kind","reply").put("text","已保存这次修改。")))
        val reopened=ConversationTranscript(timeline,TurnJournal(directory))
        repeat(2){assertEquals(ConversationTurn("turn","修改设定",ReplyState.REPLIED,"已保存这次修改。",requests=2),reopened.read("chat").single())}
    }
    @Test fun `失败与部分回复保持状态而不伪造完整助手回复`() {
        val timeline=timeline();val journal=TurnJournal(temp.newFolder().toPath())
        fun finish(id:String,result:JSONObject) {
            timeline.append("chat",id,"输入 $id")
            val namespace="4:chat${id.length}:$id"
            journal.enqueue(namespace,"initial","意图");journal.claim(namespace,"initial")
            journal.finish(namespace,"initial",JSONObject().put("kind","finished").put("requests",1).put("result",result))
        }
        finish("failed",JSONObject().put("kind","network_failure"))
        finish("partial",JSONObject().put("kind","partial").put("reason","length").put("text","尚未说完"))
        val entries=ConversationTranscript(timeline,journal).read("chat")
        assertEquals(ReplyState.FAILED,entries[0].state);assertNull(entries[0].reply)
        assertEquals(ReplyState.PARTIAL,entries[1].state);assertEquals("尚未说完",entries[1].reply)
    }
    @Test fun `回复失败仍呈现已保存事实且恢复链不重复累计操作`() {
        val timeline=timeline();timeline.append("chat","turn","修改设定")
        val directory=temp.newFolder().toPath();val journal=TurnJournal(directory)
        val namespace="4:chat4:turn"
        val saved=JSONObject().put("call","write").put("result",JSONObject().put("status","saved").put("root_id","role").put("target_id","role").put("revision","actual"))
        fun finish(id:String,outcome:JSONObject) {
            journal.enqueue(namespace,id,id);journal.claim(namespace,id);journal.finish(namespace,id,outcome)
        }
        finish("initial",JSONObject().put("kind","paused").put("version","next").put("outcomes",org.json.JSONArray().put(saved)))
        val view=ConversationTranscript(timeline,TurnJournal(directory))
        assertEquals(1,view.read("chat").single().operations.size)
        journal.enqueue(namespace,"next","next");journal.claim(namespace,"next")
        assertEquals("actual",view.read("chat").single().operations.single().revision)
        journal.finish(namespace,"next",JSONObject().put("kind","finished").put("requests",2).put("result",JSONObject().put("kind","network_failure"))
            .put("outcomes",org.json.JSONArray().put(saved).put(JSONObject().put("call","denied").put("result",JSONObject().put("status","denied")))))
        repeat(2) {
            val result=view.read("chat").single()
            assertEquals(ReplyState.FAILED,result.state);assertNull(result.reply)
            assertEquals(listOf("saved","denied"),result.operations.map {it.status})
            assertEquals("role",result.operations.first().targetId)
        }
    }

}
