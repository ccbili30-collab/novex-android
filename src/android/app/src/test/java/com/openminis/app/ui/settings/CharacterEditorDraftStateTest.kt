package com.openminis.app.ui.settings

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwnerType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterEditorDraftStateTest {
    @Test
    fun variantDraftCopiesSourceContentWithoutSharingModuleIdentityAndBuildsOneSaveCommand() {
        val character = CharacterEntity(
            id = "character-1",
            name = "苏晚晴",
            originalVersionId = "original-1",
            createdAt = 1,
            updatedAt = 1,
        )
        val source = CharacterVersionEntity(
            id = "original-1",
            characterId = character.id,
            kind = CharacterVersionKind.ORIGINAL,
            label = "本体",
            profileJson = JSONObject()
                .put("name", "苏晚晴")
                .put("providerExtension", "must-survive")
                .toString(),
            createdAt = 1,
            updatedAt = 1,
        )
        val module = ContentModuleEntity(
            id = "source-module",
            ownerType = ModuleOwnerType.CHARACTER_VERSION,
            ownerId = source.id,
            type = ContentModuleType.QUOTES,
            name = "语录",
            contentJson = ContentModuleDocumentCodec.encode(ContentModuleDocument.Collection()),
            position = 0,
            createdAt = 1,
            updatedAt = 1,
        )

        val draft = CharacterEditorDraftState.from(
            aggregate = CharacterAggregate(character, source, emptyList()),
            source = source,
            modules = listOf(module),
            createVariant = true,
            moduleIdFactory = { "copied-module" },
        ).copy(label = "云岚分身", occupation = "书院医师")

        assertEquals("original-1", draft.sourceVersionId)
        assertEquals(null, draft.versionId)
        assertEquals(listOf("copied-module"), draft.modules.map { it.id })
        assertNotEquals(module.id, draft.modules.single().id)
        assertTrue(ContentModuleDocumentCodec.decode(draft.modules.single().contentJson) is ContentModuleDocument.Collection)

        val command = draft.toSaveCommand(now = 20)
        assertTrue(command.createVariant)
        assertEquals("云岚分身", command.label)
        assertEquals("书院医师", CharacterVersionProfile.fromJson(command.profileJson).occupation)
        assertEquals("must-survive", JSONObject(command.profileJson).getString("providerExtension"))
        assertEquals(listOf("copied-module"), command.modules.map { it.id })
    }

    @Test
    fun newCharacterDraftRequiresOnlyNameAndStartsWithoutOptionalContent() {
        val draft = CharacterEditorDraftState.create()

        assertTrue(draft.isBlank)
        assertEquals("本体", draft.label)
        assertTrue(draft.modules.isEmpty())
        assertTrue(draft.imageChanges.isEmpty())
        assertFalse(draft.visualExpanded)
    }
}
