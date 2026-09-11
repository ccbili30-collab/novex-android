package novex.runtime

import novex.content.*
import novex.storage.*
import novex.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardTextRangeToolTest {
    @get:Rule val temporary=TemporaryFolder()
    private lateinit var storePath:java.nio.file.Path
    private fun fixture():CardStore {
        storePath=temporary.newFolder().toPath()
        val store=CardStore(storePath);val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("source"),ref))){"前😀后\n".byteInputStream()}
        val role=ContentDocument("role",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",listOf(ContentBlock.Text("b",ref)),listOf("标签"),ModuleUse.Always)))
        store.save(ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(role)),null,ChangeSource.HUMAN,"initial")
        return store
    }
    private fun readPage(store:CardStore,policy:CardToolPolicy):JSONObject {
        val reader=CardToolReader(store)
        val args=JSONObject().put("root_id","world").put("target_id","role")
        val metadata=reader.read(PendingTool("read","read_card",args.toString()),policy)
        return reader.read(PendingTool("page","read_text_block",args.put("draft_version",metadata.getString("draft_version"))
            .put("module_id","m").put("block_id","b").put("offset",1).put("count",1).toString()),policy)
    }
    private fun edit(page:JSONObject,id:String="change")=PendingTool(id,"replace_text_range",JSONObject()
        .put("root_id","world").put("target_id","role").put("draft_version",page.getString("draft_version"))
        .put("module_id","m").put("block_id","b").put("content_ref",page.getString("content_ref"))
        .put("start",page.getLong("offset")).put("end",page.getLong("end_offset")).put("text","新的🌍").toString())
    private fun text(store:CardStore):String {
        val ref=(store.open("world")!!.content.internalCharacters.single().modules.single().blocks.single() as ContentBlock.Text).content
        return store.contents.open(ref).bufferedReader().use {it.readText()}
    }

    @Test fun `读取的表情码点范围经工具回合保存且前后文规则保留`() {
        val store=fixture();val old=store.open("world")!!
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")))
        val page=readPage(store,policy);assertEquals("😀",page.getString("text"));assertEquals(2L,page.getLong("end_offset"))
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val round=CardToolRound(coordinator,CardToolReader(store))
        val call=edit(page)
        val request=TextRequest("model",listOf(WireMessage("user","修改这个表情")),512,CardToolProtocol.definitions(policy))
        val response=round.consume("chat",request,ModelResult.ToolsRequested("",listOf(call)),policy) as ToolRoundResult.Continue
        val receipt=response.outcomes.single().result as CardToolResult.Saved
        assertEquals("role",receipt.targetId);assertEquals(store.open("world")!!.revision,receipt.revision)
        assertEquals("前新的🌍后\n",text(store))
        val module=store.open("world")!!.content.internalCharacters.single().modules.single()
        assertEquals(listOf("标签"),module.tags);assertEquals(ModuleUse.Always,module.use);assertEquals("b",module.blocks.single().id)
        assertEquals(old.content.modules,store.open("world")!!.content.modules)
        val wire=JSONObject(response.request.encode()).getJSONArray("messages")
        assertEquals("change",wire.getJSONObject(wire.length()-1).getString("tool_call_id"))
        assertEquals("saved",JSONObject(wire.getJSONObject(wire.length()-1).getString("content")).getString("status"))
    }

    @Test fun `批准勾选不执行重开后确认且重试不重复保存`() {
        val store=fixture();val old=store.open("world")!!
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")),ToolPermission.APPROVAL)
        val request=CardToolProtocol.parse("chat",edit(readPage(store,policy)))
        val root=temporary.newFolder().toPath();val coordinator=CardToolCoordinator(store,TurnJournal(root))
        assertEquals(CardToolResult.AwaitingApproval,coordinator.submit(request,policy))
        coordinator.select(request,true);assertEquals(old,store.open("world"));assertNull(CardDrafts(store).read("world"))
        val reopened=CardToolCoordinator(CardStore(storePath),TurnJournal(root))
        val saved=reopened.confirm(request,policy) as CardToolResult.Saved
        assertEquals(saved,reopened.submit(request,policy));assertEquals(saved.revision,store.open("world")!!.revision)
        assertEquals("前新的🌍后\n",text(store))
    }

    @Test fun `拒绝只读过期正文和不精确范围参数且不改正式版本`() {
        val store=fixture();val old=store.open("world")!!
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")))
        val call=edit(readPage(store,policy));val request=CardToolProtocol.parse("chat",call)
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val readOnly=policy.copy(permission=ToolPermission.READ_ONLY)
        assertTrue(CardToolProtocol.definitions(readOnly).isEmpty())
        assertEquals(CardToolResult.Denied,coordinator.submit(request,readOnly))
        for(invalid in listOf<Any>("1",1.5,-1,java.math.BigInteger("9223372036854775808"))) {
            assertThrows(IllegalArgumentException::class.java){CardToolProtocol.parse("chat",call.copy(arguments=JSONObject(call.arguments).put("start",invalid).toString()))}
        }
        assertThrows(IllegalArgumentException::class.java){CardToolProtocol.parse("chat",call.copy(arguments=JSONObject(call.arguments).put("permission","free").toString()))}
        val stale=call.copy(arguments=JSONObject(call.arguments).put("content_ref","different/reference").toString())
        assertTrue(coordinator.submit(CardToolProtocol.parse("chat",stale),policy) is CardToolResult.Failed)
        assertEquals(old,store.open("world"));assertEquals(old.content,CardDrafts(store).read("world")!!.content)
        assertEquals("前😀后\n",text(store))
    }
}
