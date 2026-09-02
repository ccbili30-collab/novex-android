package com.openminis.app.ui.novex

import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentModuleWorkspaceStateTest {
    @Test
    fun savedModulesOpenFoldedAndCanExpandIndependently() {
        val initial = ContentModuleWorkspaceState.fromSaved(
            listOf(module("map", position = 0, collapsed = false), module("timeline", position = 1)),
        )

        assertTrue(initial.expandedModuleIds.isEmpty())
        val bothExpanded = initial.toggleExpanded("map").toggleExpanded("timeline")
        assertEquals(setOf("map", "timeline"), bothExpanded.expandedModuleIds)
        assertEquals(setOf("timeline"), bothExpanded.toggleExpanded("map").expandedModuleIds)
    }

    @Test
    fun movingAndRemovingModulesKeepOneNormalizedDraftOrder() {
        val initial = ContentModuleWorkspaceState.fromSaved(
            listOf(module("timeline", 2), module("map", 0), module("faction", 1)),
        ).toggleExpanded("faction")

        assertEquals(listOf("map", "faction", "timeline"), initial.modules.map { it.id })
        val moved = initial.move("timeline", 0)
        assertEquals(listOf("timeline", "map", "faction"), moved.modules.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.modules.map { it.position })

        val removed = moved.remove("faction")
        assertEquals(listOf("timeline", "map"), removed.modules.map { it.id })
        assertEquals(listOf(0, 1), removed.modules.map { it.position })
        assertFalse("faction" in removed.expandedModuleIds)
    }

    @Test
    fun newlyAddedModulesOpenForEditingAndSavedValuesReplaceInPlace() {
        val initial = ContentModuleWorkspaceState.fromSaved(listOf(module("map", 0)))
        val added = initial.add(module("timeline", 99))

        assertEquals(listOf("map", "timeline"), added.modules.map { it.id })
        assertTrue("timeline" in added.expandedModuleIds)

        val renamed = added.modules.last().copy(name = "王朝时间线")
        val replaced = added.replace(renamed)
        assertEquals("王朝时间线", replaced.modules.last().name)
        assertEquals(listOf(0, 1), replaced.modules.map { it.position })
    }

    private fun module(
        id: String,
        position: Int,
        collapsed: Boolean = true,
    ) = ContentModuleEntity(
        id = id,
        ownerType = ModuleOwnerType.WORLD,
        ownerId = "world",
        type = when (id) {
            "map" -> ContentModuleType.MAP
            "timeline" -> ContentModuleType.TIMELINE
            else -> ContentModuleType.FACTION
        },
        name = id,
        position = position,
        collapsed = collapsed,
        createdAt = position.toLong(),
        updatedAt = position.toLong(),
    )
}
