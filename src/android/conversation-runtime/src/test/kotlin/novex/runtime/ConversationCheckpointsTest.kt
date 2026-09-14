package novex.runtime

import novex.content.*
import novex.storage.*
import novex.conversation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class ConversationCheckpointsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `真实输入请求状态和快捷操作冻结后不随对话变化`() {
        val cards=CardStore(temp.newFolder().toPath());cards.save(ContentDocument("role",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"save")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards);sessions.create("chat","对话",SourceSelection("role"))
        val timeline=ConversationTimeline(temp.newFolder().toPath(),sessions,TurnJournal(temp.newFolder().toPath()))
        timeline.append("chat","one","不要概括我的原始话语🌊")
        val runs=TurnJournal(temp.newFolder().toPath());val ns="4:chat3:one"
        runs.enqueue(ns,"initial","原始请求：完整世界资料");runs.claim(ns,"initial");runs.prepared(ns,"initial",JSONObject().put("adopted","原始采用依据"))
        runs.finish(ns,"initial",JSONObject().put("kind","finished").put("reply","原始回复"))
        val state=ConversationStateStore(temp.newFolder().toPath());state.record("chat",StateEvent("state-one","one",listOf(StateUpdate("体力","72"))))
        val controls=ConversationControlStore(temp.newFolder().toPath());controls.register("chat",null,ControlRegistration("controls-one","one",listOf(ControlDefinition("view","状态",ControlBehavior.View()))))
        val directory=temp.newFolder().toPath();val checkpoints=ConversationCheckpoints(directory)
        val capture=ConversationCheckpointCapture(timeline,runs,controls,state)
        val saved=checkpoints.save("chat","checkpoint","山门",capture.sections("chat"))
        state.record("chat",StateEvent("state-two","one",listOf(StateUpdate("体力","20"))))
        timeline.append("chat","two","后续消息")
        val reopened=ConversationCheckpoints(directory);assertEquals(saved,reopened.read("chat","checkpoint"))
        fun text(key:String)=reopened.open("chat","checkpoint",key).bufferedReader().use {it.readText()}
        assertEquals("不要概括我的原始话语🌊",text("turn/one/input"))
        assertEquals("原始请求：完整世界资料",text("turn/one/run/initial/input"))
        assertEquals(72,JSONObject(text("state")).getJSONObject("values").getInt("体力"))
        assertEquals(1,JSONObject(text("timeline")).getJSONArray("turns").length())
        assertTrue(text("controls").contains("状态"));assertTrue(reopened.list("other").isEmpty())
    }
    @Test fun `失败不登记半份存档重试幂等且同名不同编号共存`() {
        val directory=temp.newFolder().toPath();val store=ConversationCheckpoints(directory)
        val good=CheckpointSection("original"){"原文".byteInputStream()}
        assertThrows(IOException::class.java){store.save("chat","bad","存档",listOf(good,CheckpointSection("missing"){throw IOException("读取中断")}))}
        assertTrue(store.list("chat").isEmpty());assertEquals(0L,Files.list(directory.resolve("contents/batches")).use {it.count()})
        val saved=store.save("chat","one","同名",listOf(good))
        assertEquals(saved,store.save("chat","one","同名",listOf(CheckpointSection("original"){error("重试不应再次读取")})))
        store.save("chat","two","同名",listOf(good));assertEquals(2,store.list("chat").size)
        assertThrows(IllegalArgumentException::class.java){store.open("other","one","original")}
    }
    @Test fun `来源回调可跨实例读取同库但不能嵌套保存`() {
        val directory=temp.newFolder().toPath();val store=ConversationCheckpoints(directory);val other=ConversationCheckpoints(directory)
        store.save("chat","one","原件",listOf(CheckpointSection("text"){"原文".byteInputStream()}))
        store.save("chat","copy","副本",listOf(CheckpointSection("text"){
            assertEquals(1,other.list("chat").size);other.open("chat","one","text")
        }))
        assertEquals("原文",store.open("chat","copy","text").bufferedReader().use {it.readText()})
        assertThrows(IllegalStateException::class.java){store.save("chat","outer","嵌套",listOf(CheckpointSection("text"){
            other.save("chat","inner","内部",listOf(CheckpointSection("text"){"不能保存".byteInputStream()}));"不能保存".byteInputStream()
        }))}
        assertEquals(setOf("one","copy"),store.list("chat").map {it.id}.toSet())
    }

}
