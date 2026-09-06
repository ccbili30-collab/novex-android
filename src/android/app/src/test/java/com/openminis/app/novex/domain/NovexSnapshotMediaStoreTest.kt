package com.openminis.app.novex.domain

import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.novex.adapter.NovexSnapshotMediaStore
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexSnapshotMediaStoreTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `adopted media survives original deletion and deduplicates by content after restart`() {
        val original = File(files.root, "original.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) }
        val asset = MediaAssetEntity("asset", original.path, "image/png", hash, 1L)
        val directory = File(files.root, "adopted")
        val retained = NovexSnapshotMediaStore(directory).retain(asset)
        assertNotEquals(original.canonicalPath, File(retained.path).canonicalPath)
        assertArrayEquals(original.readBytes(), File(retained.path).readBytes())
        assertEquals(retained, NovexSnapshotMediaStore(directory).retain(asset))
        original.delete()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(retained.path).readBytes())
        assertEquals(hash, retained.sha256)
        assertEquals(1, directory.listFiles()!!.size)
    }
}
