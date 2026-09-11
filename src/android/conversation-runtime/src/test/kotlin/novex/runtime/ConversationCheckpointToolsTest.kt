package novex.runtime

import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ConversationCheckpointToolsTest {
    @get:Rule val temp=TemporaryFolder()
    private val namespace="4:chat4:turn"
    private fun call(id:String="call")=PendingTool(id,"save_conversation_checkpoint","{\"name\":\"山门\"}")
    @Test fun `批准只选择确认才保存重试读取实际存档且拒绝不保存`() {
        val store=ConversationCheckpoints(temp.newFolder().toPath());val journal=TurnJournal(temp.newFolder().toPath());var saves=0
        val tools=ConversationCheckpointTools(store,journal,"chat","turn"){id,name->saves++;store.save("chat",id,name,listOf(CheckpointSection("original"){"原文".byteInputStream()}))}
        assertTrue(tools.definitions(ToolPermission.READ_ONLY).isEmpty())
        assertEquals(CardToolResult.Denied,tools.submit(namespace,call("readonly"),ToolPermission.READ_ONLY));assertTrue(store.list("chat").isEmpty())
        assertEquals(CardToolResult.AwaitingApproval,tools.submit(namespace,call(),ToolPermission.APPROVAL))
        assertEquals(CardToolResult.AwaitingApproval,tools.confirm(namespace,call(),ToolPermission.APPROVAL))
        tools.select(namespace,call(),true);assertEquals(0,saves)
        val result=tools.confirm(namespace,call(),ToolPermission.APPROVAL) as CardToolResult.CheckpointSaved
        assertEquals("山门",store.read("chat",result.checkpointId)!!.name)
        assertEquals(result,tools.submit(namespace,call(),ToolPermission.FREE));assertEquals(1,saves)
        assertEquals(result.checkpointId,JSONObject(CardToolProtocol.result(result)).getString("checkpoint_id"))
        tools.submit(namespace,call("reject"),ToolPermission.APPROVAL);tools.select(namespace,call("reject"),true)
        assertEquals(CardToolResult.Denied,tools.reject(namespace,call("reject")));assertEquals(1,saves)
        assertEquals(CardToolResult.Denied,tools.submit(namespace,call("reject"),ToolPermission.FREE))
    }
    @Test fun `保存完成回执中断后恢复真实结果不重新执行`() {
        val store=ConversationCheckpoints(temp.newFolder().toPath());val journal=TurnJournal(temp.newFolder().toPath());var saves=0
        val tools=ConversationCheckpointTools(store,journal,"chat","turn"){id,name->
            saves++;store.save("chat",id,name,listOf(CheckpointSection("input"){"原始内容".byteInputStream()}));throw IOException("登记之后中断")
        }
        assertEquals(CardToolResult.Unconfirmed,tools.submit(namespace,call(),ToolPermission.FREE));assertEquals(1,store.list("chat").size)
        val recovered=tools.submit(namespace,call(),ToolPermission.FREE)
        assertTrue(recovered is CardToolResult.CheckpointSaved);assertEquals(1,saves)
        assertEquals(recovered,tools.submit(namespace,call(),ToolPermission.FREE))
    }
    @Test fun `未登记的回执不能声称保存且不接受越界目标或附加原文`() {
        val store=ConversationCheckpoints(temp.newFolder().toPath());val journal=TurnJournal(temp.newFolder().toPath())
        val tools=ConversationCheckpointTools(store,journal,"chat","turn"){id,name->SavedCheckpoint(id,"chat",name,1,emptyMap())}
        assertEquals(CardToolResult.Unconfirmed,tools.submit(namespace,call(),ToolPermission.FREE));assertTrue(store.list("chat").isEmpty())
        assertThrows(IllegalArgumentException::class.java){tools.submit("5:other4:turn",call("other"),ToolPermission.FREE)}
        assertThrows(IllegalArgumentException::class.java){tools.submit(namespace,PendingTool("extra",call().name,"{\"name\":\"存档\",\"summary\":\"替代原文\"}"),ToolPermission.FREE)}
        assertThrows(IllegalArgumentException::class.java){tools.submit(namespace,call().copy(arguments="{\"name\":\"另一个名字\"}"),ToolPermission.FREE)}
    }
}
