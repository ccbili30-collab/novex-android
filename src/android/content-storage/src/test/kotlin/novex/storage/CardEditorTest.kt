package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardEditorTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun `模块携带与标签共用保存且不改正文`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        val initial=drafts.create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","模块",emptyList()))))
        val written=editor.apply("card",initial.version,"card",EditorCommand.WriteText("m",null,"模块","保留正文",EditorPosition()))
        val rule=ModuleUse.Keywords(listOf("港口"),false,false)
        val changed=editor.apply("card",written.version,"card",EditorCommand.ModuleOptions("m",listOf("重要"),rule),ChangeSource.AI)
        assertEquals(written.content.modules.single().blocks,changed.content.modules.single().blocks)
        val saved=drafts.commit("card",changed.version)
        assertEquals(ChangeSource.AI,saved.source)
        assertEquals(rule,store.open("card")!!.content.modules.single().use)
        val resumed=drafts.begin("card")
        val cleared=editor.apply("card",resumed.version,"card",EditorCommand.ModuleOptions("m",listOf("必须携带"),null))
        assertNull(cleared.content.modules.single().use)
        assertEquals(written.content.modules.single().blocks,cleared.content.modules.single().blocks)
    }
    @Test fun `人工和模型来源使用同一编辑保存且保留模块规则`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        var draft=drafts.create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList(),listOf("重要"),ModuleUse.Always))))
        draft=editor.apply("card",draft.version,"card",EditorCommand.WriteText("m",null,"经历","人工原文",EditorPosition()))
        val saved=drafts.commit("card",draft.version);assertEquals(ChangeSource.HUMAN,saved.source)
        draft=drafts.begin("card");val block=draft.content.modules.single().blocks.single().id
        draft=editor.apply("card",draft.version,"card",EditorCommand.WriteText("m",block,"经历","模型修订",EditorPosition()),ChangeSource.AI)
        val ai=drafts.commit("card",draft.version);assertEquals(ChangeSource.AI,ai.source)
        assertEquals(listOf("重要"),ai.content.modules.single().tags);assertEquals(ModuleUse.Always,ai.content.modules.single().use)
        assertEquals(block,ai.content.modules.single().blocks.single().id)
        draft=drafts.begin("card")
        draft=editor.apply("card",draft.version,"card",EditorCommand.WriteText("m",block,"经历","人工接续",EditorPosition()))
        val human=drafts.commit("card",draft.version);assertEquals(ChangeSource.HUMAN,human.source)
        val ref=(human.content.modules.single().blocks.single() as ContentBlock.Text).content
        assertEquals("人工接续",store.contents.open(ref).bufferedReader().use { it.readText() })
        assertEquals(ai.revision,human.parentRevision)
    }
    @Test fun `内部角色编辑隔离及过期写入不覆盖新草稿`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        val sibling=ContentDocument("sibling",CardKind.CHARACTER,"船长",listOf(ContentModule("other","其他",emptyList())))
        val initial=drafts.create(ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(
            ContentDocument("role",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList()))),sibling)))
        val current=editor.apply("world",initial.version,"role",EditorCommand.WriteText("m",null,"经历","只改角色",EditorPosition()))
        assertEquals(sibling,current.content.internalCharacters[1]);assertTrue(current.content.modules.isEmpty())
        assertThrows(DraftConflict::class.java){editor.apply("world",initial.version,"role",EditorCommand.Rename("迟到的修改"),ChangeSource.AI)}
        assertThrows(IllegalArgumentException::class.java){editor.apply("world",current.version,"role",EditorCommand.Position(EditorPosition("other")))}
        assertEquals(current,drafts.read("world"))
    }
    @Test fun `只移动编辑位置不把模型内容误记为人工修改`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        var draft=drafts.create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList()))))
        draft=editor.apply("card",draft.version,"card",EditorCommand.WriteText("m",null,"经历","模型原文",EditorPosition()),ChangeSource.AI)
        draft=editor.apply("card",draft.version,"card",EditorCommand.Position(draft.position.copy(selectionStart=1,selectionEnd=2)))
        assertEquals(ChangeSource.AI,draft.source)
    }
    @Test fun `模块排序共用保存且隔离目标与拒绝过期草稿`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        val role=ContentDocument("role",CardKind.CHARACTER,"角色",listOf(ContentModule("a","第一",emptyList(),listOf("标签"),ModuleUse.Always),ContentModule("b","第二",emptyList())))
        val root=ContentDocument("world",CardKind.WORLD,"世界",listOf(ContentModule("outside","世界模块",emptyList())),internalCharacters=listOf(role))
        val initial=drafts.create(root)
        val written=editor.apply("world",initial.version,"role",EditorCommand.WriteText("a",null,"第一","排序保留正文",EditorPosition()))
        val moved=editor.apply("world",written.version,"role",EditorCommand.MoveModule("a",null),ChangeSource.AI)
        assertEquals(root.modules,moved.content.modules)
        assertEquals(written.content.internalCharacters.single().modules.reversed(),moved.content.internalCharacters.single().modules)
        assertThrows(IllegalArgumentException::class.java){editor.apply("world",moved.version,"role",EditorCommand.MoveModule("a","outside"))}
        assertThrows(DraftConflict::class.java){editor.apply("world",written.version,"role",EditorCommand.MoveModule("b",null))}
        assertEquals(moved,drafts.read("world"))
        val saved=drafts.commit("world",moved.version)
        assertEquals(ChangeSource.AI,saved.source)
        val modules=store.open("world")!!.content.internalCharacters.single().modules
        assertEquals(listOf("b","a"),modules.map {it.id})
        val text=(modules[1].blocks.single() as ContentBlock.Text).content
        assertEquals("排序保留正文",store.contents.open(text).bufferedReader().use {it.readText()})
    }

    @Test fun `内容块排序保留位置并拒绝跨模块和过期操作`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        var draft=drafts.create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","正文",emptyList()),ContentModule("other","其他",emptyList()))))
        draft=editor.apply("card",draft.version,"card",EditorCommand.WriteText("m",null,"正文","第一段",EditorPosition()))
        val first=draft.content.modules[0].blocks.single()
        draft=editor.apply("card",draft.version,"card",EditorCommand.WriteText("m",null,"正文","第二段",EditorPosition(selectionStart=2,selectionEnd=2)))
        val second=draft.content.modules[0].blocks[1]
        val original=draft
        val moved=editor.apply("card",draft.version,"card",EditorCommand.MoveBlock("m",first.id,null),ChangeSource.AI)
        assertEquals(original.position,moved.position)
        assertEquals(listOf(second,first),moved.content.modules[0].blocks)
        assertThrows(IllegalArgumentException::class.java){editor.apply("card",moved.version,"card",EditorCommand.MoveBlock("other",first.id,null))}
        assertThrows(DraftConflict::class.java){editor.apply("card",original.version,"card",EditorCommand.MoveBlock("m",second.id,null))}
        assertEquals(moved,drafts.read("card"))
        val saved=drafts.commit("card",moved.version)
        assertEquals(ChangeSource.AI,saved.source)
        assertEquals(listOf("第二段","第一段"),saved.content.modules[0].blocks.map {store.contents.open((it as ContentBlock.Text).content).bufferedReader().use {r->r.readText()}})
    }

}
