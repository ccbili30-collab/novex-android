package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class StagedContentFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `复制正文图片和扩展后删除来源文件重开副本仍完整`() {
        val sourceRoot = temporary.newFolder("source").toPath()
        val targetRoot = temporary.newFolder("target").toPath()
        val source = StagedContentFiles(sourceRoot)
        val target = StagedContentFiles(targetRoot)
        val allocate = source.allocator()
        val text = allocate(); val image = allocate(); val extension = allocate()
        val expected = mapOf(text to "完整正文🌊".toByteArray(), image to byteArrayOf(0, 1, -1, 42), extension to "{\"未知\":true}".toByteArray())
        source.receive(expected.keys.map { ContentTransfer(it, it) }) { ByteArrayInputStream(expected.getValue(it)) }
        val card = ContentDocument("card", CardKind.CHARACTER, "示例",
            modules = listOf(ContentModule("module", "", listOf(ContentBlock.Text("text", text), ContentBlock.Image("picture", "resource")))),
            resources = listOf(CardResource("resource", image, "application/octet-stream")), extensions = mapOf("unknown" to extension))
        var id = 0
        val plan = ContentCopies.plan(card, { "copy-${id++}" }, target.allocator())
        target.receive(plan.transfers, source::open)
        Files.walk(sourceRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        val reopened = StagedContentFiles(targetRoot)
        for (transfer in plan.transfers) assertArrayEquals(expected.getValue(transfer.source), reopened.open(transfer.destination).use { it.readBytes() })
    }

    @Test fun `第二份来源空流时第一份也不能作为成功批次读取`() {
        val root = temporary.newFolder().toPath(); val files = StagedContentFiles(root); val allocate = files.allocator()
        val first = allocate(); val second = allocate()
        val transfers = listOf(ContentTransfer(ContentRef("source-one"), first), ContentTransfer(ContentRef("source-two"), second))
        assertThrows(IOException::class.java) {
            files.receive(transfers) { if (it.value == "source-one") ByteArrayInputStream(byteArrayOf(1)) else null }
        }
        assertThrows(IOException::class.java) { files.open(first) }
        Files.list(root.resolve("pending")).use { assertEquals(0, it.count()) }
    }

    @Test fun `途中读取异常不发布且可以用同一计划重试`() {
        val files = StagedContentFiles(temporary.newFolder().toPath()); val ref = files.allocator()()
        val transfers = listOf(ContentTransfer(ContentRef("source"), ref))
        assertThrows(IOException::class.java) {
            files.receive(transfers) { object : InputStream() { override fun read(): Int = throw IOException("实验中断") } }
        }
        files.receive(transfers) { ByteArrayInputStream(byteArrayOf(2, 3)) }
        assertArrayEquals(byteArrayOf(2, 3), files.open(ref).use { it.readBytes() })
        assertThrows(IllegalArgumentException::class.java) { files.receive(transfers) { ByteArrayInputStream(byteArrayOf(9)) } }
        assertArrayEquals(byteArrayOf(2, 3), files.open(ref).use { it.readBytes() })
    }

    @Test fun `内容被篡改时读取失败且不能转存成成功副本`() {
        val root = temporary.newFolder().toPath(); val files = StagedContentFiles(root); val ref = files.allocator()()
        files.receive(listOf(ContentTransfer(ContentRef("source"), ref))) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        Files.write(root.resolve("batches").resolve(ref.value), byteArrayOf(8, 9))
        val target = StagedContentFiles(temporary.newFolder().toPath()); val destination = target.allocator()()
        assertThrows(IOException::class.java) { target.receive(listOf(ContentTransfer(ref, destination)), files::open) }
        assertThrows(IOException::class.java) { target.open(destination) }
    }
}
