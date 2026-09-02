package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
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
            ContentModuleCatalog.definitions(ContentModuleScope.CHARACTER_VERSION).map { it.type },
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

    @Test
    fun sparseCharacterPageOmitsBlankOptionalFacts() {
        assertEquals(
            emptyList<CharacterFact>(),
            visibleCharacterFacts(CharacterVersionProfile(name = "伊薇")),
        )
    }

    @Test
    fun richCharacterPageShowsOnlyFilledFactsInConfirmedOrder() {
        assertEquals(
            listOf(
                CharacterFact("标签", "魔法师 · 冒险者"),
                CharacterFact("种族", "精灵"),
                CharacterFact("职业", "星见师"),
                CharacterFact("简介", "在雾港追查失落星轨。"),
            ),
            visibleCharacterFacts(
                CharacterVersionProfile(
                    name = "伊薇",
                    tags = listOf("魔法师", "冒险者"),
                    race = "精灵",
                    occupation = "星见师",
                    summary = "在雾港追查失落星轨。",
                ),
            ),
        )
    }

    @Test
    fun versionSelectorStaysSecondaryButStillExplainsReusableVersions() {
        assertEquals("本体 · 2 个分身", characterVersionSelectorLabel(CharacterVersionKind.ORIGINAL, "本体", 2))
        assertEquals("赛博分身 · 2 个分身", characterVersionSelectorLabel(CharacterVersionKind.VARIANT, "赛博分身", 2))
        assertEquals("本体", characterVersionSelectorLabel(CharacterVersionKind.ORIGINAL, "", 0))
    }
}
