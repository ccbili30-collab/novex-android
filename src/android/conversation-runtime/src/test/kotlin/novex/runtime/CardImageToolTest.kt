package novex.runtime

import novex.content.*
import novex.storage.*
import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardImageToolTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun fixture():CardStore {
        val store=CardStore(temporary.newFolder().toPath())
        fun ref(text:String):ContentRef {val ref=store.contents.allocator()();store.contents.receive(listOf(ContentTransfer(ContentRef("source"),ref))){text.byteInputStream()};return ref}
        val blue=CardResource("blue",ref("blue-bytes"),"image/png");val red=CardResource("red",ref("red-bytes"),"image/png")
        val role=ContentDocument("role",CardKind.CHARACTER,"角色",listOf(ContentModule("m","图文",listOf(ContentBlock.Image("image","blue",ref("保留图注"))))),
            resources=listOf(blue,red,CardResource("note",ref("not-an-image"),"text/plain")),appearance=CardAppearance(coverResourceId="blue"))
        store.save(ContentDocument("world",CardKind.WORLD,"世界",resources=listOf(CardResource("outside",ref("outside"),"image/png")),internalCharacters=listOf(role)),null,ChangeSource.HUMAN,"initial")
        return store
    }
    private fun call(store:CardStore,name:String,resource:String,purpose:String="cover",id:String=name):PendingTool {
        val args=JSONObject().put("root_id","world").put("target_id","role").put("draft_version","saved:${store.open("world")!!.revision}").put("resource_id",resource)
        if(name=="set_card_image")args.put("purpose",purpose) else args.put("module_id","m").put("block_id","image")
        return PendingTool(id,name,args.toString())
    }
    @Test fun `资源目录只返回本卡图片并明确未看图`() {
        val store=fixture();val policy=CardToolPolicy(setOf(ManagementTarget("world","role")))
        val result=CardToolReader(store).read(PendingTool("read","read_card",JSONObject().put("root_id","world").put("target_id","role").toString()),policy)
        val resources=result.getJSONArray("resources")
        assertEquals(listOf("blue","red"),(0 until resources.length()).map {resources.getJSONObject(it).getString("id")})
        assertFalse(result.getBoolean("image_sent"));assertFalse(result.toString().contains("blue-bytes"))
        assertEquals("blue",result.getJSONObject("appearance").getString("cover_resource_id"))
        assertEquals("blue",result.getJSONArray("modules").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).getString("resource_id"))
    }
    @Test fun `批准换图保留图注封面旧资源随后独立设置封面头像并完整还原`() {
        val store=fixture();val old=store.open("world")!!;val role=old.content.internalCharacters.single()
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")),ToolPermission.APPROVAL)
        val request=CardToolProtocol.parse("chat",call(store,"replace_block_image","red"))
        assertEquals(CardToolResult.AwaitingApproval,coordinator.submit(request,policy));coordinator.select(request,true)
        assertEquals(old,store.open("world"))
        val receipt=coordinator.confirm(request,policy) as CardToolResult.Saved
        assertEquals(receipt,coordinator.submit(request,policy))
        val changed=store.open("world")!!.content.internalCharacters.single()
        val original=role.modules.single().blocks.single() as ContentBlock.Image
        assertEquals(original.copy(resourceId="red"),changed.modules.single().blocks.single())
        assertEquals(role.appearance,changed.appearance);assertEquals(role.resources,changed.resources)
        for(purpose in listOf("cover","avatar")) {
            val input=call(store,"set_card_image",if(purpose=="cover")"red" else "blue",purpose,purpose)
            assertTrue(coordinator.submit(CardToolProtocol.parse("chat",input),policy.copy(permission=ToolPermission.FREE)) is CardToolResult.Saved)
        }
        val final=store.open("world")!!.content
        assertEquals(CardAppearance("blue","red"),final.internalCharacters.single().appearance)
        assertEquals(old.content.resources,final.resources)
        val archive=temporary.newFolder().toPath().resolve("world.zip");CardFiles(store).export("world",archive)
        val fresh=CardStore(temporary.newFolder().toPath());val draft=CardFiles(fresh).prepare(java.nio.file.Files.newInputStream(archive),"还原",CardKind.WORLD)
        val restored=CardDrafts(fresh).commit(draft.content.id,draft.version).content.internalCharacters.single()
        fun bytes(id:String)=fresh.contents.open(restored.resources.single {it.id==id}.content).bufferedReader().use {it.readText()}
        assertEquals("red-bytes",bytes(restored.appearance.coverResourceId!!));assertEquals("blue-bytes",bytes(restored.appearance.avatarResourceId!!))
    }
    @Test fun `批准插入复用资源重试不重复展示且人工仍可编辑`() {
        val store=fixture();val old=store.open("world")!!
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")),ToolPermission.APPROVAL)
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val args=JSONObject().put("root_id","world").put("target_id","role").put("draft_version","saved:${old.revision}")
            .put("module_id","m").put("resource_id","red").put("after_id","image")
        val request=CardToolProtocol.parse("insert-chat",PendingTool("insert","insert_owned_image",args.toString()))
        assertEquals(CardToolResult.AwaitingApproval,coordinator.submit(request,policy));coordinator.select(request,true)
        assertEquals(old,store.open("world"))
        val receipt=coordinator.confirm(request,policy) as CardToolResult.Saved
        assertEquals(receipt,coordinator.confirm(request,policy));assertEquals(receipt,coordinator.submit(request,policy))
        val saved=store.open("world")!!;val role=saved.content.internalCharacters.single()
        assertEquals(old.content.internalCharacters.single().resources,role.resources)
        val blocks=role.modules.single().blocks;assertEquals(2,blocks.size)
        assertEquals(old.content.internalCharacters.single().modules.single().blocks.single(),blocks.first())
        val inserted=blocks.last() as ContentBlock.Image;assertEquals("red",inserted.resourceId);assertNotEquals("image",inserted.id)
        val draft=CardDrafts(store).begin("world")
        val edited=CardEditor(store).apply("world",draft.version,"role",EditorCommand.MoveBlock("m",inserted.id,"image"),ChangeSource.HUMAN)
        val final=CardDrafts(store).commit("world",edited.version)
        assertEquals(inserted,final.content.internalCharacters.single().modules.single().blocks.first())
        assertEquals(old.content.resources,final.content.resources)
    }
    @Test fun `插图拒绝错误模块定位和越权资源`() {
        val store=fixture();val old=store.open("world")!!
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")))
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        for((index,values) in listOf(Triple("m","outside","image"),Triple("m","note","image"),Triple("m","red","missing"),Triple("missing","red","")).withIndex()) {
            val args=JSONObject().put("root_id","world").put("target_id","role").put("draft_version","saved:${old.revision}")
                .put("module_id",values.first).put("resource_id",values.second).put("after_id",values.third)
            val request=CardToolProtocol.parse("bad",PendingTool("bad-$index","insert_owned_image",args.toString()))
            assertTrue(coordinator.submit(request,policy) is CardToolResult.Failed)
            assertEquals(old,store.open("world"))
        }
        assertTrue(CardToolProtocol.definitions(policy.copy(permission=ToolPermission.READ_ONLY)).isEmpty())
    }
    @Test fun `越权跨卡与非图片资源和未知用途均拒绝`() {
        val store=fixture();val old=store.open("world")!!
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val policy=CardToolPolicy(setOf(ManagementTarget("world","role")))
        for(name in listOf("set_card_image","replace_block_image"))for(resource in listOf("outside","note")) {
            assertTrue(coordinator.submit(CardToolProtocol.parse("chat",call(store,name,resource,id="$name-$resource")),policy) is CardToolResult.Failed)
            assertEquals(old,store.open("world"))
        }
        val good=CardToolProtocol.parse("chat",call(store,"set_card_image","red"))
        assertEquals(CardToolResult.Denied,coordinator.submit(good,policy.copy(permission=ToolPermission.READ_ONLY)))
        assertEquals(CardToolResult.Denied,coordinator.submit(good,policy.copy(targets=emptySet())))
        assertThrows(IllegalArgumentException::class.java){CardToolProtocol.parse("chat",call(store,"set_card_image","red","other"))}
        assertEquals(old,store.open("world"))
    }
}
