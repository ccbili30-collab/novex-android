package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleCollectionItem
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveFictionRuntimeTest {
    @Test
    fun projectCreatesAContentAddressedSnapshotWithFullModulesAndPresetControls() {
        val source = gameSnapshot(updatedAt = 20)

        val runtime = InteractiveFictionRuntimeSnapshotFactory.create(source)

        assertTrue(runtime.contentJson.contains("力量体系"))
        assertTrue(runtime.contentJson.contains("灵力等级"))
        assertEquals(64, runtime.snapshotId.length)
        assertEquals(listOf("status"), runtime.presetControls.map { it.id })
        assertEquals(ConversationControlSource.PROJECT_PRESET, runtime.presetControls.single().source)
    }

    @Test
    fun laterProjectEditsCannotChangeAnExistingConversationSnapshot() {
        val first = InteractiveFictionRuntimeSnapshotFactory.create(gameSnapshot(updatedAt = 20))
        val changed = InteractiveFictionRuntimeSnapshotFactory.create(gameSnapshot(updatedAt = 30, power = "天阶"))

        assertTrue(first.contentJson.contains("灵力等级"))
        assertFalse(first.contentJson.contains("天阶"))
        assertTrue(changed.contentJson.contains("天阶"))
        assertFalse(first.snapshotId == changed.snapshotId)
    }

    @Test
    fun activatingAGameReplacesOnlyProjectPresetControls() {
        val user = control("user", ConversationControlSource.USER)
        val stalePreset = control("old", ConversationControlSource.PROJECT_PRESET)
        val active = InteractiveFictionRuntimeSnapshotFactory.create(gameSnapshot())
        val configured = NovexConversationConfiguration.empty("chat-1")
            .apply(NovexConversationCommand.UpsertControl(user))
            .apply(NovexConversationCommand.UpsertControl(stalePreset))
            .apply(NovexConversationCommand.ActivateInteractiveFiction(active))

        assertEquals(listOf("user", "status"), configured.snapshot.controls.map { it.id })
        assertTrue(configured.snapshot.controls.first().source == ConversationControlSource.USER)
    }

    @Test
    fun viewControlReadsNearestBranchStateWithoutCreatingATurn() {
        val active = InteractiveFictionRuntimeSnapshotFactory.create(gameSnapshot())
        val config = NovexConversationConfiguration.empty("chat-1")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(active))
            .apply(
                NovexConversationCommand.SetPlaythroughValue(
                    "user-1",
                    "health",
                    PlaythroughValue.Number(72.0),
                ),
            ).snapshot

        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("user-1", "assistant-1"))
        val outcome = InteractiveFictionRuntime.invoke(active.presetControls.single(), resolved)

        assertEquals("user-1", resolved.branchId)
        assertTrue(outcome is ConversationControlOutcome.View)
        assertEquals(PlaythroughValue.Number(72.0), (outcome as ConversationControlOutcome.View).values["health"])
    }

    @Test
    fun actionControlCreatesAnExplicitTurnPayload() {
        val action = ConversationControlDefinition(
            id = "rest",
            label = "原地休息",
            behavior = ConversationControlBehavior.ACTION,
            source = ConversationControlSource.USER,
            actionKey = "rest",
            payloadJson = "{\"prompt\":\"我决定原地休息。\"}",
        )

        val outcome = InteractiveFictionRuntime.invoke(action, PlaythroughState("leaf"))

        assertEquals("我决定原地休息。", (outcome as ConversationControlOutcome.Action).userTurn)
    }

    @Test
    fun aiRegistrationReplacesOnlyEarlierAiControlsAndKeepsStructuredBehavior() {
        val preset = control("preset", ConversationControlSource.PROJECT_PRESET)
        val user = control("user", ConversationControlSource.USER)
        val staleAi = control("stale", ConversationControlSource.AI)
        val starting = NovexConversationConfiguration.empty("chat-1")
            .apply(NovexConversationCommand.UpsertControl(preset))
            .apply(NovexConversationCommand.UpsertControl(user))
            .apply(NovexConversationCommand.UpsertControl(staleAi))
            .snapshot

        val updated = ConversationControlRegistration.registerAiControls(
            starting,
            """[{"label":"角色档案","behavior":"view","actionKey":"status","stateKeys":["health"]},{"label":"休息","behavior":"action","actionKey":"rest","prompt":"我选择休息。"}]""",
        )

        assertEquals(listOf("preset", "user", "ai:status", "ai:rest"), updated.controls.map { it.id })
        assertEquals(ConversationControlBehavior.VIEW, updated.controls[2].behavior)
        assertTrue(updated.controls[2].payloadJson.contains("health"))
        assertEquals(ConversationControlBehavior.ACTION, updated.controls[3].behavior)
        assertFalse(updated.controls.any { it.id == "stale" })
    }

    @Test
    fun playthroughUpdateAcceptsTypedValuesAndWritesOnlyTheActiveLeaf() {
        val active = InteractiveFictionRuntimeSnapshotFactory.create(gameSnapshot())
        val starting = NovexConversationConfiguration.empty("chat-1")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(active))
            .snapshot

        val updated = PlaythroughStateRegistration.applyUpdates(
            configuration = starting,
            branchId = "assistant-2",
            updatesJson = """[{"key":"health","value":61},{"key":"location","value":"山门"},{"key":"poisoned","value":false}]""",
        )

        assertEquals(PlaythroughValue.Number(61.0), updated.playthroughStates["assistant-2"]?.values?.get("health"))
        assertEquals(PlaythroughValue.Text("山门"), updated.playthroughStates["assistant-2"]?.values?.get("location"))
        assertEquals(PlaythroughValue.Flag(false), updated.playthroughStates["assistant-2"]?.values?.get("poisoned"))
        assertEquals(setOf("assistant-2"), updated.playthroughStates.keys)
    }

    @Test
    fun siblingPathsResolveTheirOwnNearestStateWithoutCopyOrExecution() {
        val active = InteractiveFictionRuntimeSnapshotFactory.create(gameSnapshot())
        val configuration = NovexConversationConfiguration.empty("chat-1")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(active))
            .apply(NovexConversationCommand.SetPlaythroughValue("reply-a", "route", PlaythroughValue.Text("A")))
            .apply(NovexConversationCommand.SetPlaythroughValue("reply-b", "route", PlaythroughValue.Text("B")))
            .snapshot

        val branchA = InteractiveFictionRuntime.resolveState(configuration, listOf("user-1", "reply-a"))
        val branchB = InteractiveFictionRuntime.resolveState(configuration, listOf("user-1", "reply-b"))

        assertEquals(PlaythroughValue.Text("A"), branchA.values["route"])
        assertEquals(PlaythroughValue.Text("B"), branchB.values["route"])
        assertEquals(2, configuration.playthroughStates.size)
    }

    private fun gameSnapshot(
        updatedAt: Long = 20,
        power: String = "灵力等级",
    ) = NovexInteractiveFictionSnapshot(
        project = InteractiveFictionProjectEntity(
            id = "game-1",
            name = "云岚问道",
            summary = "修行冒险",
            launchMode = InteractiveFictionLaunchMode.FIXED_IDENTITY,
            playerIdentity = "外门弟子",
            createdAt = 10,
            updatedAt = updatedAt,
        ),
        media = emptyMap(),
        modules = listOf(
            ContentModuleEntity(
                id = "power",
                ownerType = com.openminis.app.data.character.ModuleOwnerType.INTERACTIVE_FICTION,
                ownerId = "game-1",
                type = ContentModuleType.GAME_POWER_SYSTEM,
                name = "力量体系",
                contentJson = ContentModuleDocumentCodec.encode(ContentModuleDocument.Article(power)),
                position = 0,
                collapsed = false,
                createdAt = 10,
                updatedAt = updatedAt,
            ),
            ContentModuleEntity(
                id = "controls",
                ownerType = com.openminis.app.data.character.ModuleOwnerType.INTERACTIVE_FICTION,
                ownerId = "game-1",
                type = ContentModuleType.GAME_QUICK_ACTIONS,
                name = "快捷操作",
                contentJson = ContentModuleDocumentCodec.encode(
                    ContentModuleDocument.Collection(
                        listOf(
                            ContentModuleCollectionItem(
                                id = "status",
                                name = "角色状态",
                                preservedJson = "{\"behavior\":\"view\",\"actionKey\":\"status\",\"stateKeys\":[\"health\"]}",
                            ),
                        ),
                    ),
                ),
                position = 1,
                collapsed = false,
                createdAt = 10,
                updatedAt = updatedAt,
            ),
        ),
        moduleImages = emptyMap(),
        moduleItemImages = emptyMap(),
    )

    private fun control(id: String, source: ConversationControlSource) = ConversationControlDefinition(
        id = id,
        label = id,
        behavior = ConversationControlBehavior.VIEW,
        source = source,
        actionKey = id,
    )
}
