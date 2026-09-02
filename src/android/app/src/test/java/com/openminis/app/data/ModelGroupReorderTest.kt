package com.openminis.app.data

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-modelgroup-reorder] Pins the ordering semantics of
 * `ProviderRepository.reorderModelGroups` (mirrors iOS
 * `ProviderConfigStore.reorderGroups`, 4ba54ff5).
 *
 * Same shape as ProviderReorderTest: the repository needs Context + Room, so
 * the production policy is kept pure and tested directly. It covers both
 * drag and explicit phone-menu moves while preserving hidden image groups.
 *
 * Persistence needs no separate assertion: ProviderConfigMapping writes each
 * group's `sort_order` from its list index at save time and the DAO reads
 * `ORDER BY sort_order ASC`, so list position IS the stored order.
 */
class ModelGroupReorderTest {
    @Test
    fun `a full permutation is applied verbatim`() {
        assertEquals(
            listOf("coding", "daily", "translate"),
            normalizeModelGroupOrder(
                current = listOf("daily", "coding", "translate"),
                newOrder = listOf("coding", "daily", "translate"),
            ),
        )
    }

    @Test
    fun `unknown ids are dropped rather than inserted`() {
        // A drag that raced a group deletion must not resurrect the group.
        val result = normalizeModelGroupOrder(current = listOf("a", "b"), newOrder = listOf("b", "ghost", "a"))
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `a group added concurrently is appended, not lost`() {
        // Drag committed an id list captured before "new" was created.
        val result = normalizeModelGroupOrder(current = listOf("a", "b", "new"), newOrder = listOf("b", "a"))
        assertEquals(listOf("b", "a", "new"), result)
    }

    @Test
    fun `duplicate ids in the incoming order are collapsed`() {
        assertEquals(
            listOf("b", "a", "c"),
            normalizeModelGroupOrder(current = listOf("a", "b", "c"), newOrder = listOf("b", "b", "a")),
        )
    }

    @Test
    fun `no group is ever lost or duplicated`() {
        val current = listOf("a", "b", "c", "d")
        val result = normalizeModelGroupOrder(current, newOrder = listOf("d", "ghost", "b"))
        assertEquals(current.sorted(), result.sorted())
        assertEquals(current.size, result.size)
    }

    @Test
    fun `menu move preserves hidden image group slots`() {
        val all = listOf("daily", "image-a", "coding", "image-b", "translate")
        val managed = listOf("daily", "coding", "translate")

        assertEquals(
            listOf("coding", "image-a", "daily", "image-b", "translate"),
            moveManagedModelGroup(all, managed, "coding", ModelGroupMove.TOP),
        )
        assertEquals(
            listOf("daily", "image-a", "translate", "image-b", "coding"),
            moveManagedModelGroup(all, managed, "coding", ModelGroupMove.BOTTOM),
        )
    }

    @Test
    fun `drag reorder preserves hidden image group slots`() {
        assertEquals(
            listOf("translate", "image-a", "daily", "image-b", "coding"),
            reorderManagedModelGroups(
                allGroupIds = listOf("daily", "image-a", "coding", "image-b", "translate"),
                managedGroupIds = listOf("daily", "coding", "translate"),
                fromGroupId = "translate",
                toGroupId = "daily",
            ),
        )
    }

    @Test
    fun `delete impact lists bindings and removal clears every reference`() {
        val target = ModelGroup(id = "target", name = "编程")
        val survivor = ModelGroup(id = "survivor", name = "日常")
        val config = ProviderConfig(
            modelGroups = mutableListOf(target, survivor),
            defaultPrimaryGroupId = target.id,
            defaultSubGroupId = target.id,
            voiceInputGroupId = target.id,
            voiceOutputGroupId = target.id,
            visionGroupId = target.id,
            agentLoopGroupIds = mutableListOf(target.id),
            imageGenerationGroupIds = mutableListOf(target.id),
        )

        val impact = config.modelGroupRemovalImpact(target.id)
        assertTrue(impact.groupExists)
        assertEquals(ModelGroupBinding.entries.toSet(), impact.bindings)

        val removed = config.removeModelGroupAndBindings(target.id)
        assertEquals(impact, removed)
        assertFalse(config.modelGroups.any { it.id == target.id })
        assertTrue(config.modelGroups.any { it.id == survivor.id })
        assertNull(config.defaultPrimaryGroupId)
        assertNull(config.defaultSubGroupId)
        assertNull(config.voiceInputGroupId)
        assertNull(config.voiceOutputGroupId)
        assertNull(config.visionGroupId)
        assertFalse(target.id in config.agentLoopGroupIds)
        assertFalse(target.id in config.imageGenerationGroupIds)
    }
}
