package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files

class CardFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `原文接收完整保留但确认前不能导出正式卡且取消不归库`() {
        val store = CardStore(temporary.newFolder().toPath()); val files = CardFiles(store)
        val raw = "\uFEFF不认识的外部资料🌊\r\n".toByteArray()
        val draft = files.prepare(ByteArrayInputStream(raw), "原文", CardKind.WORLD)
        assertEquals(CardKind.WORLD, draft.content.kind)
        assertEquals("", draft.content.modules.single().name)
        assertTrue(store.list().isEmpty())
        val text = draft.content.modules.single().blocks.single() as ContentBlock.Text
        assertArrayEquals(raw, store.contents.open(text.content).use { it.readBytes() })
        val output = temporary.root.toPath().resolve("not-saved.zip")
        assertThrows(IllegalArgumentException::class.java) { files.export(draft.content.id, output) }
        assertFalse(Files.exists(output))
        CardDrafts(store).discard(draft.content.id, draft.version)
        assertTrue(CardDrafts(store).list().isEmpty());assertTrue(store.list().isEmpty())
    }

    @Test fun `识别自家交换包沿用原卡类并完整保留内部角色`() {
        val source = CardStore(temporary.newFolder().toPath())
        val draft = CardFiles(source).prepare(ByteArrayInputStream("人物设定".toByteArray()), "角色", CardKind.CHARACTER)
        val world = ContentDocument("world", CardKind.WORLD, "世界", internalCharacters = listOf(draft.content))
        source.save(world, null, ChangeSource.HUMAN, "first")
        val archive = temporary.root.toPath().resolve("world.zip");CardFiles(source).export(world.id, archive)
        val target = CardStore(temporary.newFolder().toPath())
        val imported = Files.newInputStream(archive).use { CardFiles(target).prepare(it, "不应改名", CardKind.CHARACTER) }
        assertEquals(CardKind.WORLD, imported.content.kind);assertEquals("世界", imported.content.name)
        assertEquals(1, imported.content.internalCharacters.size)
        assertTrue(target.list().isEmpty())
        assertEquals(ChangeSource.IMPORT, CardDrafts(target).commit(imported.content.id, imported.version).source)
        Files.delete(archive)
        val child = target.open(imported.content.id)!!.content.internalCharacters.single()
        val block = child.modules.single().blocks.single() as ContentBlock.Text
        assertEquals("人物设定", target.contents.open(block.content).bufferedReader().use { it.readText() })
        assertTrue(Files.list(target.directory.resolve("incoming")).use { it.count() == 0L })
    }

    @Test fun `空文件非法编码和残缺交换包失败不生成卡或草稿`() {
        val store = CardStore(temporary.newFolder().toPath());val files = CardFiles(store)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0x50,0x4b,3,4,0))) {
            assertThrows(Exception::class.java) { files.prepare(ByteArrayInputStream(bytes), "无效", CardKind.CHARACTER) }
            assertTrue(store.list().isEmpty());assertTrue(CardDrafts(store).list().isEmpty())
        }
    }
}
