package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleType
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterPagePolicyTest {
    @Test
    fun characterVersionOffersExactlyTheConfirmedOptionalModules() {
        assertEquals(
            listOf(
                ContentModuleType.QUOTES,
                ContentModuleType.WORLD_EXPERIENCE,
                ContentModuleType.ATTRIBUTE_PANEL,
                ContentModuleType.EQUIPMENT,
                ContentModuleType.TALENT_SKILL,
                ContentModuleType.APPEARANCE_PERSONALITY,
                ContentModuleType.INTEREST,
                ContentModuleType.CUSTOM,
            ),
            CHARACTER_PAGE_MODULE_TYPES,
        )
    }

    @Test
    fun customAttributesAndOcRelationshipsUseOneLinePerEntry() {
        assertEquals(
            listOf("力量" to "12", "阵营" to "中立"),
            parseCharacterAttributes("力量：12\n阵营:中立").map { it.name to it.value },
        )
        val relation = parseCharacterRelationships("莉莉丝｜宿敌｜争夺雾港控制权").single()
        assertEquals("莉莉丝", relation.characterName)
        assertEquals("宿敌", relation.relationship)
        assertEquals("争夺雾港控制权", relation.description)
    }
}
