package novex.content

import org.junit.Assert.*
import org.junit.Test

class ContentDocumentTest {
    private val original = ContentDocument("role", CardKind.CHARACTER, "同名人物",
        modules = listOf(
            ContentModule("first", "同名模块", listOf(ContentBlock.Text("text", ContentRef("original-text")))),
            ContentModule("second", "同名模块", listOf(ContentBlock.Image("picture", "image"))),
        ), resources = listOf(CardResource("image", ContentRef("owned-image"), "image/png")),
        extensions = mapOf("unknown" to ContentRef("original-extension")))

    @Test fun `同名模块按编号排序且不丢扩展和资源`() {
        val moved = ContentChanges.apply(original, ContentChange.MoveModule("second", "first"))
        assertEquals(listOf("second", "first"), moved.modules.map { it.id })
        assertEquals(original.resources, moved.resources)
        assertEquals(original.extensions, moved.extensions)
        assertEquals(listOf("first", "second"), original.modules.map { it.id })
    }

    @Test fun `移除展示模块不删除图片本体`() {
        val removed = ContentChanges.apply(original, ContentChange.RemoveModule("second"))
        assertEquals(1, removed.modules.size)
        assertEquals(original.resources, removed.resources)
    }

    @Test fun `内部角色图片不能误指向世界资源`() {
        val child = original.copy(resources = emptyList())
        val world = ContentDocument("world", CardKind.WORLD, "世界", resources = original.resources,
            internalCharacters = listOf(child))
        assertThrows(IllegalArgumentException::class.java) { world.validate() }
    }

    @Test fun `错误变更不改变原候选且重复块编号被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentChanges.apply(original, ContentChange.MoveModule("first", "missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentChanges.apply(original, ContentChange.AddModule(ContentModule("third", "新增", original.modules[0].blocks)))
        }
        assertEquals(listOf("first", "second"), original.modules.map { it.id })
    }

    @Test fun `文字引用无需读取正文即可完成结构编辑`() {
        val huge = ContentModule("raw", "", listOf(ContentBlock.Text("raw-block", ContentRef("external-large-original"))))
        val added = ContentChanges.apply(original, ContentChange.AddModule(huge))
        assertEquals(huge, added.modules.last())
        assertEquals(original.extensions, added.extensions)
    }

    @Test fun `两卡共用变换但世界不能作为内部角色`() {
        val world = ContentDocument("world", CardKind.WORLD, "世界", internalCharacters = listOf(original))
        val updated = ContentChanges.apply(world, ContentChange.AddModule(ContentModule("world-module", "世界内容", emptyList())))
        assertEquals(listOf(original), updated.internalCharacters)
        assertThrows(IllegalArgumentException::class.java) {
            world.copy(internalCharacters = listOf(ContentDocument("nested-world", CardKind.WORLD, "错误"))).validate()
        }
    }
}
