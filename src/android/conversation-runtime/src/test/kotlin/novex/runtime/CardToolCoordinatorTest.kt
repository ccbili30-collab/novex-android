package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardToolCoordinatorTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun draft(store:CardStore)=CardDrafts(store).create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList()))))
    private fun request(version:String,id:String="call")=CardToolRequest("chat",id,ManagementTarget("card"),version,CardToolEdit.WriteText("m",null,"经历","模型补充的经历"))
    @Test fun `批准勾选重开均不执行只有确认保存真实对象`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=draft(store);val root=temporary.newFolder().toPath()
        val journal=TurnJournal(root);var coordinator=CardToolCoordinator(store,journal)
        val request=request(initial.version);val policy=CardToolPolicy(setOf(request.target),ToolPermission.APPROVAL)
        assertEquals(CardToolResult.AwaitingApproval,coordinator.submit(request,policy))
        assertEquals(CardToolResult.AwaitingApproval,coordinator.confirm(request,policy))
        coordinator.select(request,true)
        assertNull(store.open("card"));assertEquals(initial,CardDrafts(store).read("card"))
        coordinator=CardToolCoordinator(store,TurnJournal(root))
        val result=coordinator.confirm(request,policy) as CardToolResult.Saved
        val saved=store.open(result.rootId)!!
        assertEquals(result.revision,saved.revision);assertEquals(ChangeSource.AI,saved.source)
        assertEquals("模型补充的经历",store.contents.open((saved.content.modules.single().blocks.single() as ContentBlock.Text).content).bufferedReader().use { it.readText() })
        assertEquals(result,coordinator.confirm(request,policy));assertEquals(result.revision,store.open("card")!!.revision)
        store.acquireEdit("card","card","人工接续").close()
    }
    @Test fun `只读不提供工具且范围撤销后确认不能写入`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=draft(store);val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val request=request(initial.version);val read=CardToolPolicy(setOf(request.target),ToolPermission.READ_ONLY)
        assertTrue(coordinator.availableTools(read).isEmpty());assertEquals(CardToolResult.Denied,coordinator.submit(request,read))
        val approval=read.copy(permission=ToolPermission.APPROVAL)
        coordinator.submit(request,approval);coordinator.select(request,true)
        assertEquals(CardToolResult.Denied,coordinator.confirm(request,approval.copy(targets=emptySet())))
        assertEquals(initial,CardDrafts(store).read("card"));assertNull(store.open("card"))
    }
    @Test fun `自由默认直接保存且停止的操作不改变草稿`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=draft(store);val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val request=request(initial.version);val policy=CardToolPolicy(setOf(request.target))
        val stop=ToolStop();stop.stop()
        assertEquals(CardToolResult.Stopped(null),coordinator.submit(request,policy,stop));assertEquals(initial,CardDrafts(store).read("card"))
        assertTrue(coordinator.submit(request.copy(callId="new-call"),policy) is CardToolResult.Saved)
    }
    @Test fun `批准等待期间人工修改后旧版本确认不能覆盖且释放占用`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=draft(store);val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val request=request(initial.version);val policy=CardToolPolicy(setOf(request.target),ToolPermission.APPROVAL)
        coordinator.submit(request,policy);coordinator.select(request,true)
        val human=CardEditor(store).apply("card",initial.version,"card",EditorCommand.Rename("人工新名称"))
        assertTrue(coordinator.confirm(request,policy) is CardToolResult.Failed)
        assertEquals(human,CardDrafts(store).read("card"));store.acquireEdit("card","card","after-failure").close()
    }
    @Test fun `正式保存后未记录工具结果可按真实修订恢复而不重复写入`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=draft(store);val root=temporary.newFolder().toPath();val journal=TurnJournal(root)
        val request=request(initial.version);val policy=CardToolPolicy(setOf(request.target),ToolPermission.APPROVAL)
        CardToolCoordinator(store,journal).submit(request,policy);journal.claim("chat","call")
        val committed=store.acquireEdit("card","card","interrupted").use { lease ->
            val changed=CardEditor(store).apply("card",initial.version,"card",EditorCommand.WriteText("m",null,"经历","模型补充的经历",EditorPosition()),ChangeSource.AI,lease)
            journal.prepared("chat","call",JSONObject().put("commit",changed.version))
            CardDrafts(store).commit("card",changed.version,lease)
        }
        val recovered=CardToolCoordinator(store,TurnJournal(root)).submit(request,policy) as CardToolResult.Saved
        assertEquals(committed.revision,recovered.revision);assertEquals(committed.revision,store.open("card")!!.revision)
    }
    @Test fun `保存甲角色不发布乙草稿也不受乙占用阻断`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store)
        val world=ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(ContentDocument("a",CardKind.CHARACTER,"甲"),ContentDocument("b",CardKind.CHARACTER,"乙")))
        store.save(world,null,ChangeSource.HUMAN,"base")
        val start=drafts.begin("world")
        val pending=CardEditor(store).apply("world",start.version,"b",EditorCommand.Rename("乙的未保存修改"))
        val request=CardToolRequest("chat","call",ManagementTarget("world","a"),pending.version,CardToolEdit.Rename("甲的新名称"))
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        store.acquireEdit("world","b","其他编辑").use {
            val result=coordinator.submit(request,CardToolPolicy(setOf(request.target))) as CardToolResult.Saved
            val formal=store.open("world")!!
            assertEquals(result.revision,formal.revision)
            assertEquals(listOf("甲的新名称","乙"),formal.content.internalCharacters.map {it.name})
            val remainder=drafts.read("world")!!
            assertEquals(formal.revision,remainder.baseRevision)
            assertEquals(listOf("甲的新名称","乙的未保存修改"),remainder.content.internalCharacters.map {it.name})
            assertEquals(ChangeSource.HUMAN,remainder.source)
        }
        store.acquireEdit("world","a","人工处理保留稿").close()
    }

    @Test fun `拒绝已选操作后重开和自由重试都不能重新执行`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=draft(store)
        val root=temporary.newFolder().toPath();val journal=TurnJournal(root)
        val coordinator=CardToolCoordinator(store,journal);val request=request(initial.version)
        val policy=CardToolPolicy(setOf(request.target),ToolPermission.APPROVAL)
        coordinator.submit(request,policy);coordinator.select(request,true)
        assertEquals(CardToolResult.Denied,coordinator.reject(request))
        val reopened=CardToolCoordinator(store,TurnJournal(root))
        assertEquals(CardToolResult.Denied,reopened.reject(request))
        assertEquals(CardToolResult.Denied,reopened.submit(request,policy.copy(permission=ToolPermission.FREE)))
        assertEquals(initial,CardDrafts(store).read("card"));assertNull(store.open("card"))
        val running=request.copy(callId="running")
        coordinator.submit(running,policy);journal.claim(running.chatId,running.callId)
        assertEquals(CardToolResult.Unconfirmed,coordinator.reject(running))
        assertEquals(TurnState.RUNNING,journal.read(running.chatId,running.callId)!!.state)
    }

}
