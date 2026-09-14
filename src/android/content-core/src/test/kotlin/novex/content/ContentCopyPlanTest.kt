package novex.content

import org.junit.Assert.*
import org.junit.Test

class ContentCopyPlanTest {
    private val role = ContentDocument("role", CardKind.CHARACTER, "角色",
        modules = listOf(ContentModule("module", "图文", listOf(
            ContentBlock.Text("text", ContentRef("text-content")),
            ContentBlock.Image("image-block", "image", ContentRef("caption")),
            ContentBlock.Image("second-placement", "image"),
        ))),
        resources = listOf(
            CardResource("image", ContentRef("image-bytes"), "image/png"),
            CardResource("spare", ContentRef("unused-image"), "image/png"),
        ), extensions = mapOf("unrecognized" to ContentRef("extension-original")),
        appearance = CardAppearance(avatarResourceId = "image", coverResourceId = "spare"),
    )

    private fun plan(card: ContentDocument): ContentCopyPlan {
        var objectIndex = 0
        var contentIndex = 0
        return ContentCopies.plan(card, { "copy-${objectIndex++}" }, { ContentRef("new-content-${contentIndex++}") })
    }

    @Test fun `复制包括正文图注备用图片及未知扩展`() {
        val plan = plan(role)
        assertEquals(setOf("text-content", "image-bytes", "unused-image", "caption", "extension-original"),
            plan.transfers.map { it.source.value }.toSet())
        assertEquals(plan.objectIds.getValue("role"), plan.candidate.id)
        val copiedBlocks = plan.candidate.modules.single().blocks
        val image = copiedBlocks[1] as ContentBlock.Image
        assertEquals(plan.candidate.resources.first().id, image.resourceId)
        assertEquals(image.resourceId, (copiedBlocks[2] as ContentBlock.Image).resourceId)
        assertEquals(5, plan.transfers.size)
        assertEquals(plan.candidate.resources.first().id, plan.candidate.appearance.avatarResourceId)
        assertEquals(plan.candidate.resources.last().id, plan.candidate.appearance.coverResourceId)
        assertEquals(ContentRef("extension-original"), role.extensions["unrecognized"])
    }

    @Test fun `世界内部角色独立编号并保留图片归属`() {
        val world = ContentDocument("world", CardKind.WORLD, "世界", internalCharacters = listOf(role))
        val plan = plan(world)
        val copiedRole = plan.candidate.internalCharacters.single()
        assertNotEquals(role.id, copiedRole.id)
        assertTrue(plan.candidate.resources.isEmpty())
        assertEquals(role.resources.size, copiedRole.resources.size)
        val changedRole = ContentChanges.apply(copiedRole, ContentChange.RemoveModule(copiedRole.modules.single().id))
        assertTrue(changedRole.modules.isEmpty())
        assertEquals(1, role.modules.size)
    }

    @Test fun `封面不能指向其他卡的资源`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentDocument("world", CardKind.WORLD, "世界", internalCharacters = listOf(role),
                appearance = CardAppearance(coverResourceId = "image")).validate()
        }
    }

    @Test fun `重用来源或目标编号时拒绝复制计划`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentCopies.plan(role, { "role" }, { ContentRef("fresh") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentCopies.plan(role, { "same" }, { ContentRef("fresh") })
        }
        var next = 0
        assertThrows(IllegalArgumentException::class.java) {
            ContentCopies.plan(role, { "fresh-${next++}" }, { ContentRef("text-content") })
        }
    }
}
