package novex.runtime

import novex.conversation.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationControlProtocolTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun envelope(vararg items:JSONObject)=JSONObject().put("controls",JSONArray(items.toList()).toString()).toString()
    private fun action()=JSONObject().put("actionKey","go").put("label","去港口").put("behavior","action").put("prompt","前往港口\n观察灯塔")
    @Test fun `模型格式解析保存重开点击保留完整动作与查看语义`() {
        val arguments=envelope(action(),JSONObject().put("actionKey","stats").put("label","状态").put("behavior","view").put("stateKeys",JSONArray(listOf("health"))))
        val registration=ConversationControlProtocol.parse("registered-call","assistant-message",arguments)
        val directory=temporary.newFolder().toPath();ConversationControlStore(directory).register("chat",null,registration)
        val reopened=ConversationControlStore(directory);val visible=reopened.read("chat").controls.visible(listOf("assistant-message"))
        assertEquals(ControlInvocation.SendInstruction("前往港口\n观察灯塔"),reopened.invoke(visible[0].handle,listOf("assistant-message")))
        assertEquals(ControlInvocation.ShowState("状态",listOf("health")),reopened.invoke(visible[1].handle,listOf("assistant-message")))
    }
    @Test fun `拒绝跨对话参数混合语义和错误类型不静默降级`() {
        val bad=listOf(
            JSONObject(envelope(action())).put("conversation_id","other").toString(),
            envelope(action().put("behavior","view")),
            envelope(action().put("enabled","false")),
            envelope(action().put("instruction","不同指令")),
            envelope(action(),action())
        )
        bad.forEach {assertThrows(Exception::class.java){ConversationControlProtocol.parse("r","m",it)}}
        val legacy=action().apply {remove("prompt");put("instruction","旧格式指令")}
        assertEquals(ControlBehavior.Action("旧格式指令"),ConversationControlProtocol.parse("r","m",envelope(legacy)).definitions.single().behavior)
    }
}
