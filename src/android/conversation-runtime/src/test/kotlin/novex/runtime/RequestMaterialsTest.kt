package novex.runtime

import novex.content.*
import novex.conversation.*
import novex.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class RequestMaterialsTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun `管理目录只包含明确目标且只读不提供工具目录`() {
        val store=CardStore(temporary.newFolder().toPath())
        val role=ContentDocument("role",CardKind.CHARACTER,"授权角色",listOf(module(store,"secret","不自动注入的私有正文",ModuleUse.Always)))
        val sibling=ContentDocument("sibling",CardKind.CHARACTER,"未授权同级人物")
        store.save(ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(role,sibling)),null,ChangeSource.HUMAN,"first")
        val materials=RequestMaterials(store)
        val settings=TurnSettings("test",ModelCapacity(64000),64000,1024,"普通讨论",emptyList(),setOf("role"),window,managementTargets=setOf(ManagementTarget("world","role")))
        val request=DialogueRequestBuilder(materials).prepare("整理角色",emptyList(),settings)
        val payload=request.request.encode()
        assertTrue(payload.contains("授权角色"));assertFalse(payload.contains("不自动注入的私有正文"));assertFalse(payload.contains("未授权同级人物"))
        val directory=request.trace.getJSONArray("management")
        assertEquals(1,directory.length());assertEquals("world",directory.getJSONObject(0).getString("root_id"));assertEquals("role",directory.getJSONObject(0).getString("target_id"))
        val readonly=DialogueRequestBuilder(materials).prepare("讨论",emptyList(),settings.copy(toolPermission=ToolPermission.READ_ONLY))
        assertEquals(0,readonly.trace.getJSONArray("management").length())
        assertFalse(readonly.request.encode().contains("授权角色"))
        val rootOnly=settings.copy(managedIds=setOf("world"),managementTargets=null)
        assertEquals(setOf(ManagementTarget("world")),rootOnly.effectiveManagementTargets)
        val cleared=rootOnly.copy(managedIds=emptySet())
        assertEquals(0,DialogueRequestBuilder(materials).prepare("讨论",emptyList(),cleared).trace.getJSONArray("management").length())
    }
    private val window=TriggerWindow(1,setOf(MessageRole.USER))
    private fun ref(store:CardStore,text:String):ContentRef {
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){ByteArrayInputStream(text.toByteArray())}
        return ref
    }
    private fun module(store:CardStore,id:String,text:String,use:ModuleUse)=ContentModule(id,id,
        listOf(ContentBlock.Text("$id-block",ref(store,text))),use=use)
    private fun read(service:RequestMaterials,input:RequestMaterialDraft)=input.texts.map { service.open(it).bufferedReader().use { it.readText() } }
    @Test fun `读取已保存正文去重且管理目录和未选内部角色不参与`() {
        val store=CardStore(temporary.newFolder().toPath())
        val role=ContentDocument("role",CardKind.CHARACTER,"船长",listOf(module(store,"role-module","船长秘密",ModuleUse.Always)))
        val world=ContentDocument("world",CardKind.WORLD,"港口",listOf(module(store,"world-module","港口背景",ModuleUse.Always)),internalCharacters=listOf(role))
        store.save(world,null,ChangeSource.HUMAN,"revision-1")
        val service=RequestMaterials(store)
        val input=service.prepare(listOf(SourceSelection("world"),SourceSelection("world")),setOf("不存在的管理对象"),emptyList(),window)
        assertEquals(listOf("港口背景"),read(service,input))
        val selected=service.prepare(listOf(SourceSelection("world","role")),emptySet(),emptyList(),window)
        assertEquals(listOf("船长秘密"),read(service,selected));assertEquals("role",selected.texts.single().cardId)
    }
    @Test fun `每轮获取最新正式修订而已经组装的请求仍读取一致旧版本`() {
        val store=CardStore(temporary.newFolder().toPath())
        val first=ContentDocument("role",CardKind.CHARACTER,"旅人",listOf(module(store,"m","旧经历",ModuleUse.Always)))
        store.save(first,null,ChangeSource.HUMAN,"one")
        val service=RequestMaterials(store)
        fun prepare()=service.prepare(listOf(SourceSelection("role")),emptySet(),emptyList(),window)
        val old=prepare()
        val second=first.copy(modules=listOf(module(store,"m","新经历",ModuleUse.Always)))
        store.save(second,"one",ChangeSource.HUMAN,"two")
        assertEquals(listOf("旧经历"),read(service,old))
        val latest=prepare();assertEquals(listOf("新经历"),read(service,latest));assertEquals("two",latest.texts.single().revision)
    }
    @Test fun `条件退出不读取正文且图片未发送状态与图注区分`() {
        val store=CardStore(temporary.newFolder().toPath())
        val caption=ref(store,"照片里的灯塔");val image=ref(store,"原图资源测试字节")
        val content=ContentDocument("role",CardKind.CHARACTER,"旅人",listOf(ContentModule("m","资料",
            listOf(ContentBlock.Image("image","asset",caption)),use=ModuleUse.Keywords(listOf("灯塔"),false,false))),
            resources=listOf(CardResource("asset",image,"image/png")))
        store.save(content,null,ChangeSource.HUMAN,"one")
        val service=RequestMaterials(store)
        val input=service.prepare(listOf(SourceSelection("role")),emptySet(),listOf(TriggerMessage("1",MessageRole.USER,"灯塔")),window)
        assertEquals(listOf("照片里的灯塔"),read(service,input));assertTrue(input.texts.single().caption)
        assertEquals("asset",input.imagesNotSent.single().resourceId)
        val next=service.prepare(listOf(SourceSelection("role")),emptySet(),listOf(TriggerMessage("2",MessageRole.USER,"森林")),window)
        assertTrue(next.texts.isEmpty());assertTrue(next.imagesNotSent.isEmpty())
    }
    @Test fun `缺失采用对象拒绝组装而不是跳过后继续发送`() {
        val service=RequestMaterials(CardStore(temporary.newFolder().toPath()))
        assertThrows(IllegalArgumentException::class.java){service.prepare(listOf(SourceSelection("missing")),emptySet(),emptyList(),window)}
    }
}
