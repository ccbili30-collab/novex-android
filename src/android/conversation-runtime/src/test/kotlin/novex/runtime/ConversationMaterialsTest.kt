package novex.runtime

import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationMaterialsTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun `按对话回合读取原记录不伪造缺失信息`() {
        val root=temporary.newFolder().toPath();val journal=TurnJournal(root)
        val reader=ConversationMaterials(journal)
        assertNull(reader.read("chat","turn"))
        journal.enqueue("4:chat4:turn","initial","意图");journal.claim("4:chat4:turn","initial")
        journal.prepared("4:chat4:turn","initial",JSONObject().put("modules",JSONArray().put(JSONObject()
            .put("sourceName","原世界").put("moduleName","地理").put("selected",true).put("reason","KEYWORD_MATCH").put("matchedWords",JSONArray().put("港口"))))
            .put("imagesNotSent",JSONArray().put("image")))
        val expected=ConversationMaterialRecord(listOf(AdoptedModuleRecord("原世界","地理",true,"KEYWORD_MATCH",listOf("港口"))),1)
        assertEquals(expected,ConversationMaterials(TurnJournal(root)).read("chat","turn"))
        assertNull(reader.read("other","turn"))
        assertEquals(TurnState.RUNNING,journal.read("4:chat4:turn","initial")!!.state)
    }
    @Test fun `独立明细不需要解析完整请求且结束状态保留引用`() {
        val root=temporary.newFolder().toPath();val journal=TurnJournal(root)
        val namespace="4:chat4:turn"
        journal.enqueue(namespace,"initial","意图");journal.claim(namespace,"initial")
        val details=JSONObject().put("modules",JSONArray().put(JSONObject().put("sourceName","世界").put("moduleName","地理").put("selected",true).put("reason","ALWAYS")))
        val prepared=journal.prepared(namespace,"initial",JSONObject().put("request", "大".repeat(1000000)),details)
        assertNotNull(prepared.details)
        assertTrue(journal.text(prepared.details!!).length<500)
        journal.finish(namespace,"initial",JSONObject().put("kind","finished"))
        val reopened=TurnJournal(root)
        assertEquals(prepared.details,reopened.read(namespace,"initial")!!.details)
        assertEquals("地理",ConversationMaterials(reopened).read("chat","turn")!!.modules.single().moduleName)
        // 以冲突的完整记录证明读取优先使用独立明细，而非重新解析请求资料。
        assertFalse(reopened.text(prepared.trace!!).contains("地理"))
    }

}
