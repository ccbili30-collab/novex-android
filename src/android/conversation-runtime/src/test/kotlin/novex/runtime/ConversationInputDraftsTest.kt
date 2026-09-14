package novex.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationInputDraftsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `输入按对话持久保存重开与清空不串会话`() {
        val root=temp.newFolder().toPath();val store=ConversationInputDrafts(root)
        val one=store.save("one",null,"未发送的中文🌊\n第二行")
        val two=store.save("two",null,"另一个对话")
        val reopened=ConversationInputDrafts(root)
        assertEquals(one,reopened.read("one"));assertEquals(two,reopened.read("two"))
        assertEquals(one,reopened.save("one",one.version,one.text))
        val cleared=reopened.save("one",one.version,"")
        assertNotEquals(one.version,cleared.version);assertEquals("",ConversationInputDrafts(root).read("one").text)
        assertEquals(two,reopened.read("two"))
    }
    @Test fun `旧页面及旧发送结果不能覆盖新输入`() {
        val store=ConversationInputDrafts(temp.newFolder().toPath())
        val one=store.save("chat",null,"准备发送")
        val newer=store.save("chat",one.version,"新补充")
        assertThrows(IllegalStateException::class.java){store.save("chat",one.version,"")}
        assertThrows(IllegalStateException::class.java){store.save("chat",null,"旧页面")}
        assertEquals(newer,store.read("chat"))
    }
}
