package novex.conversation

import org.junit.Assert.*
import org.junit.Test

class ConversationControlsTest {
    private fun action(key: String, instruction: String) = ControlDefinition(key, key, ControlBehavior.Action(instruction))
    private fun batch(id: String, message: String?, vararg definitions: ControlDefinition) =
        ControlRegistration(id, message, definitions.toList())

    @Test fun `无卡片的对话注册后展开动作原样交给发送入口`() {
        val instruction = "  查看港口🌊\n保留当前人物。  "
        val registry = ConversationControls.empty("chat").register(batch("r1", "reply", action("go", instruction)))
        val visible = registry.visible(listOf("reply"))
        assertEquals(1, visible.size)
        assertEquals(ControlInvocation.SendInstruction(instruction), registry.invoke(visible.single().handle, listOf("reply")))
        assertTrue(registry.visible(emptyList()).isEmpty())
    }

    @Test fun `祖先同名操作被当前分支覆盖而其他分支不串入`() {
        val registry = ConversationControls.empty("chat")
            .register(batch("root", null, action("go", "默认")))
            .register(batch("ra", "a", action("go", "向东")))
            .register(batch("rb", "b", action("go", "向西")))
        val east = registry.visible(listOf("start", "a")).single()
        assertEquals(ControlInvocation.SendInstruction("向东"), registry.invoke(east.handle, listOf("start", "a")))
        assertThrows(IllegalArgumentException::class.java) { registry.invoke(east.handle, listOf("start", "b")) }
        assertEquals("rb", registry.visible(listOf("start", "b")).single().handle.registrationId)
        assertEquals("root", registry.visible(listOf("start")).single().handle.registrationId)
    }

    @Test fun `重新注册和切换对话后旧按钮不能发送不同指令`() {
        val old = ConversationControls.empty("chat").register(batch("r1", "m", action("go", "旧指令")))
        val handle = old.visible(listOf("m")).single().handle
        val fresh = old.register(batch("r2", "m", action("go", "新指令")))
        assertThrows(IllegalArgumentException::class.java) { fresh.invoke(handle, listOf("m")) }
        val another = ConversationControls.restore("another", old.snapshot())
        assertThrows(IllegalArgumentException::class.java) { another.invoke(handle, listOf("m")) }
        assertEquals(ControlInvocation.SendInstruction("旧指令"), old.invoke(handle, listOf("m")))
    }

    @Test fun `查看操作不生成发送指令且快照不受调用方修改影响`() {
        val keys = mutableListOf("体力", "位置")
        val definitions = mutableListOf(ControlDefinition("status", "状态", ControlBehavior.View(keys)))
        val original = ConversationControls.empty("chat").register(ControlRegistration("r", null, definitions))
        keys.clear(); definitions.clear()
        val snapshot = original.snapshot()
        val restored = ConversationControls.restore("chat", snapshot)
        val outcome = restored.invoke(restored.visible(emptyList()).single().handle, emptyList())
        assertEquals(ControlInvocation.ShowState("状态", listOf("体力", "位置")), outcome)
        (snapshot.single().definitions.single().behavior as ControlBehavior.View).stateKeys.let {
            (it as MutableList).clear()
        }
        assertEquals(outcome, restored.invoke(restored.visible(emptyList()).single().handle, emptyList()))
    }

    @Test fun `完整保留超过十二项和长名称而坏批次整体拒绝`() {
        val label = "这个名称不应被无声截断".repeat(10)
        val definitions = (1..20).map { action("$it", "指令 $it").copy(label = label) }
        val registry = ConversationControls.empty("chat").register(ControlRegistration("r", null, definitions))
        assertEquals(20, registry.visible(emptyList()).size)
        assertTrue(registry.visible(emptyList()).all { it.definition.label == label })
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(batch("bad", null, action("valid", "有效"), action("invalid", " ")))
        }
        assertEquals(20, registry.visible(emptyList()).size)
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(batch("dup", null, action("same", "1"), action("same", "2")))
        }
    }
    @Test fun `当前路径停用同名操作后不回退祖先旧按钮`() {
        val first=batch("first","one",action("go","旧动作"))
        val stopped=batch("stopped","two",action("go","停用动作").copy(enabled=false))
        val registry=ConversationControls.empty("chat").register(first).register(stopped)
        val old=registry.visible(listOf("one")).single()
        assertTrue(registry.visible(listOf("one","two")).isEmpty())
        assertThrows(IllegalArgumentException::class.java){registry.invoke(old.handle,listOf("one","two"))}
        assertEquals(old,registry.visible(listOf("one")).single())
    }

}
