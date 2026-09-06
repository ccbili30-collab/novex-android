package com.openminis.app.ui.settings

import com.openminis.app.data.character.NovexCardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexNativeCardImportFlowTest {
    @Test
    fun worldRootOffersOnlyTheNativeWorldCardContract() {
        val spec = novexNativeCardImportSpec(NovexCardKind.WORLD)

        assertEquals("导入世界卡", spec.label)
        assertEquals(".novexworld", spec.extensionLabel)
        assertTrue("application/zip" in spec.mimeTypes)
    }

    @Test
    fun characterRootOffersNativeAndTavernCharacterFiles() {
        val spec = novexNativeCardImportSpec(NovexCardKind.CHARACTER)

        assertEquals("导入角色卡", spec.label)
        assertTrue(spec.extensionLabel.contains(".novexcharacter"))
        assertTrue("image/png" in spec.mimeTypes)
        assertTrue("application/json" in spec.mimeTypes)
        assertTrue("application/octet-stream" in spec.mimeTypes)
    }

    @Test
    fun interactiveFictionRootOffersOnlyTheNativeGameCardContract() {
        val spec = novexNativeCardImportSpec(NovexCardKind.GAME)

        assertEquals("导入文游卡", spec.label)
        assertEquals(".novexgame", spec.extensionLabel)
        assertTrue("application/zip" in spec.mimeTypes)
    }
}
