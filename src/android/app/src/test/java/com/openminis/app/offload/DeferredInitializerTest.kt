package com.openminis.app.offload

import com.openminis.app.sandbox.offload.DeferredInitializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredInitializerTest {
    @Test
    fun resourceIsCreatedOnlyOnFirstUseAndThenReused() {
        var creations = 0
        val deferred = DeferredInitializer {
            creations += 1
            Any()
        }

        assertFalse(deferred.isInitialized())
        assertEquals(0, creations)
        val first = deferred.value
        val second = deferred.value

        assertTrue(deferred.isInitialized())
        assertTrue(first === second)
        assertEquals(1, creations)
    }
}
