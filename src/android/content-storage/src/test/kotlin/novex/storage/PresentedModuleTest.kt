package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresentedModuleTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun store()=CardStore(temporary.newFolder().toPath())
    @Test fun `新增横向分组保留原模块编号顺序正文与角色引用且保存前不影响正式卡`() {
        val store=store()
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"原文 **不改写**".byteInputStream()}
        val intro=ContentModule("intro","简介",listOf(ContentBlock.Text("i-text",ref)))
        val modules=listOf(ContentModule("era","时代",listOf(ContentBlock.Text("body",ref)),children=listOf(ContentModule("child","下属",emptyList())),characterIds=listOf("role")),ContentModule("geo","地理",emptyList()))
        val card=ContentDocument("world",CardKind.WORLD,"世界",listOf(intro)+modules,
            internalCharacters=listOf(ContentDocument("role",CardKind.CHARACTER,"人物")),appearance=CardAppearance(readingLayout=ReadingLayout.CONTINUOUS))
        store.save(card,null,ChangeSource.HUMAN,"saved")
        val drafts=CardDrafts(store);val draft=drafts.begin(card.id)
        val next=CardEditor(store).apply(card.id,draft.version,card.id,EditorCommand.AddPresentedModule(true,"intro"))
        assertEquals(card,store.open(card.id)!!.content)
        assertEquals(intro,next.content.modules.first())
        assertEquals("主要",next.content.modules[1].name)
        assertEquals(FolderContents.organize(card).modules.drop(1),next.content.modules[1].children)
        assertEquals(card.internalCharacters,next.content.internalCharacters)
        assertEquals(ReadingLayout.PAGED,next.content.appearance.readingLayout)
        assertEquals(next.content.modules.last().id,next.position.moduleId)
        drafts.commit(card.id,next.version)
        assertEquals(next.content,CardStore(store.directory).open(card.id)!!.content)
        assertEquals("原文 **不改写**",store.contents.open(ref).bufferedReader().use{it.readText()})
    }
    @Test fun `空白角色从主要槽新增竖向模块并能丢弃草稿`() {
        val store=store();val card=ContentDocument("role",CardKind.CHARACTER,"角色")
        store.save(card,null,ChangeSource.HUMAN,"saved")
        val drafts=CardDrafts(store);val draft=drafts.begin(card.id)
        val next=CardEditor(store).apply(card.id,draft.version,card.id,EditorCommand.AddPresentedModule(false))
        assertEquals("主要",next.content.modules.single().name)
        assertEquals(next.position.moduleId,next.content.modules.single().children.single().id)
        drafts.discard(card.id,next.version)
        assertEquals(card,store.open(card.id)!!.content)
    }
    @Test fun `已有横向分组只追加新组不重新包装`() {
        val store=store();val group=ContentModule("group","原分组",emptyList(),children=listOf(ContentModule("child","下属",emptyList())))
        val card=ContentDocument("role",CardKind.CHARACTER,"角色",modules=listOf(group))
        store.save(card,null,ChangeSource.HUMAN,"saved")
        val draft=CardDrafts(store).begin(card.id)
        val next=CardEditor(store).apply(card.id,draft.version,card.id,EditorCommand.AddPresentedModule(true))
        assertEquals(2,next.content.modules.size);assertEquals(group,next.content.modules.first())
    }
    @Test fun `内部角色增加分组仅改变该角色并拒绝旧草稿版本`() {
        val store=store();val role=ContentDocument("role",CardKind.CHARACTER,"角色",modules=listOf(ContentModule("m","设定",emptyList())),appearance=CardAppearance(readingLayout=ReadingLayout.CONTINUOUS))
        val world=ContentDocument("world",CardKind.WORLD,"世界",modules=listOf(ContentModule("w","世界设定",emptyList())),internalCharacters=listOf(role))
        store.save(world,null,ChangeSource.HUMAN,"saved")
        val draft=CardDrafts(store).begin(world.id);val editor=CardEditor(store)
        val next=editor.apply(world.id,draft.version,role.id,EditorCommand.AddPresentedModule(true))
        assertEquals(world.modules,next.content.modules)
        assertEquals(role.modules,next.content.internalCharacters.single().modules.first().children)
        try {editor.apply(world.id,draft.version,role.id,EditorCommand.AddPresentedModule(true));fail("不能覆盖较新草稿")}catch(_:DraftConflict){}
        assertEquals(next,CardDrafts(store).read(world.id))
    }
}
