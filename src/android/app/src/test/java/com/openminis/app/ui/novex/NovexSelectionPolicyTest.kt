package com.openminis.app.ui.novex

import org.junit.Assert.*
import org.junit.Test

class NovexSelectionPolicyTest {
    @Test fun searchFindsDescriptionsAndGroupsWithoutRemovingDisabledChoices() {
        val options = listOf(
            NovexSelectionEntry("a", "云岚", description = "山海书院", group = "世界"),
            NovexSelectionEntry("b", "星海", group = "世界", enabled = false),
            NovexSelectionEntry("c", "苏晚晴", group = "角色"),
        )
        assertEquals(listOf("a"), filterNovexSelections(options, "书院").map { it.id })
        assertEquals(listOf("a", "b"), filterNovexSelections(options, " 世界 ").map { it.id })
        assertFalse(filterNovexSelections(options, "星海").single().enabled)
        assertTrue(filterNovexSelections(options, "不存在").isEmpty())
    }

    @Test fun disabledChoicesCannotChangeTheSelectedSet() {
        val selected = setOf("a")
        assertEquals(selected, toggleNovexSelection(selected, NovexSelectionEntry("b", "未就绪", enabled = false)))
        assertEquals(setOf("a", "b"), toggleNovexSelection(selected, NovexSelectionEntry("b", "世界")))
        assertEquals(emptySet<String>(), toggleNovexSelection(selected, NovexSelectionEntry("a", "角色")))
    }
}
