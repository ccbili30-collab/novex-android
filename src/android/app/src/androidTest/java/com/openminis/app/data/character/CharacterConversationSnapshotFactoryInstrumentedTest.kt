package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterConversationSnapshotFactoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var catalog: CharacterCatalogRepository
    private lateinit var modules: ContentModuleRepository
    private lateinit var media: MediaAssetRepository
    private lateinit var factory: CharacterConversationSnapshotFactory

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = CharacterCatalogRepository(database.characterCatalogDao())
        modules = ContentModuleRepository(database.contentModuleDao())
        media = MediaAssetRepository(database.mediaAssetDao()) { true }
        factory = CharacterConversationSnapshotFactory(catalog, modules, media)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun selectedWorldPersonaAndConcreteVersionBecomeImmutableConversationSnapshot() = runBlocking {
        val world = catalog.createWorld("霓虹城", "机械与魔法共存", now = 10, id = "world-1")
        val root = catalog.createCharacter(
            name = "伊薇",
            originalProfileJson = CharacterVersionProfile("伊薇本体").toJson(),
            now = 11,
            characterId = "character-1",
            originalVersionId = "original-1",
        )
        val variant = catalog.createVariant(
            characterId = root.character.id,
            label = "赛博分身",
            profileJson = CharacterVersionProfile(
                name = "赛博伊薇",
                tags = listOf("黑客"),
                occupation = "情报贩子",
                summary = "只相信可以验证的证据。",
                customAttributes = listOf(CharacterCustomAttribute("义眼", "夜视")),
                relationships = listOf(CharacterRelationship("阿澄", "搭档", "共同追查失踪案")),
            ).toJson(),
            now = 12,
            id = "variant-1",
        )
        catalog.addVersionToWorld(world.id, variant.id, position = 0, now = 13)
        modules.add(
            ModuleOwner.world(world.id),
            ContentModuleType.TIMELINE,
            "时间线",
            JSONObject().put("text", "新历 42 年城墙断电").toString(),
            now = 14,
            id = "world-module",
        )
        modules.add(
            ModuleOwner.characterVersion(variant.id),
            ContentModuleType.APPEARANCE_PERSONALITY,
            "外貌性格",
            JSONObject().put("text", "银发，冷静克制").toString(),
            now = 15,
            id = "role-module",
        )
        val worldBackground = media.register("/managed/world.webp", "image/webp", "world-bg", now = 16)
        val avatar = media.register("/managed/eve.png", "image/png", "eve-avatar", now = 17)
        val pageBackground = media.register("/managed/eve-bg.webp", "image/webp", "eve-bg", now = 18)
        media.attach(ModuleOwner.world(world.id), MediaAssetSlot.WORLD_BACKGROUND, worldBackground.id)
        media.attach(ModuleOwner.characterVersion(variant.id), MediaAssetSlot.CHARACTER_AVATAR, avatar.id)
        media.attach(
            ModuleOwner.characterVersion(variant.id),
            MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
            pageBackground.id,
        )
        val persona = PlayerPersona(
            id = "persona-1",
            name = "阿澄",
            worldId = world.id,
            description = "调查员",
            createdAt = 19,
            updatedAt = 19,
        )

        val snapshot = factory.create(world.id, variant.id, persona)

        assertEquals(world.id, snapshot.worldId)
        assertEquals(variant.id, snapshot.characterVersionId)
        assertEquals(variant.id, snapshot.profile.character?.id)
        assertEquals(persona.id, snapshot.profile.persona?.id)
        assertEquals("/managed/eve.png", snapshot.profile.character?.avatarPath)
        assertEquals("/managed/eve-bg.webp", snapshot.profile.character?.defaultBackgroundPath)
        assertEquals("/managed/eve-bg.webp", snapshot.profile.backgroundPath)
        val prompt = CharacterPromptComposer.compose(
            snapshot.profile.character?.toJson()?.toString(),
            snapshot.profile.persona?.toJson()?.toString(),
            snapshot.profile.world?.toJson()?.toString(),
        ).orEmpty()
        assertTrue(prompt.contains("新历 42 年城墙断电"))
        assertTrue(prompt.contains("银发，冷静克制"))
        assertTrue(prompt.contains("义眼：夜视"))
        assertTrue(prompt.contains("阿澄：搭档"))

        val session = ChatRepository(database.chatDao()).createSession(
            modelId = "model-1",
            worldId = snapshot.worldId,
            characterVersionId = snapshot.characterVersionId,
            characterId = snapshot.profile.character?.id,
            characterSnapshotJson = snapshot.profile.character?.toJson()?.toString(),
            worldSnapshotJson = snapshot.profile.world?.toJson()?.toString(),
            personaId = snapshot.profile.persona?.id,
            personaSnapshotJson = snapshot.profile.persona?.toJson()?.toString(),
        )
        assertEquals(world.id, database.chatDao().getSession(session.id)?.worldId)
        assertEquals(variant.id, database.chatDao().getSession(session.id)?.characterVersionId)
    }

    @Test
    fun migratedLegacyVersionKeepsExistingMediaUntilManagedAssetsReplaceIt() = runBlocking {
        val legacyWorld = StoryWorld(
            id = "legacy-world",
            name = "旧世界",
            description = "旧世界概述",
            backgroundPath = "/legacy/world-bg.png",
            createdAt = 10,
            updatedAt = 11,
        )
        database.characterCatalogDao().insertWorld(
            WorldEntity(
                id = legacyWorld.id,
                name = legacyWorld.name,
                overview = legacyWorld.description,
                legacySnapshotJson = legacyWorld.toJson().toString(),
                createdAt = legacyWorld.createdAt,
                updatedAt = legacyWorld.updatedAt,
            ),
        )
        val legacyCard = CharacterCard(
            id = "legacy-role",
            name = "旧角色",
            worldId = legacyWorld.id,
            systemPrompt = "保留旧角色提示词",
            tags = listOf("旧标签"),
            avatarPath = "/legacy/avatar.png",
            coverPath = "/legacy/page-bg.png",
            defaultBackgroundPath = "/legacy/chat-bg.png",
            createdAt = 12,
            updatedAt = 13,
        )
        val character = catalog.createCharacter(
            name = legacyCard.name,
            originalProfileJson = legacyCard.toJson().toString(),
            now = legacyCard.createdAt,
            characterId = legacyCard.id,
            originalVersionId = legacyCard.id,
        )
        catalog.addVersionToWorld(legacyWorld.id, character.original.id, position = 0, now = 14)

        val snapshot = factory.create(legacyWorld.id, character.original.id, null).profile

        assertEquals("/legacy/world-bg.png", snapshot.world?.backgroundPath)
        assertEquals("/legacy/avatar.png", snapshot.character?.avatarPath)
        assertEquals("/legacy/page-bg.png", snapshot.character?.coverPath)
        assertEquals("/legacy/chat-bg.png", snapshot.character?.defaultBackgroundPath)
        assertEquals("/legacy/chat-bg.png", snapshot.backgroundPath)
        assertEquals("保留旧角色提示词", snapshot.character?.systemPrompt)
        assertEquals(listOf("旧标签"), snapshot.character?.tags)
    }

    @Test
    fun versionAndPersonaMustBelongToSelectedWorld() = runBlocking {
        val world = catalog.createWorld("世界甲", now = 10, id = "world-a")
        val otherWorld = catalog.createWorld("世界乙", now = 11, id = "world-b")
        val character = catalog.createCharacter("伊薇", now = 12, characterId = "role", originalVersionId = "original")
        catalog.addVersionToWorld(otherWorld.id, character.original.id, position = 0, now = 13)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { factory.create(world.id, character.original.id, null) }
        }
        catalog.addVersionToWorld(world.id, character.original.id, position = 0, now = 14)
        val wrongPersona = PlayerPersona(
            id = "persona-b",
            name = "异世界身份",
            worldId = otherWorld.id,
            createdAt = 15,
            updatedAt = 15,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { factory.create(world.id, character.original.id, wrongPersona) }
        }
        Unit
    }
}
