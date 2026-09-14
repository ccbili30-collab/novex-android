package novex.content

import org.junit.Assert.*
import org.junit.Test

class BlockEditsTest {
    private val role = ContentDocument("role", CardKind.CHARACTER, "角色", modules = listOf(
        ContentModule("one", "同名模块", listOf(ContentBlock.Text("text", ContentRef("original")), ContentBlock.Image("pic", "image"))),
        ContentModule("two", "同名模块", emptyList()),
    ), resources = listOf(CardResource("image", ContentRef("pixels"), "image/png")))

    @Test fun `图文跨模块移动保持块编号和资源`() {
        val result = ContentChanges.apply(role, ContentChange.EditBlocks(listOf(
            BlockEdit.Move("pic", "two"),
            BlockEdit.Insert("two", ContentBlock.Text("new-text", ContentRef("addition")), "pic"),
        )))
        assertEquals(listOf("text"), result.modules[0].blocks.map { it.id })
        assertEquals(listOf("new-text", "pic"), result.modules[1].blocks.map { it.id })
        assertEquals(role.resources, result.resources)
        assertEquals(listOf("text", "pic"), role.modules[0].blocks.map { it.id })
    }

    @Test fun `后项失败不会把前项变更施加给原文档`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentChanges.apply(role, ContentChange.EditBlocks(listOf(
                BlockEdit.Move("pic", "two"),
                BlockEdit.Insert("missing", ContentBlock.Text("new", ContentRef("addition"))),
            )))
        }
        assertEquals(2, role.modules[0].blocks.size)
        assertTrue(role.modules[1].blocks.isEmpty())
    }

    @Test fun `删除在用图片资源被拒绝移除展示后可以删除`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentChanges.apply(role, ContentChange.RemoveResource("image"))
        }
        val hidden = ContentChanges.apply(role, ContentChange.EditBlocks(listOf(BlockEdit.Remove("pic"))))
        assertEquals(1, hidden.resources.size)
        val deleted = ContentChanges.apply(hidden, ContentChange.RemoveResource("image"))
        assertTrue(deleted.resources.isEmpty())
    }

    @Test fun `指定世界内角色操作不会改到世界本身或其他角色`() {
        val other = ContentDocument("other", CardKind.CHARACTER, "角色")
        val world = ContentDocument("world", CardKind.WORLD, "世界", internalCharacters = listOf(role, other))
        val result = ContentChanges.applyTo(world, "role", ContentChange.EditBlocks(listOf(
            BlockEdit.Replace("text", ContentBlock.Text("text", ContentRef("edited"))),
        )))
        assertEquals(ContentRef("edited"), (result.internalCharacters[0].modules[0].blocks[0] as ContentBlock.Text).content)
        assertEquals(other, result.internalCharacters[1])
        assertTrue(result.modules.isEmpty())
        assertEquals(ContentRef("original"), (role.modules[0].blocks[0] as ContentBlock.Text).content)
    }
}
