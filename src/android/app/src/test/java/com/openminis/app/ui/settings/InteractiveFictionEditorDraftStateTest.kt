package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveFictionEditorDraftStateTest {
    @Test
    fun newProjectUsesFirstAvailableNameAndKeepsEverythingOptionalExceptName() {
        assertEquals("新文游", nextDefaultInteractiveFictionName(emptyList()))
        assertEquals("新文游（1）", nextDefaultInteractiveFictionName(listOf("新文游")))
        assertEquals(
            "新文游（2）",
            nextDefaultInteractiveFictionName(listOf("新文游", "新文游（1）")),
        )

        val draft = InteractiveFictionEditorDraftState.create(name = "新文游")
        assertFalse(draft.isBlank)
        assertEquals(InteractiveFictionLaunchMode.FREE_SANDBOX, draft.launchMode)
        assertEquals("", draft.summary)
        assertEquals("", draft.playerIdentity)
        assertTrue(draft.modules.isEmpty())
        assertTrue(draft.imageChanges.isEmpty())
    }

    @Test
    fun savedProjectBecomesOneOrderedPageCommandWithoutMutatingSource() {
        val project = InteractiveFictionProjectEntity(
            id = "game-1",
            name = "云岚问道",
            summary = "旧简介",
            launchMode = InteractiveFictionLaunchMode.FIXED_IDENTITY,
            playerIdentity = "书院新生",
            createdAt = 1,
            updatedAt = 2,
        )
        val opening = ContentModuleEntity(
            id = "opening",
            ownerType = ModuleOwnerType.INTERACTIVE_FICTION,
            ownerId = project.id,
            type = ContentModuleType.GAME_OPENING,
            name = "开局说明",
            contentJson = ContentModuleDocumentCodec.encode(ContentModuleDocument.Article("山门初开")),
            position = 0,
            createdAt = 1,
            updatedAt = 1,
        )

        val draft = InteractiveFictionEditorDraftState.from(project, listOf(opening))
            .copy(
                name = "云岚问道·重制",
                summary = "新简介",
                launchMode = InteractiveFictionLaunchMode.CO_CREATE_WORLD,
                playerIdentity = "",
            )
            .editModules {
                add(ContentModuleType.GAME_QUICK_ACTIONS, "快捷操作", moduleId = "actions")
                    .move("actions", 0)
            }
            .removeImage(MediaAssetSlot.INTERACTIVE_FICTION_COVER)

        assertEquals("旧简介", project.summary)
        val command = draft.toSaveCommand(now = 20)
        assertEquals("game-1", command.projectId)
        assertEquals("云岚问道·重制", command.name)
        assertEquals(InteractiveFictionLaunchMode.CO_CREATE_WORLD, command.launchMode)
        assertEquals(listOf("actions", "opening"), command.modules.map { it.id })
        assertEquals(
            listOf(MediaAssetSlot.INTERACTIVE_FICTION_COVER),
            command.imageChanges.map { it.slot },
        )
    }
}
