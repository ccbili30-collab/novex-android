package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files

class CardCharactersTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun text(store: CardStore, value: String): ContentRef {
        val ref = store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"), ref))) { ByteArrayInputStream(value.toByteArray()) }
        return ref
    }
    private fun read(store: CardStore, ref: ContentRef) = store.contents.open(ref).bufferedReader().use { it.readText() }

    @Test fun `新建内部角色不进入普通库并随世界共同保存重开`() {
        val directory = temporary.newFolder().toPath(); val store = CardStore(directory)
        val drafts = CardDrafts(store)
        val world = drafts.create(ContentDocument("world", CardKind.WORLD, "港口"))
        val added = CardCharacters(store).create("world", world.version, "守塔人")
        assertTrue(store.list().isEmpty())
        val role = added.content.internalCharacters.single()
        val module = ContentModule("history", "经历", listOf(ContentBlock.Text("paragraph", text(store,"出生于港口"))))
        val content = ContentChanges.applyTo(added.content, role.id, ContentChange.AddModule(module))
        val edited = drafts.update("world", added.version, content, EditorPosition("history","paragraph",1,3))
        val reopenedDraft = CardDrafts(CardStore(directory)).read("world")!!
        assertEquals(edited, reopenedDraft)
        drafts.commit("world", edited.version)
        val reopened = CardStore(directory)
        assertEquals(listOf("world"), reopened.list().map { it.id })
        assertNull(reopened.open(role.id))
        assertEquals("出生于港口",read(reopened,(reopened.open("world")!!.content.internalCharacters.single().modules.single().blocks.single() as ContentBlock.Text).content))
    }

    @Test fun `来源修改后内部副本正文图注图片及扩展保持独立并完整交换`() {
        val store=CardStore(temporary.newFolder().toPath()); val drafts=CardDrafts(store)
        val original=text(store,"旧经历"); val caption=text(store,"海边"); val bytes=text(store,"测试资源字节"); val extension=text(store,"未知扩展原文")
        val source=ContentDocument("source",CardKind.CHARACTER,"守塔人",
            listOf(ContentModule("module","经历",listOf(ContentBlock.Text("body",original),ContentBlock.Image("picture","resource",caption)))),
            listOf(CardResource("resource",bytes,"image/png")),extensions=mapOf("external" to extension),appearance=CardAppearance("resource","resource"))
        val saved=store.save(source,null,ChangeSource.HUMAN,"initial")
        val world=drafts.create(ContentDocument("world",CardKind.WORLD,"港口"))
        val copied=CardCharacters(store).copy("world",world.version,"source")
        val role=copied.content.internalCharacters.single()
        assertNotEquals(source.id,role.id); assertNotEquals(source.modules.single().id,role.modules.single().id)
        assertNotEquals(original,(role.modules.single().blocks.first() as ContentBlock.Text).content)
        assertNotEquals(bytes,role.resources.single().content)
        assertEquals(role.resources.single().id,role.appearance.coverResourceId)
        val modified=ContentChanges.apply(source,ContentChange.ReplaceModule(source.modules.single().copy(blocks=listOf(ContentBlock.Text("body",text(store,"新的来源经历"))))))
        store.save(modified,saved.revision,ChangeSource.HUMAN,"source-changed")
        drafts.commit("world",copied.version)
        val archive=temporary.root.toPath().resolve("world.zip"); CardFiles(store).export("world",archive)
        val isolated=CardStore(temporary.newFolder().toPath())
        val imported=CardFiles(isolated).prepare(Files.newInputStream(archive),"ignored",CardKind.CHARACTER)
        CardDrafts(isolated).commit(imported.content.id,imported.version)
        val restored=isolated.open(imported.content.id)!!.content.internalCharacters.single()
        assertEquals("旧经历",read(isolated,(restored.modules.single().blocks.first() as ContentBlock.Text).content))
        assertEquals("海边",read(isolated,(restored.modules.single().blocks.last() as ContentBlock.Image).caption!!))
        assertEquals("测试资源字节",read(isolated,restored.resources.single().content))
        assertEquals("未知扩展原文",read(isolated,restored.extensions.getValue("external")))
        assertNotEquals(role.id,restored.id)
        assertEquals(restored.resources.single().id,restored.appearance.avatarResourceId)
    }

    @Test fun `拒绝过期复制错误卡类和跨作品编辑而保留原草稿`() {
        val store=CardStore(temporary.newFolder().toPath()); val drafts=CardDrafts(store)
        val world=drafts.create(ContentDocument("world",CardKind.WORLD,"港口"))
        val role=drafts.create(ContentDocument("role",CardKind.CHARACTER,"角色"))
        val service=CardCharacters(store)
        assertThrows(DraftConflict::class.java) { service.copy("world","old","role") }
        assertThrows(IllegalArgumentException::class.java) { service.create("role",role.version,"嵌套角色") }
        assertThrows(IllegalArgumentException::class.java) { ContentTargets.replace(world.content,role.content) }
        assertThrows(IllegalArgumentException::class.java) { service.copy("world",world.version,"role") }
        assertEquals(world,drafts.read("world")); assertEquals(role,drafts.read("role"))
    }
}
