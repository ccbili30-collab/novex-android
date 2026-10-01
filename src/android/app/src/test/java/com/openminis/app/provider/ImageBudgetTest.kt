package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [血统清剿 P3.7 行为钉] ImageBudget 预算语义——单图上限、消息累计掉尾、
 * 请求级「最新优先」规划与占位文案逐字钉死。
 */
class ImageBudgetTest {

    private fun kb(n: Int): ByteArray = ByteArray(n * 1024)

    // ── 消息级预算 ─────────────────────────────────────────────────────────

    @Test
    fun `message budget drops tail beyond cumulative cap`() {
        // 6 × 4KB 都低于单图 5MB 上限，但 6 张累计 24KB…改用大件：
        // 5MB 上限下单件不压缩，累计 25MB 上限第 6 张 5MB 件必被掉尾。
        val fiveMb = kb(5 * 1024)
        val result = ImageBudget.applyMessageBudget(List(6) { fiveMb })
        // 5 张 × 5MB = 25MB 达到上限；第 6 张掉尾。
        assertEquals(5, result.keptBytes.size)
        assertEquals(1, result.droppedCount)
        assertEquals(0, result.compressedCount)
        assertEquals(25L * 1024 * 1024, result.totalBytes)
        assertTrue(result.mutated)
    }

    @Test
    fun `message budget under cap keeps everything untouched`() {
        val parts = List(3) { kb(64) }
        val result = ImageBudget.applyMessageBudget(parts)
        assertEquals(3, result.keptBytes.size)
        assertEquals(0, result.droppedCount)
        assertEquals(0, result.compressedCount)
        assertFalse(result.mutated)
    }

    @Test
    fun `per image ceiling constant contract`() {
        assertEquals(5L * 1024 * 1024, ImageBudget.MAX_PER_IMAGE_BYTES)
        assertEquals(25L * 1024 * 1024, ImageBudget.MAX_TOTAL_BYTES)
        assertEquals(25L * 1024 * 1024, ImageBudget.MAX_REQUEST_BYTES)
        assertEquals(2000, ImageBudget.MAX_EDGE_PX)
        assertEquals(80, ImageBudget.JPEG_QUALITY)
    }

    // ── 请求级规划 ─────────────────────────────────────────────────────────

    private fun img(data: ByteArray, path: String? = null) =
        ImageBudget.BudgetImage(data = data, linuxPath = path, mimeType = "image/png")

    @Test
    fun `request planner protects the latest images and elides the eldest`() {
        // 老→新 6 张 5MB（共 30MB > 25MB 上限）：最老那张必须被抹除。
        val eldest = kb(5 * 1024)
        val images = listOf(img(eldest, "/var/minis/attachments/old.png")) +
            (1..5).map { img(kb(5 * 1024), "/var/minis/attachments/new$it.png") }
        val plan = ImageBudget.planRequestBudget(images)
        assertEquals(1, plan.droppedCount)
        assertEquals(5, plan.totalCount - plan.droppedCount)
        val droppedId = ImageBudget.ImagePartId.of(eldest)
        assertTrue(plan.droppedIds.contains(droppedId))
        assertEquals("/var/minis/attachments/old.png", plan.droppedPaths[droppedId])
        assertEquals(25L * 1024 * 1024, plan.keptBytes)
        assertEquals(5L * 1024 * 1024, plan.elidedBytes)
        assertTrue(plan.mutated)
    }

    @Test
    fun `request planner clamps per-image charge at the single image cap`() {
        // 一张 8MB（超单图 5MB 上限）：计费按 5MB 收。
        val fat = kb(8 * 1024)
        val plan = ImageBudget.planRequestBudget(listOf(img(fat)))
        assertEquals(5L * 1024 * 1024, plan.keptBytes)
        assertEquals(0, plan.droppedCount)
    }

    @Test
    fun `empty plan is inert`() {
        val plan = ImageBudget.planRequestBudget(emptyList())
        assertEquals(0, plan.droppedCount)
        assertEquals(0, plan.totalCount)
        assertEquals(0L, plan.keptBytes)
        assertFalse(plan.mutated)
    }

    // ── 占位文案（模型面契约，逐字） ───────────────────────────────────────

    @Test
    fun `elided placeholder with path steers model to read_image`() {
        val text = ImageBudget.elidedImagePlaceholder("/var/minis/attachments/spillover/abc.png")
        assertEquals(
            "[image elided to fit 25MB request budget. Original at /var/minis/attachments/spillover/abc.png — " +
                "re-fetch with `read_image /var/minis/attachments/spillover/abc.png` if you need to see it.]",
            text,
        )
    }

    @Test
    fun `elided placeholder without path asks user to re-attach`() {
        assertEquals(
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; " +
                "ask the user to re-attach if needed.]",
            ImageBudget.elidedImagePlaceholder(null),
        )
    }
}
