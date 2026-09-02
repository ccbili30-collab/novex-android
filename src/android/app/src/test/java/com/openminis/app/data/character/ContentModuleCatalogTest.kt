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
