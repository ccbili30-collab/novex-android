package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.WorldEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexManagementServiceTest {
    @Test
    fun `inspection is limited to mounted subjects`() {
        runBlocking {
            val workspace = FakeManagementWorkspace()
            workspace.worldSnapshots["w1"] = NovexWorldSnapshot(
                world = WorldEntity("w1", "雾海", "群岛常年被雾包围", "[\"群岛\"]", null, 1, 1),
                versions = emptyList(),
                availableVersions = emptyList(),
                worldsByVersion = emptyMap(),
                media = emptyMap(),
                modules = emptyList(),
                moduleImages = emptyMap(),
                moduleItemImages = emptyMap(),
            )
            workspace.modulesByOwner[ModuleOwner.world("w1")] = listOf(module("m1", ModuleOwner.world("w1")))
            val service = NovexManagementService(workspace, FakeArtifactPort())
            val configuration = NovexConversationConfigurationSnapshot(
                conversationId = "chat-1",
                managedSubjects = listOf(
                    ManagedSubject(NovexContentAddress.world("w1"), ManagedAccess.READ_ONLY),
                ),
            )

            val inspection = service.inspect(configuration, NovexContentAddress.world("w1"), null)
            assertEquals(listOf("m1"), inspection.modules.map { it.id })
            assertEquals("雾海", org.json.JSONObject(inspection.selectedSubjectJson!!).getString("name"))
            assertEquals(
                "群岛常年被雾包围",
                org.json.JSONObject(inspection.selectedSubjectJson!!).getString("overview"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    service.inspect(configuration, NovexContentAddress.world("w2"), null)
                }
            }
        }
    }

    @Test
    fun `mounted creative artifact exposes durable metadata without becoming background context`() = runBlocking {
        val artifacts = FakeArtifactPort().apply {
            descriptions["artifact-1"] = NovexManagedArtifactDescription(
                id = "artifact-1",
                title = "第一章",
                kind = CreativeArtifactKind.DOCUMENT,
                mimeType = "text/markdown",
                sizeBytes = 42L,
                sourcePath = "/var/minis/workspace/novel/chapter-1.md",
            )
        }
        val service = NovexManagementService(FakeManagementWorkspace(), artifacts)
        val address = NovexContentAddress.creativeArtifact("artifact-1")
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(ManagedSubject(address, ManagedAccess.EDIT)),
        )

        val inspection = service.inspect(configuration, address, null)
        val payload = org.json.JSONObject(inspection.selectedSubjectJson!!)

        assertEquals("第一章", inspection.subjects.single().label)
        assertEquals("/var/minis/workspace/novel/chapter-1.md", payload.getString("source_path"))
        assertTrue(inspection.modules.isEmpty())
        assertTrue(configuration.backgroundSettings.isEmpty())
    }

    @Test
    fun `a proposal is inert until the real user confirms it`() = runBlocking {
        val workspace = FakeManagementWorkspace()
        val owner = ModuleOwner.world("w1")
        val service = NovexManagementService(workspace, FakeArtifactPort())
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(
                ManagedSubject(NovexContentAddress.world("w1"), ManagedAccess.EDIT),
            ),
        )
        val proposal = service.propose(
            configuration = configuration,
            changesJson = """[{"operation":"add_module","subject_kind":"world","subject_id":"w1","module_type":"MAP","name":"地图","content_json":{}}]""",
            latestUserRequest = "添加一个地图模块",
            planId = "proposal-12345678",
        )

        assertTrue(workspace.applied.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.apply(configuration, proposal, "确认") }
        }
        service.apply(configuration, proposal, proposal.confirmationPhrase)
        assertEquals(owner, (workspace.applied.single() as NovexCommand.AddModule).owner)
    }

    @Test
    fun `a multi change plan crosses one transaction boundary`() = runBlocking {
        val workspace = FakeManagementWorkspace()
        val owner = ModuleOwner.world("w1")
        workspace.modulesByOwner[owner] = listOf(module("m1", owner))
        var transactionCount = 0
        val service = NovexManagementService(
            workspace = workspace,
            artifacts = FakeArtifactPort(),
            transaction = NovexManagementTransaction { block ->
                transactionCount += 1
                block()
            },
        )
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(
                ManagedSubject(NovexContentAddress.world("w1"), ManagedAccess.EDIT),
            ),
        )
        val proposal = service.propose(
            configuration,
            """[
              {"operation":"update_module","module_id":"m1","name":"新地图","content_json":{"caption":"北境"}},
              {"operation":"move_module","module_id":"m1","to_index":0}
            ]""".trimIndent(),
            latestUserRequest = "更新地图并放到第一项",
            planId = "proposal-abcdefgh",
        )

        service.apply(configuration, proposal, proposal.confirmationPhrase)
        assertEquals(1, transactionCount)
        assertEquals(2, workspace.applied.size)
    }

    @Test
    fun `created content becomes an editable managed subject`() = runBlocking {
        val workspace = FakeManagementWorkspace()
        val service = NovexManagementService(workspace, FakeArtifactPort())
        val configuration = NovexConversationConfigurationSnapshot(conversationId = "chat-1")
        val proposal = service.propose(
            configuration,
            """[{"operation":"create_character","name":"苏晚晴","profile_json":{"name":"苏晚晴"}}]""",
            latestUserRequest = "创建角色苏晚晴",
            planId = "proposal-newchar1",
        )

        val result = service.apply(configuration, proposal, proposal.confirmationPhrase)
        assertEquals(
            listOf(NovexContentAddress.characterVersion("version-created")),
            result.createdSubjects,
        )
    }
}

private class FakeArtifactPort : NovexManagementArtifactPort {
    val descriptions = mutableMapOf<String, NovexManagedArtifactDescription>()
    override suspend fun exists(artifactId: String) = false
    override suspend fun describe(artifactId: String) = descriptions[artifactId]
    override suspend fun attach(attachment: CreativeArtifactAttachment) = Unit
    override suspend fun detach(attachment: CreativeArtifactAttachment) = Unit
}

private class FakeManagementWorkspace : NovexWorkspace {
    val modulesByOwner = mutableMapOf<ModuleOwner, List<ContentModuleEntity>>()
    val worldSnapshots = mutableMapOf<String, NovexWorldSnapshot>()
    val applied = mutableListOf<NovexCommand>()

    override suspend fun worlds() = emptyList<NovexWorldCard>()
    override suspend fun characters() = emptyList<NovexCharacterCard>()
    override suspend fun interactiveFictions() = emptyList<NovexInteractiveFictionCard>()
    override suspend fun world(id: String) = worldSnapshots[id]
    override suspend fun character(id: String) = null
    override suspend fun interactiveFiction(id: String) = null
    override suspend fun modules(owner: ModuleOwner) = NovexModuleSnapshot(
        modulesByOwner[owner].orEmpty(),
        emptyMap(),
        emptyMap(),
    )
    override suspend fun module(id: String): NovexModuleDetail? = modulesByOwner.values.flatten()
        .firstOrNull { it.id == id }
        ?.let { NovexModuleDetail(it, null, emptyList(), emptyList()) }

    override suspend fun apply(command: NovexCommand): NovexChange {
        applied += command
        return when (command) {
            is NovexCommand.CreateCharacter -> NovexChange.CharacterSaved(
                CharacterAggregate(
                    character = CharacterEntity(
                        "character-created",
                        command.name,
                        "version-created",
                        command.now,
                        command.now,
                    ),
                    original = CharacterVersionEntity(
                        id = "version-created",
                        characterId = "character-created",
                        kind = CharacterVersionKind.ORIGINAL,
                        label = "本体",
                        profileJson = command.profileJson,
                        createdAt = command.now,
                        updatedAt = command.now,
                    ),
                    variants = emptyList(),
                ),
            )
            is NovexCommand.AddModule -> NovexChange.ModuleSaved(
                module(command.id, command.owner, command.type, command.name, command.contentJson),
            )
            is NovexCommand.SaveModule -> NovexChange.ModuleSaved(
                module(command.moduleId, ModuleOwner.world("w1"), name = command.name, content = command.contentJson),
            )
            is NovexCommand.MoveModule -> NovexChange.ModuleSaved(
                module(command.moduleId, ModuleOwner.world("w1")),
            )
            else -> NovexChange.Completed
        }
    }
}

private fun module(
    id: String,
    owner: ModuleOwner,
    type: ContentModuleType = ContentModuleType.MAP,
    name: String = "地图",
    content: String = "{}",
) = ContentModuleEntity(
    id = id,
    ownerType = owner.type,
    ownerId = owner.id,
    type = type,
    name = name,
    contentJson = content,
    position = 0,
    createdAt = 1,
    updatedAt = 1,
)
