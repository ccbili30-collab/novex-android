package novex.storage

import novex.content.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CardEditAccessTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun initial(store:CardStore):CardDraft=CardDrafts(store).create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList()))))
    @Test fun `占用期间可以读取但人工写入正式保存和丢弃均被拒绝`() {
        val store=CardStore(temporary.newFolder().toPath());val draft=initial(store);val drafts=CardDrafts(store)
        store.acquireEdit("card","card","模型任务").use { lease ->
            assertEquals(draft,drafts.read("card"))
            assertThrows(CardOccupied::class.java){CardEditor(store).apply("card",draft.version,"card",EditorCommand.Rename("人工抢写"))}
            assertThrows(CardOccupied::class.java){drafts.commit("card",draft.version)}
            assertThrows(CardOccupied::class.java){drafts.discard("card",draft.version)}
            val edited=CardEditor(store).apply("card",draft.version,"card",EditorCommand.Rename("已整理"),ChangeSource.AI,lease)
            assertEquals(ChangeSource.AI,drafts.commit("card",edited.version,lease).source)
        }
        val next=drafts.begin("card")
        assertEquals("人工接续",CardEditor(store).apply("card",next.version,"card",EditorCommand.Rename("人工接续")).content.name)
    }
    @Test fun `占用一个内部角色不阻止其他角色修改但提交不能夹带占用目标的改动`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store)
        val world=ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(ContentDocument("a",CardKind.CHARACTER,"甲"),ContentDocument("b",CardKind.CHARACTER,"乙")))
        store.save(world,null,ChangeSource.HUMAN,"base");var draft=drafts.begin("world")
        store.acquireEdit("world","a","任务").use { lease ->
            draft=CardEditor(store).apply("world",draft.version,"b",EditorCommand.Rename("乙修改"))
            drafts.commit("world",draft.version)
            draft=drafts.begin("world")
            draft=CardEditor(store).apply("world",draft.version,"a",EditorCommand.Rename("甲修改"),ChangeSource.AI,lease)
            assertThrows(CardOccupied::class.java){drafts.commit("world",draft.version)}
            drafts.commit("world",draft.version,lease)
        }
        assertEquals(listOf("甲修改","乙修改"),store.open("world")!!.content.internalCharacters.map { it.name })
    }
    @Test fun `世界占用保护角色成员结构且已关闭句柄不能绕过新占用`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store)
        val draft=drafts.create(ContentDocument("world",CardKind.WORLD,"世界"))
        val old=store.acquireEdit("world","world","first")
        assertThrows(CardOccupied::class.java){CardCharacters(store).create("world",draft.version,"新角色")}
        old.close()
        store.acquireEdit("world","world","second").use {
            assertThrows(IllegalStateException::class.java){CardEditor(store).apply("world",draft.version,"world",EditorCommand.Rename("旧任务"),ChangeSource.AI,old)}
        }
        assertEquals(draft,drafts.read("world"))
    }
    @Test(timeout=15000) fun `独立进程持有占用被终止后系统释放且内容仍完整`() {
        val root=temporary.newFolder().toPath();val store=CardStore(root)
        store.save(ContentDocument("card",CardKind.CHARACTER,"原内容"),null,ChangeSource.HUMAN,"base")
        val classes=listOf(CardEditLeaseProcess::class.java,CardStore::class.java,ContentDocument::class.java,JSONObject::class.java,kotlin.Unit::class.java)
        val classpath=classes.map{Path.of(it.protectionDomain.codeSource.location.toURI()).toString()}.distinct().joinToString(java.io.File.pathSeparator)
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,"novex.storage.CardEditLeaseProcess",root.toString()).redirectErrorStream(true).start()
        try {
            assertEquals("READY",process.inputStream.bufferedReader().readLine())
            assertThrows(CardOccupied::class.java){store.acquireEdit("card","card","parent")}
            process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS))
            // 进程结束通知与操作系统释放文件锁可能有短暂时差；限定时间内必须恢复。
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(true) {
                try { store.acquireEdit("card","card","after-exit").close();break }
                catch(occupied:CardOccupied) {
                    if(System.nanoTime()>=deadline)throw occupied
                    Thread.sleep(20)
                }
            }
            assertEquals("原内容",store.open("card")!!.content.name)
        }finally{process.destroyForcibly()}
    }
}
object CardEditLeaseProcess {
    @JvmStatic fun main(args:Array<String>) {
        val store=CardStore(Path.of(args[0]))
        store.acquireEdit("card","card","child").use { println("READY");System.out.flush();System.`in`.read() }
    }
}
