package novex.runtime
import novex.content.*
import novex.storage.*
import novex.conversation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderAdoptionTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun `模块成为文件夹后原对话的手选常驻覆盖与禁用仍作用于原正文`() {
        val store=CardStore(temporary.newFolder().toPath())
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"原角色设定".byteInputStream()}
        val body=ContentModule("original","资料",listOf(ContentBlock.Text("text",ref)),use=ModuleUse.Manual)
        val card=ContentDocument("card",CardKind.CHARACTER,"角色",listOf(body))
        store.save(card,null,ChangeSource.HUMAN,"before")
        val draft=CardDrafts(store).begin(card.id)
        val changed=CardEditor(store).apply(card.id,draft.version,card.id,EditorCommand.AddModule("经历",body.id))
        val saved=CardDrafts(store).commit(card.id,changed.version)
        val scope=MaterialScope(listOf(AdoptedSource(card.id,saved.revision,saved.content.modules)),emptySet())
        val window=TriggerWindow(1,setOf(MessageRole.USER))
        val manual=ModuleAdoption.plan(scope,emptyList(),window,manualForThisRequest=setOf(body.id))
        assertEquals(listOf(body.blocks),manual.selected.map {it.module.blocks})
        val always=ModuleAdoption.plan(scope,emptyList(),window,overrides=mapOf(body.id to UseOverride.Rule(ModuleUse.Always)))
        assertEquals(listOf(body.id),always.selected.map {it.module.id})
        val disabled=ModuleAdoption.plan(scope,emptyList(),window,overrides=mapOf(body.id to UseOverride.Disabled),manualForThisRequest=setOf(body.id))
        assertTrue(disabled.selected.isEmpty())
    }
}
