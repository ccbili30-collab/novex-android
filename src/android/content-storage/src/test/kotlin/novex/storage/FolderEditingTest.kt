package novex.storage
import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderEditingTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun store()=CardStore(temporary.newFolder().toPath())
    private fun text(store:CardStore):ContentRef {
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"原始内容".byteInputStream()}
        return ref
    }
    @Test fun `增加子项后正文只移动一次且内外重命名独立`() {
        for(source in ChangeSource.entries) {
            val store=store();val ref=text(store)
            val card=ContentDocument("card",CardKind.CHARACTER,"卡",listOf(ContentModule("m","基础档案",listOf(ContentBlock.Text("b",ref)))))
            store.save(card,null,source,"saved")
            val drafts=CardDrafts(store);val editor=CardEditor(store)
            var draft=drafts.begin(card.id)
            draft=editor.apply(card.id,draft.version,card.id,EditorCommand.AddModule("经历","m"),source)
            val folder=draft.content.modules.single();val body=folder.children.first()
            assertTrue(folder.blocks.isEmpty());assertEquals("基础档案",body.name)
            assertEquals("m",body.id);assertNotEquals("m",folder.id)
            assertEquals(card.modules.single().blocks,body.blocks)
            assertEquals(draft.content,FolderContents.organize(draft.content))
            draft=editor.apply(card.id,draft.version,card.id,EditorCommand.RenameModule(folder.id,"档案夹"),source)
            draft=editor.apply(card.id,draft.version,card.id,EditorCommand.RenameModule(body.id,"人物资料"),source)
            assertEquals("档案夹",draft.content.modules.single().name)
            assertEquals("人物资料",draft.content.modules.single().children.first().name)
            assertEquals(1,draft.content.modules.flattenModules().flatMap {it.blocks}.count {it.id=="b"})
            drafts.commit(card.id,draft.version)
            assertEquals(draft.content,CardStore(store.directory).open(card.id)!!.content)
        }
    }
    @Test fun `旧混合模块整理草稿不影响正式卡且只看不算改动`() {
        val store=store();val ref=text(store)
        val card=ContentDocument("card",CardKind.WORLD,"卡",listOf(ContentModule("m","档案",listOf(ContentBlock.Text("b",ref)),children=listOf(ContentModule("c","经历",emptyList())))))
        store.save(card,null,ChangeSource.HUMAN,"saved")
        val initial=CardDrafts(store).begin(card.id)
        val draft=CardDrafts(store).update(card.id,initial.version,card,EditorPosition(moduleId="m",selectionStart=1,selectionEnd=2,textOffset=0))
        val next=CardEditor(store).apply(card.id,draft.version,card.id,EditorCommand.OrganizeFolders)
        assertEquals(next.content.modules.single().children.first().id,next.position.moduleId)
        assertEquals(1,next.position.selectionStart);assertEquals(2,next.position.selectionEnd)
        assertEquals(card,store.open(card.id)!!.content)
        assertTrue(CardContentEquality(store).same(FolderContents.tree(card),FolderContents.tree(next.content)))
        assertEquals(FolderContents.organize(card),next.content)
        CardDrafts(store).discard(card.id,next.version)
        assertEquals(card,store.open(card.id)!!.content)
    }
    @Test fun `竖向子模块移到横向槽保留所有内容并形成主要与并列模块`() {
        val store=store();val ref=text(store)
        val child=ContentModule("c","迁出",listOf(ContentBlock.Text("b",ref)))
        val original=ContentModule("m","原组",emptyList(),children=listOf(child,ContentModule("d","保留",emptyList())))
        val card=ContentDocument("card",CardKind.WORLD,"卡",listOf(original),appearance=CardAppearance(readingLayout=ReadingLayout.CONTINUOUS))
        store.save(card,null,ChangeSource.HUMAN,"saved")
        val draft=CardDrafts(store).begin(card.id)
        val next=CardEditor(store).apply(card.id,draft.version,card.id,EditorCommand.PromoteModule("c"))
        assertEquals(ReadingLayout.PAGED,next.content.appearance.readingLayout)
        assertEquals(child,next.content.modules.last())
        assertEquals(listOf("d"),next.content.modules.first().children.single().children.map {it.id})
        assertEquals(setOf("m","c","d"),next.content.modules.flattenModules().map {it.id}.filter {it in setOf("m","c","d")}.toSet())
        assertEquals(1,next.content.modules.flattenModules().flatMap {it.blocks}.count {it.id=="b"})
    }
    @Test fun `拖入已有模块时双方正文保留且拒绝循环嵌套`() {
        val store=store();val ref=text(store)
        val card=ContentDocument("card",CardKind.WORLD,"卡",listOf(ContentModule("a","甲",listOf(ContentBlock.Text("ab",ref))),ContentModule("b","乙",listOf(ContentBlock.Text("bb",ref)))))
        store.save(card,null,ChangeSource.HUMAN,"saved")
        val drafts=CardDrafts(store);val editor=CardEditor(store);val draft=drafts.begin(card.id)
        val next=editor.apply(card.id,draft.version,card.id,EditorCommand.PlaceModule("a","b",null))
        assertEquals(setOf("ab","bb"),next.content.modules.flattenModules().flatMap {it.blocks}.map {it.id}.toSet())
        try {editor.apply(card.id,next.version,card.id,EditorCommand.PlaceModule(next.content.modules.single().id,"a",null));fail("不能形成循环")}catch(_:IllegalArgumentException){}
        assertEquals(next,drafts.read(card.id))
    }
}
