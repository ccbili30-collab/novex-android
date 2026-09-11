package novex.runtime

import novex.model.*
import novex.content.*
import novex.storage.*
import org.json.JSONObject
import novex.conversation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class PausedLoopStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private val budget=LoopBudget(ModelCapacity(128000,8192),128000,5)
    private val request=TextRequest("测试模型",listOf(WireMessage("user","原文\n保留 😀")),512)
    private val call=PendingTool("write","rename_card","{\"name\":\"新的名字\"}")
    private val saved=ToolOutcome(call,CardToolResult.Saved("card","card","revision-2"))
    @Test fun `重新打开保留批准暂停请求预算及两组结果`() {
        val directory=temporary.newFolder().toPath()
        val pending=ToolOutcome(call,CardToolResult.AwaitingApproval)
        val state=ToolLoopResult.Waiting(ToolRoundResult.Waiting(request,ModelResult.ToolsRequested("准备修改",listOf(call)),listOf(pending)),2,listOf(saved,pending))
        val written=PausedLoopStore(directory).save("chat","turn",null,budget,state)
        val restored=PausedLoopStore(directory).load("chat","turn")!!
        assertEquals(written,restored)
        assertEquals(state,restored.state)
        assertEquals(budget,restored.budget)
        assertNull(PausedLoopStore(directory).load("other","turn"))
    }
    @Test fun `预算暂停保留已保存结果和完整下一请求且相同正文不重复落盘`() {
        val directory=temporary.newFolder().toPath();val store=PausedLoopStore(directory)
        val next=request.copy(messages=request.messages+WireMessage("assistant","",listOf(call))+WireMessage("tool",CardToolProtocol.result(saved.result),toolCallId=call.id))
        val state=ToolLoopResult.BudgetReached(next,5,listOf(saved))
        val first=store.save("chat","turn",null,budget,state)
        fun batches()=Files.list(directory.resolve("contents/batches")).use { it.count() }
        val count=batches()
        val second=store.save("chat","turn",first.version,budget,state)
        assertNotEquals(first.version,second.version)
        assertEquals(state,PausedLoopStore(directory).load("chat","turn")!!.state)
        assertEquals(count,batches())
        assertThrows(LoopCheckpointConflict::class.java){store.save("chat","turn",first.version,budget,state)}
        assertEquals(second.version,store.load("chat","turn")!!.version)
    }
    @Test fun `缺失正文必须读取失败不能恢复为空请求`() {
        val directory=temporary.newFolder().toPath();val store=PausedLoopStore(directory)
        store.save("chat","turn",null,budget,ToolLoopResult.BudgetReached(request,5,emptyList()))
        val body=Files.walk(directory.resolve("contents/batches")).use { paths->paths.filter { it.fileName.toString()=="text" }.findFirst().get() }
        Files.delete(body)
        assertThrows(Exception::class.java){PausedLoopStore(directory).load("chat","turn")}
    }
    @Test fun `批准暂停重新打开确认后回送真实结果且不重复保存`() {
        val cards=temporary.newFolder().toPath();val journals=temporary.newFolder().toPath();val checkpoints=temporary.newFolder().toPath()
        val store=CardStore(cards)
        store.save(ContentDocument("card",CardKind.CHARACTER,"原名称"),null,ChangeSource.HUMAN,"initial")
        val policy=CardToolPolicy(setOf(ManagementTarget("card")),ToolPermission.APPROVAL)
        val original=request.copy(tools=CardToolProtocol.definitions(policy))
        val edit=PendingTool("write","rename_card",JSONObject().put("root_id","card").put("target_id","card").put("draft_version","saved:initial").put("name","确认后的名称").toString())
        val response=ModelResult.ToolsRequested("",listOf(edit))
        val namespace="4:chat4:turn"
        val waiting=CardToolRound(CardToolCoordinator(store,TurnJournal(journals)),CardToolReader(store)).consume(namespace,original,response,policy) as ToolRoundResult.Waiting
        PausedLoopStore(checkpoints).save("chat","turn",null,budget,ToolLoopResult.Waiting(waiting,1,waiting.outcomes))
        val reopened=CardStore(cards);val coordinator=CardToolCoordinator(reopened,TurnJournal(journals))
        val restored=PausedLoopStore(checkpoints).load("chat","turn")!!.state as ToolLoopResult.Waiting
        val command=CardToolProtocol.parse(namespace,restored.pending.response.calls.single())
        coordinator.select(command,true)
        assertEquals("原名称",reopened.open("card")!!.content.name)
        val savedResult=coordinator.confirm(command,policy) as CardToolResult.Saved
        val round=CardToolRound(coordinator,CardToolReader(reopened))
        repeat(2) {
            val continued=round.consume(namespace,restored.pending.original,restored.pending.response,policy) as ToolRoundResult.Continue
            assertEquals(savedResult,continued.outcomes.single().result)
            assertEquals(savedResult.revision,JSONObject(continued.request.messages.last().text).getString("revision"))
            assertEquals(savedResult.revision,reopened.open("card")!!.revision)
        }
        assertEquals("确认后的名称",reopened.open("card")!!.content.name)
    }
    @Test fun `没有明确暂停点不可登记为可恢复`() {
        val store=PausedLoopStore(temporary.newFolder().toPath())
        assertThrows(IllegalArgumentException::class.java){store.save("chat","turn",null,budget,ToolLoopResult.Finished(ModelResult.Cancelled,0,emptyList()))}
        assertNull(store.load("chat","turn"))
    }
}
