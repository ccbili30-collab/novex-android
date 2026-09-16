package com.openminis.app.cards

import novex.runtime.ManagementTarget
import novex.runtime.SourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-prune-deleted-mounts] 挂载的卡片被删除后，绑定必须能剔掉失效的主卡/背景卡/
 * 管理项（否则发送被「采用的作品不存在」拦死）；全部存活时返回 null 不触发写回。
 */
class CardBindingPruneTest {
    private fun source(root: String) = SourceSelection(root, "$root-target")
    private fun managed(root: String) = ManagementTarget(root, "$root-target")

    @Test fun allAliveReturnsNull() {
        val binding = CardBinding(
            primary = source("a"),
            backgrounds = listOf(source("b")),
            managed = setOf(managed("c")),
        )
        assertNull(binding.prunedDeleted { true })
    }

    @Test fun dropsDeletedPrimaryBackgroundsAndManaged() {
        val binding = CardBinding(
            primary = source("dead"),
            backgrounds = listOf(source("alive"), source("gone")),
            managed = setOf(managed("alive"), managed("gone")),
            createdReceipts = setOf("r1"),
            overrides = mapOf("m1" to true),
        )
        val pruned = binding.prunedDeleted { it != "dead" && it != "gone" }!!
        assertEquals(source("alive"), pruned.primary)
        assertEquals(listOf(source("alive")), pruned.backgrounds)
        assertEquals(setOf(managed("alive")), pruned.managed)
        // 与挂载无关的字段原样保留。
        assertEquals(setOf("r1"), pruned.createdReceipts)
        assertEquals(mapOf("m1" to true), pruned.overrides)
    }

    @Test fun everythingDeletedLeavesEmptyBinding() {
        val binding = CardBinding(primary = source("dead"), backgrounds = listOf(source("dead")))
        val pruned = binding.prunedDeleted { false }!!
        assertNull(pruned.primary)
        assertEquals(emptyList<SourceSelection>(), pruned.backgrounds)
    }
}
