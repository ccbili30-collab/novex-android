package novex.runtime

import novex.conversation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationControlStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun action(id:String,message:String,text:String)=ControlRegistration(id,message,listOf(ControlDefinition("go","行动",ControlBehavior.Action(text))))
    @Test fun `注册重开点击保持原指令且其他分支不可调用`() {
        val root=temporary.newFolder().toPath();val store=ConversationControlStore(root)
        val batch=action("r","message","查看港口\n保留原文 😀")
        store.register("chat",null,batch)
        val reopened=ConversationControlStore(root)
        val visible=reopened.read("chat").controls.visible(listOf("message"))
        assertEquals(ControlInvocation.SendInstruction("查看港口\n保留原文 😀"),reopened.invoke(visible.single().handle,listOf("message")))
        assertThrows(IllegalArgumentException::class.java){reopened.invoke(visible.single().handle,listOf("other"))}
        assertTrue(reopened.read("other-chat").controls.visible(listOf("message")).isEmpty())
    }
    @Test fun `替换后重试旧注册不恢复旧菜单也不吞掉版本冲突`() {
        val store=ConversationControlStore(temporary.newFolder().toPath())
        val original=action("old","message","旧指令")
        val first=store.register("chat",null,original)
        val old=first.controls.visible(listOf("message")).single().handle
        val second=store.register("chat",first.version,action("new","message","新指令"))
        assertEquals(second.version,store.register("chat",null,original).version)
        assertThrows(IllegalArgumentException::class.java){store.invoke(old,listOf("message"))}
        assertThrows(IllegalStateException::class.java){store.register("chat",first.version,action("third","other","另一个"))}
        assertThrows(IllegalArgumentException::class.java){store.register("chat",second.version,action("old","message","篡改旧注册"))}
        assertEquals(second.version,store.read("chat").version)
    }
    @Test fun `查看操作重开仍只展示状态且不限制为十二项`() {
        val root=temporary.newFolder().toPath();val store=ConversationControlStore(root)
        val definitions=(1..20).map {ControlDefinition("state-$it","状态 $it",ControlBehavior.View(listOf("health","location")))}
        store.register("chat",null,ControlRegistration("views",null,definitions))
        val reopened=ConversationControlStore(root);val visible=reopened.read("chat").controls.visible(emptyList())
        assertEquals(20,visible.size)
        assertEquals(ControlInvocation.ShowState("状态 1",listOf("health","location")),reopened.invoke(visible.first().handle,emptyList()))
    }
}
