package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class ConversationCheckpointWriterTest {
    @get:Rule val temp=TemporaryFolder()
    private fun StagedContentFiles.writeText(text:String):ContentRef {
        val ref=allocator()();receive(listOf(ContentTransfer(ContentRef("source"),ref))){text.byteInputStream()};return ref
    }
    private fun StagedContentFiles.readText(ref:ContentRef)=open(ref).bufferedReader().use {it.readText()}
    @Test fun `世界内部角色完整原包和独立管理卡冻结且无需源库即可重新解析`() {
        val source=temp.newFolder().toPath();val cards=CardStore(source)
        val body=cards.contents.writeText("完整原文🌊".repeat(10000))
        val image=cards.contents.writeText("图片原始字节")
        val extension=cards.contents.writeText("未知扩展原文")
        val role=ContentDocument("inside",CardKind.CHARACTER,"内部角色",listOf(ContentModule("module","",listOf(ContentBlock.Text("text",body),ContentBlock.Image("picture","asset")))),
            resources=listOf(CardResource("asset",image,"image/png")),extensions=mapOf("external" to extension),appearance=CardAppearance("asset","asset"))
        val world=ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(role))
        cards.save(world,null,ChangeSource.HUMAN,"v1")
        cards.save(ContentDocument("managed",CardKind.CHARACTER,"只管理"),null,ChangeSource.HUMAN,"v2")
        val store=ConversationCheckpoints(temp.newFolder().toPath());val scratch=temp.newFolder().toPath()
        val writer=ConversationCheckpointWriter(store,cards,scratch)
        val session=ConversationSession("chat","对话",SourceSelection("world","inside"),CardKind.CHARACTER)
        val policy=CardToolPolicy(setOf(ManagementTarget("managed"),ManagementTarget("world","inside")),ToolPermission.APPROVAL)
        val saved=writer.save(session,policy,"save","出发前"){listOf(CheckpointSection("input-draft"){"未发送".byteInputStream()})}
        assertEquals(0L,Files.list(scratch).use {it.count()})
        // 删除测试源库，避免误用原文件路径证明“可恢复”。
        Files.walk(source).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.delete(it)}}
        assertEquals(saved,writer.save(session,policy,"save","出发前"){error("重试不重新捕获")})
        val context=store.open("chat","save","context").bufferedReader().use {JSONObject(it.readText())}
        assertEquals("inside",context.getJSONObject("session").getString("target"))
        assertEquals("APPROVAL",context.getJSONObject("management").getString("permission"))
        val entries=context.getJSONArray("cards");assertEquals(2,entries.length())
        val entry=(0 until entries.length()).map {entries.getJSONObject(it)}.single {it.getString("root")=="world"}
        val zip=temp.root.toPath().resolve("restored.zip")
        store.open("chat","save",entry.getString("section")).use {Files.copy(it,zip)}
        val destination=StagedContentFiles(temp.newFolder().toPath());val restored=ExchangeLab.read(zip,destination)
        val restoredRole=restored.internalCharacters.single()
        assertEquals("完整原文🌊".repeat(10000),destination.readText((restoredRole.modules.single().blocks.first() as ContentBlock.Text).content))
        assertEquals("图片原始字节",destination.readText(restoredRole.resources.single().content))
        assertEquals("未知扩展原文",destination.readText(restoredRole.extensions.getValue("external")))
        assertEquals(restoredRole.resources.single().id,restoredRole.appearance.avatarResourceId)
        assertEquals(restoredRole.appearance.avatarResourceId,restoredRole.appearance.coverResourceId)
    }
    @Test fun `源卡变化或其他记录读取失败不发布且清理临时包`() {
        val cards=CardStore(temp.newFolder().toPath());val role=ContentDocument("role",CardKind.CHARACTER,"角色")
        cards.save(role,null,ChangeSource.HUMAN,"first")
        val store=ConversationCheckpoints(temp.newFolder().toPath());val scratch=temp.newFolder().toPath()
        val writer=ConversationCheckpointWriter(store,cards,scratch)
        val session=ConversationSession("chat","对话",SourceSelection("role"),CardKind.CHARACTER)
        assertThrows(IllegalStateException::class.java){writer.save(session,CardToolPolicy(emptySet()),"changed","存档"){
            cards.save(role.copy(name="后来修改"),"first",ChangeSource.HUMAN,"second");emptyList()
        }}
        assertThrows(IOException::class.java){writer.save(session,CardToolPolicy(emptySet()),"missing","存档"){
            listOf(CheckpointSection("history"){throw IOException("原记录读取中断")})
        }}
        assertTrue(store.list("chat").isEmpty());assertEquals(0L,Files.list(scratch).use {it.count()})
    }
}
