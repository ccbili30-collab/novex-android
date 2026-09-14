package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Path

class CardStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun card(store: CardStore, kind: CardKind = CardKind.CHARACTER): ContentDocument {
        val ref = store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"), ref))) { ByteArrayInputStream("角色原文🌊".toByteArray()) }
        return RawTextImport.prepareUtf8(ref, store.contents, kind, "作品")
    }

    @Test fun `人工保存和模型修改走同一入口并能重开历史及完整导出`() {
        val root = temporary.newFolder().toPath(); val store = CardStore(root)
        val original = card(store, CardKind.WORLD).copy(internalCharacters = listOf(card(store)))
        val first = store.save(original, null, ChangeSource.HUMAN, "first")
        val edited = ContentChanges.apply(original, ContentChange.AddModule(ContentModule("extra", "新模块", emptyList())))
        store.save(edited, first.revision, ChangeSource.AI, "second")
        val reopened = CardStore(root)
        assertEquals(edited, reopened.open(original.id)!!.content)
        assertEquals(ChangeSource.AI, reopened.open(original.id)!!.source)
        assertEquals(original, reopened.history(original.id, "first")!!.content)
        assertEquals(listOf(CardSummary(original.id, "作品", CardKind.WORLD, "second")), reopened.list())
        val archive = temporary.root.toPath().resolve("saved.zip")
        ExchangeLab.write(archive, reopened.open(original.id)!!.content, reopened.contents)
        val target = CardStore(temporary.newFolder().toPath())
        val restored = ExchangeLab.read(archive, target.contents)
        val saved = target.save(restored, null, ChangeSource.IMPORT, "import")
        assertEquals(2, saved.content.modules.size)
        assertEquals(1, target.open(restored.id)!!.content.internalCharacters.size)
    }

    @Test fun `缺正文或旧起始修订不能覆盖正式卡`() {
        val store = CardStore(temporary.newFolder().toPath()); val original = card(store)
        store.save(original, null, ChangeSource.HUMAN, "first")
        val missing = original.copy(modules = listOf(ContentModule("m", "缺失", listOf(ContentBlock.Text("b", ContentRef("missing/object"))))))
        assertThrows(IOException::class.java) { store.save(missing, "first", ChangeSource.AI, "missing") }
        assertThrows(RevisionConflict::class.java) { store.save(original.copy(name = "不能覆盖"), null, ChangeSource.AI, "stale") }
        assertEquals(original, store.open(original.id)!!.content)
    }

    @Test fun `正式指针发布前后中断均可重开并以同一提交重试`() {
        for (phase in listOf("revision-written", "head-published")) {
            val root = temporary.newFolder().toPath(); val store = CardStore(root); val original = card(store)
            store.save(original, null, ChangeSource.HUMAN, "first")
            val changed = original.copy(name = "更新后")
            assertThrows(IOException::class.java) {
                store.save(changed, "first", ChangeSource.HUMAN, "retry") { if (it == phase) throw IOException("中断") }
            }
            val reopened = CardStore(root)
            assertEquals(if (phase == "revision-written") "first" else "retry", reopened.open(original.id)!!.revision)
            if (phase == "revision-written") assertNull(reopened.history(original.id, "retry"))
            assertEquals(changed, reopened.save(changed, "first", ChangeSource.HUMAN, "retry").content)
            reopened.save(changed.copy(name = "第三版"), "retry", ChangeSource.HUMAN, "third")
            assertEquals("retry", reopened.save(changed, "first", ChangeSource.HUMAN, "retry").revision)
            assertEquals("third", reopened.open(original.id)!!.revision)
            assertThrows(IllegalArgumentException::class.java) { reopened.save(changed.copy(name = "冒用编号"), "first", ChangeSource.HUMAN, "retry") }
        }
    }

    @Test fun `两个存储实例竞争同一修订只有一个保存成功`() {
        val root = temporary.newFolder().toPath(); val store = CardStore(root); val original = card(store)
        store.save(original, null, ChangeSource.HUMAN, "first")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { index -> executor.submit<Boolean> {
                try { CardStore(root).save(original.copy(name = "版本$index"), "first", ChangeSource.AI, "c$index"); true }
                catch (_: RevisionConflict) { false }
            } }
            assertEquals(1, tasks.count { it.get() })
        } finally { executor.shutdownNow() }
    }

    @Test fun `库摘要和结构重开不加载正文`() {
        val root = temporary.newFolder().toPath(); val store = CardStore(root); val original = card(store)
        store.save(original, null, ChangeSource.HUMAN, "first")
        assertTrue(root.resolve("contents").toFile().deleteRecursively())
        val reopened = CardStore(root)
        assertEquals(original.id, reopened.list().single().id)
        assertEquals(original, reopened.open(original.id)!!.content)
        // 结构可读不冒充正文可读：下一次保存仍必须完整验证内容。
        assertThrows(IOException::class.java) { reopened.save(original, "first", ChangeSource.HUMAN, "new") }
    }

    @Test fun `独立进程在提交边界直接退出后锁释放且重试不重复提交`() {
        val classpath = listOf(CardStoreCrashChild::class.java, CardStore::class.java,
            ContentDocument::class.java, org.json.JSONObject::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            .joinToString(java.io.File.pathSeparator)
        for (phase in listOf("revision-written", "head-published")) {
            val root = temporary.newFolder().toPath(); val store = CardStore(root); val original = card(store)
            store.save(original, null, ChangeSource.HUMAN, "first")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CardStoreCrashChild::class.java.name, root.toString(), original.id, phase)
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start()
            try {
                assertTrue("子进程未按预期退出", process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(java.nio.file.Files.readString(root.resolve("child.log")), 73, process.exitValue())
            } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
            val reopened = CardStore(root)
            assertEquals(if (phase == "revision-written") "first" else "child", reopened.open(original.id)!!.revision)
            reopened.save(original.copy(name = "子进程修改"), "first", ChangeSource.HUMAN, "child")
            assertEquals("child", reopened.open(original.id)!!.revision)
            assertEquals("first", reopened.open(original.id)!!.parentRevision)
            assertEquals(1, reopened.list().size)
        }
    }
}

/** 子进程在真实文件写入后终止，不执行 finally（最终清理）来模拟进程死亡。 */
object CardStoreCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val store = CardStore(Path.of(args[0]))
        val current = store.open(args[1])!!
        store.save(current.content.copy(name = "子进程修改"), "first", ChangeSource.HUMAN, "child") {
            if (it == args[2]) Runtime.getRuntime().halt(73)
        }
        error("没有到达指定中断点")
    }
}
