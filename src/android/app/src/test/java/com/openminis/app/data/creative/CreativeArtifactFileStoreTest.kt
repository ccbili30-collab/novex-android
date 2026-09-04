package com.openminis.app.data.creative

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativeArtifactFileStoreTest {
    @Test
    fun `identical bytes use one content addressed file`() {
        val root = Files.createTempDirectory("novex-artifacts").toFile()
        try {
            val store = CreativeArtifactFileStore(root)
            val first = store.put("chapter".toByteArray(), "text/markdown")
            val second = store.put("chapter".toByteArray(), "text/plain")

            assertEquals(first.contentHash, second.contentHash)
            assertEquals(first.storageKey, second.storageKey)
            assertEquals(1, root.walkTopDown().count { it.isFile })
            assertArrayEquals("chapter".toByteArray(), store.read(first.storageKey))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `storage keys cannot escape the creative library root`() {
        val root = Files.createTempDirectory("novex-artifacts").toFile()
        try {
            val store = CreativeArtifactFileStore(root)
            val outside = runCatching { store.read("../outside.txt") }
            assertTrue(outside.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
