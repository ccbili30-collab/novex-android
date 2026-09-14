package novex.runtime

import novex.content.*
import novex.conversation.*
import novex.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationSessionsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `世界与独立角色入口保存稳定来源且重复编号不串卡`() {
        val cards=CardStore(temp.newFolder().toPath())
        val role=ContentDocument("role",CardKind.CHARACTER,"独立角色")
        cards.save(role,null,ChangeSource.HUMAN,"save-role")
        val child=ContentDocument("internal",CardKind.CHARACTER,"世界内角色")
        cards.save(ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(child)),null,ChangeSource.HUMAN,"save-world")
        val root=temp.newFolder().toPath();val sessions=ConversationSessions(root,cards)
        val standalone=sessions.create("one","角色互动",SourceSelection("role"))
        val world=sessions.create("two","世界互动",SourceSelection("world"))
        val internal=sessions.create("three","内部角色互动",SourceSelection("world","internal"))
        val reopened=ConversationSessions(root,cards)
        assertEquals(standalone,reopened.read("one"))
        assertEquals(world,reopened.create("two","世界互动",SourceSelection("world")))
        assertEquals(CardKind.CHARACTER,internal.kind)
        assertEquals(3,reopened.list().size)
        assertThrows(IllegalArgumentException::class.java){reopened.create("one","角色互动",SourceSelection("world"))}
        assertThrows(IllegalArgumentException::class.java){reopened.create("missing","不存在",SourceSelection("absent"))}
        assertNull(reopened.read("missing"))
    }
    @Test fun `会话重开后采用最新正式资料而不复制正文进入口或授予管理`() {
        val cards=CardStore(temp.newFolder().toPath())
        fun content(value:String):ContentRef {
            val ref=cards.contents.allocator()()
            cards.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){value.byteInputStream()}
            return ref
        }
        val original=ContentDocument("role",CardKind.CHARACTER,"角色",listOf(ContentModule("module","设定",listOf(ContentBlock.Text("text",content("旧设定"))),use=ModuleUse.Always)))
        val saved=cards.save(original,null,ChangeSource.HUMAN,"first")
        val root=temp.newFolder().toPath()
        ConversationSessions(root,cards).create("chat","开始互动",SourceSelection("role"))
        val changed=original.copy(modules=listOf(original.modules.single().copy(blocks=listOf(ContentBlock.Text("text",content("最新设定"))))))
        val latest=cards.save(changed,saved.revision,ChangeSource.HUMAN,"second")
        val session=ConversationSessions(root,cards)
        val materials=RequestMaterials(cards)
        val request=materials.prepare(listOf(session.primary("chat")),emptySet(),listOf(TriggerMessage("message",MessageRole.USER,"开始")),TriggerWindow(1,setOf(MessageRole.USER)))
        assertEquals(latest.revision,request.texts.single().revision)
        assertEquals("最新设定",materials.open(request.texts.single()).bufferedReader().use {it.readText()})
        assertEquals(SourceSelection("role"),session.read("chat")!!.primary)
        java.nio.file.Files.list(root).use {paths->paths.filter {it.fileName.toString().endsWith(".json")}.forEach {assertFalse(Utf8Files.read(it).contains("设定"))}}
    }
    @Test fun `世界来源随当前角色更新而单角色入口不携带同级角色`() {
        val cards=CardStore(temp.newFolder().toPath())
        val role=ContentDocument("role",CardKind.CHARACTER,"船长")
        val world=ContentDocument("world",CardKind.WORLD,"群岛",internalCharacters=listOf(role))
        val first=cards.save(world,null,ChangeSource.HUMAN,"first")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards)
        sessions.create("world-chat","世界互动",SourceSelection("world"))
        sessions.create("role-chat","角色互动",SourceSelection("world","role"))
        assertEquals(listOf(SourceSelection("world"),SourceSelection("world","role")),sessions.sources("world-chat"))
        val next=world.copy(internalCharacters=world.internalCharacters+ContentDocument("guide",CardKind.CHARACTER,"向导"))
        cards.save(next,first.revision,ChangeSource.HUMAN,"second")
        assertEquals(listOf("world","role","guide"),sessions.sources("world-chat").map {it.targetId})
        assertEquals(listOf(SourceSelection("world","role")),sessions.sources("role-chat"))
    }

}
