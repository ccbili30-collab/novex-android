package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class ExchangeLabTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `readable export preserves native content and separates named payloads`() {
        val source=StagedContentFiles(temporary.newFolder().toPath())
        val original=fixture(source)
        val path=temporary.root.toPath().resolve("readable.zip")
        ExchangeLab.write(path,original,source,readable=true)
        ZipFile(path.toFile()).use {zip->
            val names=zip.entries().asSequence().map {it.name}.toList()
            assertTrue(names.any {it.startsWith("正文/") && it.endsWith(".md")})
            assertTrue(names.any {it.startsWith("图片/")})
            assertFalse(names.any {it.startsWith("contents/")})
        }
        val destination=StagedContentFiles(temporary.newFolder().toPath())
        verify(ExchangeLab.read(path,destination),destination)
    }
    private fun fixture(files: StagedContentFiles): ContentDocument {
        val allocate = files.allocator(); val text = allocate(); val image = allocate(); val extra = allocate()
        val data = mapOf(text to "中文原文。\n".repeat(300_000).toByteArray(), image to byteArrayOf(1, 4, 8, -1), extra to "{\"保留\":[1,2,3]}".toByteArray())
        files.receive(data.keys.map { ContentTransfer(it, it) }) { ByteArrayInputStream(data.getValue(it)) }
        val role = ContentDocument("role", CardKind.CHARACTER, "同名角色", modules = listOf(
            ContentModule("second", "后创建但排在前面", listOf(ContentBlock.Image("image-block", "image", extra))),
            ContentModule("first", "原文", listOf(ContentBlock.Text("text-block", text))),
        ), resources = listOf(CardResource("image", image, "application/octet-stream")),
            extensions = mapOf("unknown-field" to extra), appearance = CardAppearance("image", "image"))
        return ContentDocument("world", CardKind.WORLD, "世界", internalCharacters = listOf(role), extensions = mapOf("world-extension" to extra))
    }

    private fun verify(world: ContentDocument, files: StagedContentFiles) {
        assertEquals("世界", world.name); assertEquals(CardKind.WORLD, world.kind)
        val role = world.internalCharacters.single(); assertEquals("同名角色", role.name)
        assertEquals(listOf("后创建但排在前面", "原文"), role.modules.map { it.name })
        val text = role.modules[1].blocks.single() as ContentBlock.Text
        assertEquals("中文原文。\n".repeat(300_000), files.open(text.content).bufferedReader().use { it.readText() })
        val image = role.modules[0].blocks.single() as ContentBlock.Image
        assertEquals(role.resources.single().id, image.resourceId)
        assertEquals(image.resourceId, role.appearance.avatarResourceId); assertEquals(image.resourceId, role.appearance.coverResourceId)
        assertArrayEquals(byteArrayOf(1, 4, 8, -1), files.open(role.resources.single().content).use { it.readBytes() })
        for (reference in listOf(image.caption!!, role.extensions.getValue("unknown-field"), world.extensions.getValue("world-extension"))) {
            assertEquals("{\"保留\":[1,2,3]}", files.open(reference).bufferedReader().use { it.readText() })
        }
    }

    @Test fun `大正文世界内部角色及扩展完整两次往返`() {
        val sourceRoot = temporary.newFolder("source").toPath(); val source = StagedContentFiles(sourceRoot)
        val original = fixture(source); val firstPackage = temporary.root.toPath().resolve("first.zip")
        ExchangeLab.write(firstPackage, original, source)
        val firstRoot = temporary.newFolder("first").toPath(); val firstStore = StagedContentFiles(firstRoot)
        val first = ExchangeLab.read(firstPackage, firstStore); verify(first, firstStore)
        assertNotEquals(original.id, first.id)
        val secondPackage = temporary.root.toPath().resolve("second.zip"); ExchangeLab.write(secondPackage, first, firstStore)
        val secondRoot = temporary.newFolder("second").toPath(); val second = ExchangeLab.read(secondPackage, StagedContentFiles(secondRoot))
        listOf(sourceRoot, firstRoot).forEach { root -> Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
        Files.delete(firstPackage); Files.delete(secondPackage)
        verify(second, StagedContentFiles(secondRoot))
    }

    private fun altered(input: Path, output: Path, omit: Boolean) {
        ZipFile(input.toFile()).use { zip -> ZipOutputStream(Files.newOutputStream(output)).use { target ->
            zip.entries().asSequence().forEach { entry ->
                if (!(omit && entry.name == "contents/0")) {
                    target.putNextEntry(ZipEntry(entry.name))
                    if (!omit && entry.name == "contents/0") target.write(byteArrayOf(9))
                    else zip.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        } }
    }

    @Test fun `缺失或篡改内容条目不会得到可用副本`() {
        val source = StagedContentFiles(temporary.newFolder().toPath()); val card = fixture(source)
        val input = temporary.root.toPath().resolve("source.zip"); ExchangeLab.write(input, card, source)
        for (omit in listOf(true, false)) {
            val broken = temporary.root.toPath().resolve("broken-$omit.zip"); altered(input, broken, omit)
            val root = temporary.newFolder().toPath(); val target = StagedContentFiles(root)
            if (omit) assertThrows(IllegalArgumentException::class.java) { ExchangeLab.read(broken, target) }
            else assertThrows(IOException::class.java) { ExchangeLab.read(broken, target) }
            Files.list(root.resolve("batches")).use { assertEquals(0, it.count()) }
        }
    }

    @Test fun `来源资源校验失败时删除半包且保留来源`() {
        val root = temporary.newFolder().toPath(); val source = StagedContentFiles(root); val card = fixture(source)
        val reference = card.internalCharacters.single().resources.single().content
        Files.write(root.resolve("batches").resolve(reference.value), byteArrayOf(7))
        val output = temporary.root.toPath().resolve("failed.zip")
        assertThrows(IOException::class.java) { ExchangeLab.write(output, card, source) }
        assertFalse(Files.exists(output)); assertTrue(Files.exists(root))
    }
    @Test fun `存档重定位保留全部对象编号且原导入仍产生独立副本`() {
        val source=StagedContentFiles(temporary.newFolder().toPath());val original=fixture(source)
        val zip=temporary.root.toPath().resolve("snapshot.zip");ExchangeLab.write(zip,original,source)
        val destination=StagedContentFiles(temporary.newFolder().toPath())
        val restored=ExchangeLab.restoreSnapshot(zip,destination);verify(restored,destination)
        assertEquals(original.id,restored.id)
        val oldRole=original.internalCharacters.single();val role=restored.internalCharacters.single()
        assertEquals(oldRole.id,role.id);assertEquals(oldRole.modules.map {it.id},role.modules.map {it.id})
        assertEquals(oldRole.modules.flatMap {it.blocks}.map {it.id},role.modules.flattenModules().flatMap {it.blocks}.map {it.id})
        assertEquals(oldRole.resources.map {it.id},role.resources.map {it.id});assertEquals(oldRole.appearance,role.appearance)
        assertNotEquals(oldRole.resources.single().content,role.resources.single().content)
        val again=ExchangeLab.read(zip,StagedContentFiles(temporary.newFolder().toPath()))
        assertNotEquals(original.id,again.id);assertNotEquals(oldRole.id,again.internalCharacters.single().id)
        assertNotEquals(oldRole.modules[0].id,again.internalCharacters.single().modules[0].id)
    }

}
