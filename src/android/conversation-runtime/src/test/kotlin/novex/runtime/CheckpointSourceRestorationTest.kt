package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CheckpointSourceRestorationTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `准备区域保留来源和模块编号不修改当前作品或权限且重开重复准备一致`() {
        val cards=CardStore(temp.newFolder().toPath());val ref=cards.contents.allocator()()
        cards.contents.receive(listOf(ContentTransfer(ContentRef("source"),ref))){"存档角色原文🌊".byteInputStream()}
        val role=ContentDocument("inside",CardKind.CHARACTER,"角色",listOf(ContentModule("manual","手动模块",listOf(ContentBlock.Text("text",ref)),use=ModuleUse.Manual)))
        val world=ContentDocument("world",CardKind.WORLD,"原世界",internalCharacters=listOf(role))
        cards.save(world,null,ChangeSource.HUMAN,"original")
        val store=ConversationCheckpoints(temp.newFolder().toPath());val session=ConversationSession("chat","对话",SourceSelection("world","inside"),CardKind.CHARACTER)
        val policy=CardToolPolicy(setOf(ManagementTarget("world","inside")),ToolPermission.APPROVAL)
        ConversationCheckpointWriter(store,cards,temp.newFolder().toPath()).save(session,policy,"save","山门"){emptyList()}
        cards.save(world.copy(name="后来的世界"),"original",ChangeSource.HUMAN,"later")
        val preparedRoot=temp.newFolder().toPath()
        val prepared=CheckpointSourceRestoration(preparedRoot,store).prepare("chat","save")
        assertEquals(session,prepared.session);assertEquals(policy,prepared.policy)
        val restored=CardStore(prepared.cardsDirectory);val card=restored.open("world")!!.content
        assertEquals("原世界",card.name);assertEquals("inside",card.internalCharacters.single().id)
        assertEquals("manual",card.internalCharacters.single().modules.single().id)
        val body=(card.internalCharacters.single().modules.single().blocks.single() as ContentBlock.Text).content
        assertNotEquals(ref,body);assertEquals("存档角色原文🌊",restored.contents.open(body).bufferedReader().use {it.readText()})
        assertEquals("later",cards.open("world")!!.revision);assertEquals("后来的世界",cards.open("world")!!.content.name)
        assertEquals(prepared,CheckpointSourceRestoration(preparedRoot,store).prepare("chat","save"))
        assertEquals(1L,Files.list(preparedRoot).use {paths->paths.filter {it.fileName.toString().startsWith("prepared-")}.count()})
        assertThrows(IllegalArgumentException::class.java){CheckpointSourceRestoration(preparedRoot,store).prepare("other","save")}
        Files.writeString(prepared.cardsDirectory.resolve("contents/batches").resolve(body.value),"损坏")
        assertThrows(java.io.IOException::class.java){CheckpointSourceRestoration(preparedRoot,store).prepare("chat","save")}
        assertEquals("later",cards.open("world")!!.revision)
    }
    @Test fun `后续作品缺包整组不发布且不留下半份准备内容`() {
        val cards=CardStore(temp.newFolder().toPath());cards.save(ContentDocument("role",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"first")
        val store=ConversationCheckpoints(temp.newFolder().toPath());val session=ConversationSession("chat","对话",SourceSelection("role"),CardKind.CHARACTER)
        val original=ConversationCheckpointWriter(store,cards,temp.newFolder().toPath()).save(session,CardToolPolicy(emptySet()),"original","原件"){emptyList()}
        val context=store.open("chat",original.id,"context").bufferedReader().use {JSONObject(it.readText())}
        context.getJSONArray("cards").put(JSONObject().put("root","missing").put("revision","first").put("section","cards/1/package"))
        store.save("chat","broken","缺包",listOf(CheckpointSection("context"){context.toString().byteInputStream()},CheckpointSection("cards/0/package"){store.open("chat",original.id,"cards/0/package")}))
        val directory=temp.newFolder().toPath()
        assertThrows(IllegalArgumentException::class.java){CheckpointSourceRestoration(directory,store).prepare("chat","broken")}
        assertEquals(listOf("prepare.lock"),Files.list(directory).use {it.map {path->path.fileName.toString()}.toList()})
        assertEquals("first",cards.open("role")!!.revision)
    }
}
