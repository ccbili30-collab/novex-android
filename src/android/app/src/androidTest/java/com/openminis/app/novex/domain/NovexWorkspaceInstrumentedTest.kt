package com.openminis.app.novex.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.CharacterLibraryDocument
import com.openminis.app.data.character.CharacterModuleDocument
import com.openminis.app.data.character.CharacterVersionDocument
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleReferenceTarget
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovexWorkspaceInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var mediaRoot: File
    private lateinit var workspace: NovexWorkspace

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaRoot = File(context.cacheDir, "novex-workspace-${System.nanoTime()}").apply { mkdirs() }
        workspace = NovexWorkspaceFactory.create(database, mediaRoot)
    }

    @After
    fun tearDown() {
        database.close()
        mediaRoot.deleteRecursively()
    }

    @Test
    fun savedWorldModuleOrderAndContentRecoverThroughANewWorkspace() = runBlocking {
        val created = workspace.apply(
            NovexCommand.CreateWorld(name = "旧名称", overview = "旧概述", tagsJson = "[]", now = 10),
        ).requireWorld()
        workspace.apply(
            NovexCommand.SaveWorld(
                world = created.copy(
                    name = "云岚书院",
                    overview = "悬于群山云海之间的书院",
                    tagsJson = "[\"仙侠\",\"学院\"]",
                ),
                now = 20,
            ),
        )
        val timeline = workspace.apply(
            NovexCommand.AddModule(
                owner = ModuleOwner.world(created.id),
                type = ContentModuleType.TIMELINE,
                name = "时间线",
                now = 30,
            ),
        ).requireModule()
        val map = workspace.apply(
            NovexCommand.AddModule(
                owner = ModuleOwner.world(created.id),
                type = ContentModuleType.MAP,
                name = "地图",
                now = 31,
            ),
        ).requireModule()
        workspace.apply(
            NovexCommand.SaveModule(
                moduleId = map.id,
                name = "山海地图",
                contentJson = "{\"text\":\"九峰环湖\"}",
                now = 40,
            ),
        )
        workspace.apply(NovexCommand.MoveModule(map.id, toIndex = 0, now = 50))

        workspace = NovexWorkspaceFactory.create(database, mediaRoot)
        val restored = requireNotNull(workspace.world(created.id))

        assertEquals("云岚书院", restored.world.name)
        assertEquals("悬于群山云海之间的书院", restored.world.overview)
        assertEquals(listOf(map.id, timeline.id), restored.modules.map { it.id })
        assertEquals("山海地图", restored.modules.first().name)
        assertEquals("{\"text\":\"九峰环湖\"}", restored.modules.first().contentJson)
    }

    @Test
    fun worldLinksAndSharedMediaRemainValidWhenOneOwnerIsDeleted() = runBlocking {
        val worldA = workspace.apply(NovexCommand.CreateWorld("云岚书院", now = 10)).requireWorld()
        val worldB = workspace.apply(NovexCommand.CreateWorld("雾港", now = 11)).requireWorld()
        val character = workspace.apply(
            NovexCommand.CreateCharacter(name = "苏晚晴", profileJson = "{\"name\":\"苏晚晴\"}", now = 12),
        ).requireCharacter()
        workspace.apply(NovexCommand.LinkCharacterVersion(worldA.id, character.original.id, 0, now = 20))
        workspace.apply(NovexCommand.LinkCharacterVersion(worldB.id, character.original.id, 0, now = 21))

        val bytes = byteArrayOf(1, 4, 9, 16)
        val assetA = workspace.apply(
            NovexCommand.AttachImage(
                owner = ModuleOwner.world(worldA.id),
                slot = MediaAssetSlot.WORLD_COVER,
                bytes = bytes,
                mimeType = "image/png",
                now = 30,
            ),
        ).requireMedia()
        val assetB = workspace.apply(
            NovexCommand.AttachImage(
                owner = ModuleOwner.world(worldB.id),
                slot = MediaAssetSlot.WORLD_COVER,
                bytes = bytes,
                mimeType = "image/png",
                now = 31,
            ),
        ).requireMedia()
        assertEquals(assetA.id, assetB.id)
        assertEquals(
            listOf(worldA.id, worldB.id),
            workspace.character(character.character.id)!!.worldsByVersion.getValue(character.original.id).map { it.id },
        )

        workspace.apply(NovexCommand.DeleteWorld(worldA.id))

        val survivingWorld = requireNotNull(workspace.world(worldB.id))
        assertEquals(assetB.id, survivingWorld.media.getValue(MediaAssetSlot.WORLD_COVER).id)
        assertNotEquals(false, File(assetB.managedPath).exists())
        assertNotNull(workspace.character(character.character.id))
    }

    @Test
    fun savingForOneWorldCreatesAnIndependentVariantWithoutChangingOtherWorlds() = runBlocking {
        val worldA = workspace.apply(NovexCommand.CreateWorld("云岚书院", now = 1)).requireWorld()
        val worldB = workspace.apply(NovexCommand.CreateWorld("雾港", now = 2)).requireWorld()
        val character = workspace.apply(
            NovexCommand.CreateCharacter("苏晚晴", profileJson = "{\"name\":\"苏晚晴\"}", now = 3),
        ).requireCharacter()
        workspace.apply(NovexCommand.LinkCharacterVersion(worldA.id, character.original.id, 0, now = 4))
        workspace.apply(NovexCommand.LinkCharacterVersion(worldB.id, character.original.id, 0, now = 5))
        workspace.apply(
            NovexCommand.AddModule(
                ModuleOwner.characterVersion(character.original.id),
                ContentModuleType.WORLD_EXPERIENCE,
                "世界经历",
                "{\"text\":\"幼承家学\"}",
                now = 6,
            ),
        )
        val avatar = workspace.apply(
            NovexCommand.AttachImage(
                ModuleOwner.characterVersion(character.original.id),
                MediaAssetSlot.CHARACTER_AVATAR,
                byteArrayOf(2, 3, 5, 7),
                "image/png",
                now = 7,
            ),
        ).requireMedia()

        val variant = workspace.apply(
            NovexCommand.SaveAsWorldVariant(character.original.id, worldA.id, now = 20),
        ).requireVersion()

        assertEquals(listOf(variant.id), workspace.world(worldA.id)!!.versions.map { it.id })
        assertEquals(listOf(character.original.id), workspace.world(worldB.id)!!.versions.map { it.id })
        val restoredCharacter = workspace.character(character.character.id)!!
        assertEquals(
            "{\"text\":\"幼承家学\"}",
            restoredCharacter.modulesByVersion.getValue(variant.id).single().contentJson,
        )
        assertEquals(
            avatar.id,
            restoredCharacter.mediaByVersion.getValue(variant.id).getValue(MediaAssetSlot.CHARACTER_AVATAR).id,
        )
    }

    @Test
    fun duplicatedCharacterCanChangeAndDeleteIndependentlyThroughCommands() = runBlocking {
        val source = workspace.apply(
            NovexCommand.CreateCharacter("林深", profileJson = "{\"name\":\"林深\"}", now = 1),
        ).requireCharacter()
        workspace.apply(
            NovexCommand.AddModule(
                ModuleOwner.characterVersion(source.original.id),
                ContentModuleType.TALENT_SKILL,
                "技能",
                "{\"text\":\"追踪\"}",
                now = 2,
            ),
        )
        val avatar = workspace.apply(
            NovexCommand.AttachImage(
                ModuleOwner.characterVersion(source.original.id),
                MediaAssetSlot.CHARACTER_AVATAR,
                byteArrayOf(11, 13, 17),
                "image/png",
                now = 3,
            ),
        ).requireMedia()

        val copy = workspace.apply(
            NovexCommand.DuplicateCharacter(source.character.id, now = 10),
        ).requireCharacter()
        val copiedModule = workspace.character(copy.character.id)!!
            .modulesByVersion.getValue(copy.original.id).single()
        workspace.apply(
            NovexCommand.SaveModule(copiedModule.id, "技能", "{\"text\":\"潜行\"}", now = 11),
        )
        workspace.apply(NovexCommand.DeleteCharacter(source.character.id))

        val survivingCopy = workspace.character(copy.character.id)!!
        assertEquals(
            "{\"text\":\"潜行\"}",
            survivingCopy.modulesByVersion.getValue(copy.original.id).single().contentJson,
        )
        assertEquals(
            avatar.id,
            survivingCopy.mediaByVersion.getValue(copy.original.id).getValue(MediaAssetSlot.CHARACTER_AVATAR).id,
        )
        val exported = workspace.apply(NovexCommand.ExportCharacter(copy.character.id)).requireDocument()
        assertEquals("林深 副本", exported.name)
        assertEquals("{\"text\":\"潜行\"}", exported.versions.single().modules.single().contentJson)
    }

    @Test
    fun importedCharacterDocumentIsImmediatelyReadableAsOneRootWithVersions() = runBlocking {
        val imported = workspace.apply(
            NovexCommand.ImportCharacter(
                CharacterLibraryDocument(
                    name = "伊薇",
                    versions = listOf(
                        CharacterVersionDocument(
                            kind = CharacterVersionKind.ORIGINAL,
                            label = "本体",
                            profileJson = "{\"name\":\"伊薇\"}",
                            modules = listOf(
                                CharacterModuleDocument(
                                    type = ContentModuleType.QUOTES,
                                    name = "语录",
                                    contentJson = "{\"text\":\"晚上好\"}",
                                    collapsed = false,
                                ),
                            ),
                        ),
                        CharacterVersionDocument(
                            kind = CharacterVersionKind.VARIANT,
                            label = "赛博分身",
                            profileJson = "{\"name\":\"EVE\"}",
                        ),
                    ),
                ),
                now = 100,
            ),
        ).requireCharacter()

        val restored = workspace.character(imported.character.id)!!
        assertEquals(listOf("本体", "赛博分身"), restored.character.allVersions.map { it.label })
        assertEquals("语录", restored.modulesByVersion.getValue(imported.original.id).single().name)
    }

    @Test
    fun characterAndVariantEditsUseTheSameCommandsAsFutureAutomation() = runBlocking {
        val root = workspace.apply(
            NovexCommand.CreateCharacter("旧库名", profileJson = "{\"name\":\"旧姓名\"}", now = 1),
        ).requireCharacter()
        workspace.apply(
            NovexCommand.SaveCharacterVersion(
                characterId = root.character.id,
                versionId = root.original.id,
                rootName = "苏晚晴",
                label = "本体",
                profileJson = "{\"name\":\"苏晚晴\"}",
                now = 2,
            ),
        )
        val variant = workspace.apply(
            NovexCommand.CreateVariant(
                characterId = root.character.id,
                label = "医馆时期",
                profileJson = "{\"name\":\"苏姑娘\"}",
                now = 3,
            ),
        ).requireVersion()
        workspace.apply(
            NovexCommand.SaveCharacterVersion(
                characterId = root.character.id,
                versionId = variant.id,
                rootName = "不会覆盖根名称",
                label = "云岚分身",
                profileJson = "{\"name\":\"晚晴\"}",
                now = 4,
            ),
        )

        val edited = workspace.character(root.character.id)!!.character
        assertEquals("苏晚晴", edited.character.name)
        assertEquals("云岚分身", edited.variants.single().label)
        assertEquals("{\"name\":\"晚晴\"}", edited.variants.single().profileJson)

        workspace.apply(NovexCommand.DeleteVariant(variant.id))
        assertEquals(emptyList<String>(), workspace.character(root.character.id)!!.character.variants.map { it.id })
    }

    @Test
    fun moduleReferencesRecoverAndCanBeRemovedThroughWorkspaceCommands() = runBlocking {
        val sourceWorld = workspace.apply(NovexCommand.CreateWorld("云岚书院", now = 1)).requireWorld()
        val targetWorld = workspace.apply(NovexCommand.CreateWorld("雾港", now = 2)).requireWorld()
        val map = workspace.apply(
            NovexCommand.AddModule(
                ModuleOwner.world(sourceWorld.id),
                ContentModuleType.MAP,
                "山海地图",
                now = 3,
            ),
        ).requireModule()
        val timeline = workspace.apply(
            NovexCommand.AddModule(
                ModuleOwner.world(sourceWorld.id),
                ContentModuleType.TIMELINE,
                "书院时间线",
                now = 4,
            ),
        ).requireModule()
        val worldTarget = ModuleReferenceTarget.world(targetWorld.id)
        val moduleTarget = ModuleReferenceTarget.module(timeline.id)

        workspace.apply(NovexCommand.AddModuleReference(map.id, worldTarget, position = 0))
        workspace.apply(NovexCommand.AddModuleReference(map.id, moduleTarget, position = 1))
        workspace = NovexWorkspaceFactory.create(database, mediaRoot)

        assertEquals(
            listOf(worldTarget, moduleTarget),
            workspace.module(map.id)!!.references.map { it.target },
        )
        assertEquals(
            setOf(worldTarget, moduleTarget),
            workspace.module(map.id)!!.referenceOptions.map { it.target }.toSet(),
        )

        workspace.apply(NovexCommand.RemoveModuleReference(map.id, worldTarget))
        assertEquals(listOf(moduleTarget), workspace.module(map.id)!!.references.map { it.target })
    }
}
