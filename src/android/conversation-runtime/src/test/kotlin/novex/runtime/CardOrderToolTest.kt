package novex.runtime

import novex.content.*
import novex.storage.*
import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardOrderToolTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun fixture():CardStore {
        val store=CardStore(temporary.newFolder().toPath())
        fun ref(text:String):ContentRef {
            val ref=store.contents.allocator()();store.contents.receive(listOf(ContentTransfer(ContentRef("source"),ref))){text.byteInputStream()};return ref
        }
        val role=ContentDocument("role",CardKind.CHARACTER,"角色",listOf(
            ContentModule("a","图文",listOf(ContentBlock.Text("text",ref("完整正文")),ContentBlock.Image("image","resource",ref("图注"))),listOf("保留标签"),ModuleUse.Always),
            ContentModule("b","第二模块",emptyList())),resources=listOf(CardResource("resource",ref("owned-image-bytes"),"image/png")))
        store.save(ContentDocument("world",CardKind.WORLD,"世界",listOf(ContentModule("outside","世界模块",emptyList())),internalCharacters=listOf(role)),null,ChangeSource.HUMAN,"initial")
        return store
    }
    private fun call(store:CardStore,name:String,id:String="call",before:String=""):PendingTool {
        val args=JSONObject().put("root_id","world").put("target_id","role").put("draft_version","saved:${store.open("world")!!.revision}")
            .put("module_id","a").put("before_id",before)
        if(name=="move_content_block")args.put("block_id","image")
        return PendingTool(id,name,args.toString())
    }

    @Test fun `内部角色批准移动图片并自由移动模块保留对象资源与重复回执`() {
        val store=fixture();val old=store.open("world")!!.content
        val path=temporary.newFolder().toPath();val coordinator=CardToolCoordinator(store,TurnJournal(path))
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")),ToolPermission.APPROVAL)
        val request=CardToolProtocol.parse("chat",call(store,"move_content_block",before="text"))
        assertEquals(CardToolResult.AwaitingApproval,coordinator.submit(request,policy))
        coordinator.select(request,true);assertEquals(old,store.open("world")!!.content)
        val receipt=CardToolCoordinator(store,TurnJournal(path)).confirm(request,policy) as CardToolResult.Saved
        assertEquals(receipt,coordinator.submit(request,policy))
        val role=store.open("world")!!.content.internalCharacters.single()
        assertEquals(old.internalCharacters.single().modules[0].blocks.reversed(),role.modules[0].blocks)
        assertEquals(old.internalCharacters.single().resources,role.resources)
        val second=CardToolProtocol.parse("chat",call(store,"move_module","module-move"))
        assertTrue(coordinator.submit(second,policy.copy(permission=ToolPermission.FREE)) is CardToolResult.Saved)
        val saved=store.open("world")!!.content
        assertEquals(role.modules.reversed(),saved.internalCharacters.single().modules)
        assertEquals(old.modules,saved.modules)
        val archive=temporary.newFolder().toPath().resolve("card.zip");CardFiles(store).export("world",archive)
        val fresh=CardStore(temporary.newFolder().toPath());val draft=CardFiles(fresh).prepare(java.nio.file.Files.newInputStream(archive),"还原",CardKind.WORLD)
        val restored=CardDrafts(fresh).commit(draft.content.id,draft.version).content.internalCharacters.single()
        assertEquals(listOf("第二模块","图文"),restored.modules.map {it.name})
        assertTrue(restored.modules[1].blocks[0] is ContentBlock.Image)
        assertEquals(ModuleUse.Always,restored.modules[1].use)
        assertEquals(listOf("保留标签"),restored.modules[1].tags)
        assertEquals("owned-image-bytes",fresh.contents.open(restored.resources.single().content).bufferedReader().use {it.readText()})
    }

    @Test fun `跨卡定位版本过期未知字段与只读移动均不能改卡`() {
        val store=fixture();val old=store.open("world")!!
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")))
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val request=CardToolProtocol.parse("chat",call(store,"move_module",before="outside"))
        assertTrue(coordinator.submit(request,policy) is CardToolResult.Failed)
        assertEquals(old,store.open("world"))
        val parsed=CardToolProtocol.parse("chat",call(store,"move_content_block","read-only",before="text"))
        assertEquals(CardToolResult.Denied,coordinator.submit(parsed,policy.copy(permission=ToolPermission.READ_ONLY)))
        assertEquals(CardToolResult.Denied,coordinator.submit(parsed,policy.copy(targets=emptySet())))
        assertTrue(coordinator.submit(parsed.copy(callId="stale",draftVersion="saved:old"),policy) is CardToolResult.Failed)
        val wrong=call(store,"move_module","unknown")
        assertThrows(IllegalArgumentException::class.java){CardToolProtocol.parse("chat",wrong.copy(arguments=JSONObject(wrong.arguments).put("delete",true).toString()))}
        assertEquals(old,store.open("world"))
    }
}
