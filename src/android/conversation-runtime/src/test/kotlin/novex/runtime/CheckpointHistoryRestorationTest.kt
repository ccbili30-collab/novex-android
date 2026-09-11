package novex.runtime

import novex.content.*
import novex.conversation.*
import novex.storage.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CheckpointHistoryRestorationTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `原始输入与完整及未完成执行链还原后由共同历史读取不变造状态`() {
        val cards=CardStore(temp.newFolder().toPath());cards.save(ContentDocument("role",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"first")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards);val session=sessions.create("chat","对话",SourceSelection("role"))
        val timeline=ConversationTimeline(temp.newFolder().toPath(),sessions,TurnJournal(temp.newFolder().toPath()))
        val original="原始输入🌊\n".repeat(100000);timeline.append("chat","one",original);timeline.append("chat","two","未完成输入")
        val runs=TurnJournal(temp.newFolder().toPath())
        val first="4:chat3:one";runs.enqueue(first,"initial","原请求")
        runs.claim(first,"initial");runs.prepared(first,"initial",JSONObject().put("adopted","真实采用"))
        runs.finish(first,"initial",JSONObject().put("kind","finished").put("requests",1).put("result",JSONObject().put("kind","reply").put("text","原始回答")))
        val second="4:chat3:two";runs.enqueue(second,"initial","尚未完成的请求");runs.claim(second,"initial")
        val store=ConversationCheckpoints(temp.newFolder().toPath())
        val states=ConversationStateStore(temp.newFolder().toPath())
        states.record("chat",StateEvent("state-one","one",listOf(StateUpdate("体力","100"))))
        states.record("chat",StateEvent("state-two","two",listOf(StateUpdate("体力","72"),StateUpdate("开启","true"))))
        val controls=ConversationControlStore(temp.newFolder().toPath())
        val originalMenu=ControlRegistration("menu-one","one",listOf(ControlDefinition("go","旧动作",ControlBehavior.Action("向前"))))
        val firstMenu=controls.register("chat",null,originalMenu)
        val secondMenu=ControlRegistration("menu-two","two",listOf(ControlDefinition("go","已停用",ControlBehavior.Action("停止旧动作"),enabled=false),ControlDefinition("view","查看体力",ControlBehavior.View(listOf("体力")))))
        val menuVersion=controls.register("chat",firstMenu.version,secondMenu).version
        val capture=ConversationCheckpointCapture(timeline,runs,controls,states)
        ConversationCheckpointWriter(store,cards,temp.newFolder().toPath()).save(session,CardToolPolicy(emptySet()),"save","存档"){capture.sections("chat")}
        val sources=CheckpointSourceRestoration(temp.newFolder().toPath(),store).prepare("chat","save")
        val prepared=CheckpointHistoryRestoration(temp.newFolder().toPath(),store).prepare(sources)
        assertEquals(listOf("two"),prepared.unfinishedTurns);assertTrue(prepared.eventsRestored)
        val restoredStates=ConversationStateStore(prepared.directory.resolve("state"))
        assertEquals(states.event("chat","state-one"),restoredStates.event("chat","state-one"))
        assertEquals("100",restoredStates.view("chat",listOf("one")).single().valueJson)
        assertEquals("72",restoredStates.view("chat",listOf("one","two"),listOf("体力")).single().valueJson)
        val restoredControls=ConversationControlStore(prepared.directory.resolve("controls"))
        assertEquals(menuVersion,restoredControls.read("chat").version)
        assertEquals(secondMenu,restoredControls.registered("chat","menu-two"))
        assertEquals(originalMenu,restoredControls.registered("chat","menu-one"))
        assertEquals(listOf("view"),restoredControls.read("chat").controls.visible(listOf("one","two")).map {it.definition.key})
        assertEquals(ControlInvocation.SendInstruction("向前"),restoredControls.invoke(ControlHandle("chat","menu-one","go"),listOf("one")))
        assertThrows(IllegalArgumentException::class.java){restoredControls.invoke(ControlHandle("chat","menu-one","go"),listOf("one","two"))}
        val restoredSessions=ConversationSessions(prepared.directory.resolve("sessions"),CardStore(sources.cardsDirectory))
        val restoredTimeline=ConversationTimeline(prepared.directory.resolve("timeline"),restoredSessions,TurnJournal(prepared.directory.resolve("messages")))
        val restoredRuns=TurnJournal(prepared.directory.resolve("runs"))
        val history=ConversationTranscript(restoredTimeline,restoredRuns).read("chat")
        assertEquals(original,history[0].input);assertEquals("原始回答",history[0].reply);assertEquals(ReplyState.REPLIED,history[0].state)
        assertEquals(ReplyState.RUNNING,history[1].state);assertNull(history[1].reply)
        assertEquals("原请求",restoredRuns.text(restoredRuns.read(first,"initial")!!.input))
        assertEquals("真实采用",JSONObject(restoredRuns.text(restoredRuns.read(first,"initial")!!.trace!!)).getString("adopted"))
        assertEquals(TurnState.RUNNING,runs.read(second,"initial")!!.state)
        val originalSaved=store.read("chat","save")!!
        store.save("chat","older","早期存档",originalSaved.sections.keys.filter {it !in setOf("state-events","control-events")}.map {key->CheckpointSection(key){store.open("chat","save",key)}})
        val olderSources=CheckpointSourceRestoration(temp.newFolder().toPath(),store).prepare("chat","older")
        assertFalse(CheckpointHistoryRestoration(temp.newFolder().toPath(),store).prepare(olderSources).eventsRestored)
        store.save("chat","broken","损坏存档",originalSaved.sections.keys.map {key->CheckpointSection(key){
            if(key=="turn/two/run/initial/record")JSONObject().put("id","initial").put("state","FINISHED").put("selected",false).toString().byteInputStream()
            else store.open("chat","save",key)
        }})
        val brokenSources=CheckpointSourceRestoration(temp.newFolder().toPath(),store).prepare("chat","broken")
        val failedArea=temp.newFolder().toPath()
        assertThrows(IllegalArgumentException::class.java){CheckpointHistoryRestoration(failedArea,store).prepare(brokenSources)}
        assertEquals(0L,Files.list(failedArea).use {it.count()})
    }
    @Test fun `拒绝覆盖已有记录及缺少结果的完成状态`() {
        val journal=TurnJournal(temp.newFolder().toPath())
        journal.enqueue("chat","one","已有内容")
        assertThrows(IllegalStateException::class.java){journal.restoreSnapshot("chat","one",TurnState.QUEUED,false,mapOf("input" to {"不能覆盖".byteInputStream()}))}
        assertThrows(IllegalArgumentException::class.java){journal.restoreSnapshot("chat","two",TurnState.FINISHED,false,mapOf("input" to {"缺结果".byteInputStream()}))}
        assertEquals("已有内容",journal.text(journal.read("chat","one")!!.input));assertNull(journal.read("chat","two"))
    }
}
