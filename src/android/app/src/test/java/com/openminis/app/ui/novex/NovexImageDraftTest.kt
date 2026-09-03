package com.openminis.app.ui.novex

import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.novex.domain.NovexImageChange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexImageDraftTest {
    @Test
    fun worldAndCharacterImagesUseOneReplaceAndRemoveInterface() {
        val bytes = byteArrayOf(1, 2, 3)
        val draft = NovexImageDraft.empty()
            .replace(MediaAssetSlot.WORLD_COVER, bytes, "image/jpeg")
            .remove(MediaAssetSlot.CHARACTER_AVATAR)

        val cover = draft[MediaAssetSlot.WORLD_COVER] as NovexImageChange.Replace
        assertArrayEquals(bytes, cover.bytes)
        assertEquals("image/jpeg", cover.mimeType)
        assertTrue(draft[MediaAssetSlot.CHARACTER_AVATAR] is NovexImageChange.Remove)
        assertEquals(2, draft.changes.size)
    }
}
