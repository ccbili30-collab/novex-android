package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.security.MessageDigest
import java.io.InputStream

class RawTextImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun receive(files: StagedContentFiles, bytes: ByteArray): ContentRef {
        val ref = files.allocator()()
        files.receive(listOf(ContentTransfer(ContentRef("input"), ref))) { ByteArrayInputStream(bytes) }
        return ref
    }

    @Test fun `未知结构原文原样进入无标题自由模块并可新增模块`() {
        val files = StagedContentFiles(temporary.newFolder().toPath())
        val raw = "\uFEFF{\r\n  \"不认识的字段\": [1, 2],\r\n  \"原文\": \"  海边🌊  \"\r\n}\r\n".toByteArray()
        val original = receive(files, raw)
        for (kind in CardKind.entries) {
            val card = RawTextImport.prepareUtf8(original, files, kind, "导入作品")
            assertEquals(kind, card.kind); assertEquals("", card.modules.single().name)
            val text = card.modules.single().blocks.single() as ContentBlock.Text
            assertEquals(original, text.content)
            assertArrayEquals(raw, files.open(text.content).use { it.readBytes() })
            val edited = ContentChanges.apply(card, ContentChange.AddModule(ContentModule("new-module", "我自己新增", emptyList())))
            assertEquals(2, edited.modules.size)
            assertEquals(original, edited.extensions[RawTextImport.ORIGINAL])
        }
    }

    @Test fun `修改正文后原件仍保留并可随交换包还原`() {
        val source = StagedContentFiles(temporary.newFolder().toPath())
        val original = receive(source, "原始内容\r\n".toByteArray())
        val card = RawTextImport.prepareUtf8(original, source, CardKind.CHARACTER, "角色")
        val changed = receive(source, "人工编辑后的内容".toByteArray())
        val block = card.modules.single().blocks.single() as ContentBlock.Text
        val edited = ContentChanges.apply(card, ContentChange.EditBlocks(listOf(BlockEdit.Replace(block.id, block.copy(content = changed)))))
        val path = temporary.root.toPath().resolve("edited.zip")
        ExchangeLab.write(path, edited, source)
        val target = StagedContentFiles(temporary.newFolder().toPath()); val restored = ExchangeLab.read(path, target)
        val restoredText = restored.modules.single().blocks.single() as ContentBlock.Text
        assertEquals("人工编辑后的内容", target.open(restoredText.content).bufferedReader().use { it.readText() })
        assertEquals("原始内容\r\n", target.open(restored.extensions.getValue(RawTextImport.ORIGINAL)).bufferedReader().use { it.readText() })
    }

    @Test fun `非法编码明确失败并保留原始字节而非替换成乱码`() {
        val root = temporary.newFolder().toPath(); val files = StagedContentFiles(root)
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        val original = receive(files, bytes)
        assertThrows(CharacterCodingException::class.java) {
            RawTextImport.prepareUtf8(original, files, CardKind.WORLD, "失败输入")
        }
        assertArrayEquals(bytes, files.open(original).use { it.readBytes() })
    }

    @Test fun `超过四兆的原文两卡导入交换后脱离来源完整读取`() {
        val input = temporary.root.toPath().resolve("large.txt")
        val line = "原文不分配到概述，也不要求模型转换🌊\r\n".toByteArray(Charsets.UTF_8)
        Files.newOutputStream(input).use { output -> repeat(100_000) { output.write(line) } }
        val expectedSize = Files.size(input)
        assertTrue(expectedSize > 4 * 1024 * 1024)
        val expectedHash = Files.newInputStream(input).use(::digest)
        for (kind in CardKind.entries) {
            val sourceRoot = temporary.newFolder().toPath()
            val source = StagedContentFiles(sourceRoot)
            val original = source.allocator()()
            source.receive(listOf(ContentTransfer(ContentRef("external"), original))) { Files.newInputStream(input) }
            val card = RawTextImport.prepareUtf8(original, source, kind, "大原文")
            val archive = temporary.root.toPath().resolve("${kind.name}.zip")
            ExchangeLab.write(archive, card, source)
            val targetRoot = temporary.newFolder().toPath()
            val restored = ExchangeLab.read(archive, StagedContentFiles(targetRoot))
            sourceRoot.toFile().deleteRecursively().also { assertTrue(it) }
            Files.delete(archive)
            val text = restored.modules.single().blocks.single() as ContentBlock.Text
            assertEquals("", restored.modules.single().name)
            assertEquals(text.content, restored.extensions[RawTextImport.ORIGINAL])
            val reopened = StagedContentFiles(targetRoot)
            assertArrayEquals(expectedHash, reopened.open(text.content).use(::digest))
            reopened.open(text.content).use { stream ->
                var count = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    count += read
                }
                assertEquals(expectedSize, count)
            }
        }
    }

    private fun digest(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }
}
