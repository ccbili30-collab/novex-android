package com.openminis.app.data.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentModuleCatalogTest {
    @Test
    fun worldAndCharacterDefinitionsHaveOneSharedOrderedSource() {
        assertEquals(
            listOf(
                ContentModuleType.TIMELINE,
                ContentModuleType.ERA_EVENT,
                ContentModuleType.MAP,
                ContentModuleType.REGION,
                ContentModuleType.FACTION,
                ContentModuleType.RACE,
                ContentModuleType.CUSTOM,
            ),
            ContentModuleCatalog.definitions(ContentModuleScope.WORLD).map { it.type },
        )
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
        assertEquals("时代与事件", ContentModuleCatalog.definition(ContentModuleType.ERA_EVENT).displayName)
        assertEquals("世界经历", ContentModuleCatalog.definition(ContentModuleType.WORLD_EXPERIENCE).displayName)
        assertEquals(
            listOf(
                ContentModuleType.GAME_PLAYER_IDENTITY,
                ContentModuleType.GAME_OPENING,
                ContentModuleType.GAME_NARRATIVE_RULES,
                ContentModuleType.GAME_POWER_SYSTEM,
                ContentModuleType.GAME_ATTRIBUTES,
                ContentModuleType.GAME_SKILLS,
                ContentModuleType.GAME_EQUIPMENT,
                ContentModuleType.GAME_ITEMS,
                ContentModuleType.GAME_QUESTS,
                ContentModuleType.GAME_CHECKS,
                ContentModuleType.GAME_ENDINGS,
                ContentModuleType.GAME_CHARACTER_STATUS,
                ContentModuleType.GAME_QUICK_ACTIONS,
                ContentModuleType.CUSTOM,
            ),
            ContentModuleCatalog.definitions(ContentModuleScope.INTERACTIVE_FICTION).map { it.type },
        )
    }

    @Test
    fun everyBuiltInModuleIsUniqueButCustomModulesRemainRepeatable() {
        assertFalse(ContentModuleCatalog.definition(ContentModuleType.MAP).repeatable)
        assertTrue(ContentModuleCatalog.definition(ContentModuleType.CUSTOM).repeatable)

        val available = ContentModuleCatalog.availableToAdd(
            scope = ContentModuleScope.WORLD,
            existingTypes = listOf(ContentModuleType.TIMELINE, ContentModuleType.CUSTOM),
        )

        assertFalse(available.any { it.type == ContentModuleType.TIMELINE })
        assertTrue(available.any { it.type == ContentModuleType.MAP })
        assertTrue(available.any { it.type == ContentModuleType.CUSTOM })
    }

    @Test
    fun ownerTypeSelectsTheCorrectCatalogWithoutAllowingNestedContentModules() {
        assertEquals(ContentModuleScope.WORLD, ContentModuleCatalog.scopeFor(ModuleOwnerType.WORLD))
        assertEquals(
            ContentModuleScope.CHARACTER_VERSION,
            ContentModuleCatalog.scopeFor(ModuleOwnerType.CHARACTER_VERSION),
        )
        assertEquals(
            ContentModuleScope.INTERACTIVE_FICTION,
            ContentModuleCatalog.scopeFor(ModuleOwnerType.INTERACTIVE_FICTION),
        )
        assertEquals(null, ContentModuleCatalog.scopeFor(ModuleOwnerType.CONTENT_MODULE))
    }

    @Test
    fun aCharacterOnlyModuleCannotBeAddedToAWorld() {
        val failure = runCatching {
            ContentModuleCatalog.requireCanAdd(
                scope = ContentModuleScope.WORLD,
                type = ContentModuleType.QUOTES,
                existingTypes = emptyList(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
