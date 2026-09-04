package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNovexContextLoaderTest {
    @Test
    fun managedWorldIsNotLoadedUnlessItIsAlsoMountedAsBackground() = kotlinx.coroutines.test.runTest {
        val background = world("background", "云岚书院")
        val managedOnly = world("managed", "未注入世界")
        val workspace = FakeWorkspace(worlds = mapOf("background" to background, "managed" to managedOnly))
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat",
            backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world("background"))),
            managedSubjects = listOf(ManagedSubject(NovexContentAddress.world("managed"), ManagedAccess.EDIT)),
        )

        val result = WorkspaceNovexContextLoader(workspace).load(configuration)

        assertTrue(result.any { it.label.contains("云岚书院") })
        assertFalse(result.any { it.label.contains("未注入世界") })
        assertFalse("managed" in workspace.requestedWorldIds)
    }

    @Test
    fun selectedCharacterIdentityHasAnswerAuthorityAndItsModulesStayStructured() = kotlinx.coroutines.test.runTest {
        val version = CharacterVersionEntity(
            id = "version",
            characterId = "character",
            kind = CharacterVersionKind.ORIGINAL,
            label = "本体",
            profileJson = com.openminis.app.data.character.CharacterVersionProfile(
                name = "苏晚晴",
                summary = "江南医馆之女",
            ).toJson(),
            createdAt = 1,
            updatedAt = 1,
        )
        val module = ContentModuleEntity(
            id = "quotes",
            ownerType = ModuleOwner.characterVersion("version").type,
            ownerId = "version",
            type = ContentModuleType.QUOTES,
            name = "语录",
            contentJson = ContentModuleDocumentCodec.encode(ContentModuleDocument.Article("医者仁心")),
            position = 0,
            createdAt = 1,
            updatedAt = 1,
        )
        val root = CharacterEntity("character", "苏晚晴", "version", 1, 1)
        val workspace = FakeWorkspace(
            characters = listOf(NovexCharacterCard(CharacterAggregate(root, version, emptyList()), null)),
            modules = mapOf(ModuleOwner.characterVersion("version") to listOf(module)),
        )
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat",
            answerIdentity = AnswerIdentity.CharacterVersion("version"),
        )

        val result = WorkspaceNovexContextLoader(workspace).load(configuration)

        assertTrue(result.any { it.sourceId == "character-version:version:profile" && it.kind == ContextSourceKind.ANSWER_IDENTITY })
        assertTrue(result.any { it.sourceId == "quotes" && it.kind == ContextSourceKind.ANSWER_IDENTITY })
    }

    private fun world(id: String, name: String) = NovexWorldSnapshot(
        world = WorldEntity(id, name, "世界概述", "[]", null, 1, 1),
        versions = emptyList(),
        availableVersions = emptyList(),
        worldsByVersion = emptyMap(),
        media = emptyMap(),
        modules = emptyList(),
        moduleImages = emptyMap(),
        moduleItemImages = emptyMap(),
    )

    private class FakeWorkspace(
        private val worlds: Map<String, NovexWorldSnapshot> = emptyMap(),
        private val characters: List<NovexCharacterCard> = emptyList(),
        private val modules: Map<ModuleOwner, List<ContentModuleEntity>> = emptyMap(),
    ) : NovexWorkspace {
        val requestedWorldIds = mutableListOf<String>()
        override suspend fun worlds() = worlds.values.map { snapshot ->
            NovexWorldCard(snapshot.world, null, snapshot.versions.size, snapshot.modules.size)
        }
        override suspend fun characters() = characters
        override suspend fun interactiveFictions() = emptyList<NovexInteractiveFictionCard>()
        override suspend fun world(id: String): NovexWorldSnapshot? {
            requestedWorldIds += id
            return worlds[id]
        }
        override suspend fun character(id: String) = null
        override suspend fun interactiveFiction(id: String) = null
        override suspend fun modules(owner: ModuleOwner) = NovexModuleSnapshot(modules[owner].orEmpty(), emptyMap(), emptyMap())
        override suspend fun module(id: String): NovexModuleDetail? = null
        override suspend fun apply(command: NovexCommand): NovexChange = error("测试不执行领域命令")
    }
}
