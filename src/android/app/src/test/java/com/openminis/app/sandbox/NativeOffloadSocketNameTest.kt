package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativeOffloadSocketNameTest {
    @Test
    fun stableAndPreviewInstallationsUseIndependentAbstractSockets() {
        val stable = nativeOffloadSocketName("com.noven.player")
        val preview = nativeOffloadSocketName("com.noven.player.preview")

        assertEquals("native-offload:com.noven.player", stable)
        assertEquals("native-offload:com.noven.player.preview", preview)
        assertNotEquals(stable, preview)
    }
}
