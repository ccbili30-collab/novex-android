package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentModuleRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var catalog: CharacterCatalogRepository
    private lateinit var modules: ContentModuleRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = CharacterCatalogRepository(database.characterCatalogDao())
        modules = ContentModuleRepository(database.contentModuleDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun worldAndCharacterVersionShareAddRenameCollapseReorderAndDelete() = runBlocking {
        val world = catalog.createWorld("蒸汽城", now = 10)
        val character = catalog.createCharacter("伊薇", now = 20)
        val worldOwner = ModuleOwner.world(world.id)
        val characterOwner = ModuleOwner.characterVersion(character.original.id)

        val timeline = modules.add(
            owner = worldOwner,
            type = ContentModuleType.TIMELINE,
            name = "时间线",
            now = 30,
        )
        val region = modules.add(
            owner = worldOwner,
            type = ContentModuleType.REGION,
            name = "地区设定",
            now = 31,
        )
        val custom = modules.add(
            owner = worldOwner,
            type = ContentModuleType.CUSTOM,
            name = "蒸汽技术",
            now = 32,
        )
        val equipment = modules.add(
            owner = characterOwner,
            type = ContentModuleType.EQUIPMENT,
            name = "随身装备",
            now = 40,
        )

        modules.rename(custom.id, "核心技术", now = 50)
        modules.setCollapsed(region.id, collapsed = true, now = 51)
        modules.move(region.id, toIndex = 0, now = 52)

        val worldModules = modules.list(worldOwner)
        assertEquals(listOf(region.id, timeline.id, custom.id), worldModules.map { it.id })
        assertEquals(listOf(0, 1, 2), worldModules.map { it.position })
        assertTrue(worldModules.first().collapsed)
        assertEquals("核心技术", worldModules.last().name)
        assertEquals(listOf(equipment.id), modules.list(characterOwner).map { it.id })

        modules.delete(timeline.id)
        val afterDelete = modules.list(worldOwner)
        assertEquals(listOf(region.id, custom.id), afterDelete.map { it.id })
        assertEquals(listOf(0, 1), afterDelete.map { it.position })
    }

    @Test
    fun referencesCanTargetModulesWorldsAndVersionsWithoutDuplicatingContent() = runBlocking {
        val world = catalog.createWorld("蒸汽城", now = 10)
        val character = catalog.createCharacter("伊薇", now = 20)
        val worldOwner = ModuleOwner.world(world.id)
        val versionOwner = ModuleOwner.characterVersion(character.original.id)
        val event = modules.add(worldOwner, ContentModuleType.ERA_EVENT, "机械纪元", now = 30)
        val region = modules.add(worldOwner, ContentModuleType.REGION, "雾港", now = 31)
        val experience = modules.add(
            versionOwner,
            ContentModuleType.WORLD_EXPERIENCE,
            "世界经历",
            now = 32,
        )

        modules.addReference(event.id, ModuleReferenceTarget.module(region.id), position = 0)
        modules.addReference(event.id, ModuleReferenceTarget.characterVersion(character.original.id), position = 1)
        modules.addReference(experience.id, ModuleReferenceTarget.world(world.id), position = 0)

        assertEquals(
            listOf(
                ModuleReferenceTarget.module(region.id),
                ModuleReferenceTarget.characterVersion(character.original.id),
            ),
            modules.references(event.id).map { it.target },
        )
        assertEquals(
            listOf(ModuleReferenceTarget.world(world.id)),
            modules.references(experience.id).map { it.target },
        )

        modules.delete(region.id)
        assertFalse(modules.references(event.id).any { it.target.id == region.id })
    }
}
