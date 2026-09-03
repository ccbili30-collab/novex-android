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
    fun characterRootOffersOnlyTheNativeCharacterCardContract() {
        val spec = novexNativeCardImportSpec(NovexCardKind.CHARACTER)

        assertEquals("导入角色卡", spec.label)
        assertEquals(".novexcharacter", spec.extensionLabel)
        assertTrue("application/octet-stream" in spec.mimeTypes)
    }
}
