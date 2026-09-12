package novex.runtime

import novex.content.*
import novex.storage.*
import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class CardToolReaderTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun initial(store:CardStore):SavedCard {
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("raw"),ref))){ByteArrayInputStream("甲😀乙\n𠀀丙".toByteArray())}
        return store.save(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",listOf(ContentBlock.Text("b",ref))))),null,ChangeSource.HUMAN,"first")
    }
    private val policy=CardToolPolicy(setOf(ManagementTarget("card")))
    private fun readCall()=PendingTool("read","read_card",JSONObject().put("root_id","card").put("target_id","card").toString())
    private fun page(version:String,start:Long,count:Int=2)=PendingTool("page","read_text_block",JSONObject().put("root_id","card").put("target_id","card").put("draft_version",version).put("module_id","m").put("block_id","b").put("offset",start).put("count",count).toString())
    @Test fun `adopted reader retains saved version while management reads draft`() {
        val store=CardStore(temporary.newFolder().toPath());initial(store)
        val adopted=CardToolReader(store,useDraft={false})
        val version=adopted.read(readCall(),policy).getString("draft_version")
        val draft=CardDrafts(store).begin("card")
        CardEditor(store).apply("card",draft.version,"card",EditorCommand.WriteText("m","b","经历","未保存正文",EditorPosition()))
        assertEquals(version,adopted.read(readCall(),policy).getString("draft_version"))
        assertEquals("甲😀",adopted.read(page(version,0),policy).getString("text"))
        assertTrue(CardToolReader(store).read(readCall(),policy).getBoolean("has_draft"))
    }
    @Test fun `结构读取不创建草稿且中文补充字符分页可完整拼回`() {
        val store=CardStore(temporary.newFolder().toPath());initial(store);val reader=CardToolReader(store,2)
        val metadata=reader.read(readCall(),policy)
        assertFalse(metadata.getBoolean("has_draft"));assertTrue(CardDrafts(store).list().isEmpty());assertFalse(metadata.toString().contains("甲😀"))
        val version=metadata.getString("draft_version")
        val first=reader.read(page(version,0),policy);val second=reader.read(page(version,first.getLong("next_offset")),policy)
        val third=reader.read(page(version,second.getLong("next_offset")),policy)
        assertEquals("甲😀",first.getString("text"));assertEquals("乙\n",second.getString("text"));assertEquals("𠀀丙",third.getString("text"))
        assertFalse(third.getBoolean("has_more"));assertTrue(third.isNull("next_offset"));assertFalse(first.getBoolean("entire_file_read"));assertTrue(third.getBoolean("entire_file_read"))
        assertThrows(IllegalArgumentException::class.java){reader.read(page(version,0,3),policy)}
        assertFalse(first.getBoolean("image_sent"))
    }
    @Test fun `用户草稿更新后旧页版本拒绝而新读取返回最新来源`() {
        val store=CardStore(temporary.newFolder().toPath());initial(store);val reader=CardToolReader(store)
        val old=reader.read(readCall(),policy).getString("draft_version")
        val draft=CardDrafts(store).begin("card")
        val changed=CardEditor(store).apply("card",draft.version,"card",EditorCommand.WriteText("m","b","经历","用户刚修改的正文",EditorPosition()))
        assertThrows(IllegalArgumentException::class.java){reader.read(page(old,0),policy)}
        val current=reader.read(readCall(),policy);assertEquals(changed.version,current.getString("draft_version"));assertEquals("HUMAN",current.getString("root_change_source"))
        assertEquals("用户刚修改的正文",reader.read(page(changed.version,0,50),policy).getString("text"))
    }
    @Test fun `正式版本读后可直接执行且未知范围及只读不能调用读取接口`() {
        val store=CardStore(temporary.newFolder().toPath());initial(store);val reader=CardToolReader(store)
        val version=reader.read(readCall(),policy).getString("draft_version")
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val request=CardToolRequest("chat","rename",ManagementTarget("card"),version,CardToolEdit.Rename("新名称"))
        assertTrue(coordinator.submit(request,policy) is CardToolResult.Saved);assertEquals("新名称",store.open("card")!!.content.name)
        assertThrows(IllegalArgumentException::class.java){reader.read(readCall(),policy.copy(targets=emptySet()))}
        assertThrows(IllegalArgumentException::class.java){reader.read(readCall(),policy.copy(permission=ToolPermission.READ_ONLY))}
    }
    @Test fun `分页位置越界和非法编码不返回伪造正文`() {
        val store=CardStore(temporary.newFolder().toPath());val saved=initial(store)
        val reference=(saved.content.modules.single().blocks.single() as ContentBlock.Text).content
        assertThrows(IllegalArgumentException::class.java){TextPages(store.contents).read(reference,100,2)}
        val invalid=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("invalid"),invalid))){ByteArrayInputStream(byteArrayOf(0xff.toByte()))}
        assertThrows(java.nio.charset.MalformedInputException::class.java){TextPages(store.contents).read(invalid,0,2)}
    }
}
