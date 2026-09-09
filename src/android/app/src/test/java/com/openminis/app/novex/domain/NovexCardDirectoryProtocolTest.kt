package com.openminis.app.novex.domain

import android.app.Application
import com.openminis.app.data.character.*
import com.openminis.app.novex.adapter.NovexCardDirectoryStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexCardDirectoryProtocolTest {
    @get:Rule val files = TemporaryFolder()
    private fun card(text: String, image: ByteArray? = null) = NovexCardPackagePreview(NovexCardKind.WORLD,
        "source", "雾镇", """{"name":"雾镇","overview":"$text"}""",
        image?.let { listOf(NovexCardMedia("media/雾中镇口.png", "image/png", it)) }.orEmpty())

    @Test fun failureBeforePublicationPreservesPreviousCompleteRevisionAndOwnedImages() {
        val root = File(files.root, "cards")
        val store = NovexCardDirectoryStore(root, syncDirectory = {})
        val image = byteArrayOf(1, 2, 3)
        val before = store.prepare("WORLD:one", card("旧正文", image), "{}")
        val fail = NovexCardDirectoryStore(root, syncDirectory = {}, beforeWrite = { if (it == "card.json") error("模拟磁盘写入失败") })
        assertTrue(runCatching { fail.prepare("WORLD:one", card("新正文", byteArrayOf(9)), "{}") }.isFailure)
        assertTrue(File(store.verify(before), "card.json").readText().contains("旧正文"))
        assertArrayEquals(image, File(store.verify(before), "media/雾中镇口.png").readBytes())
        // A process exit after preparation but before DB publication keeps the previous pointer valid.
        store.prepare("WORLD:one", card("未提交正文"), "{}")
        val reopened = NovexCardDirectoryStore(root, syncDirectory = {})
        assertTrue(File(reopened.verify(before), "card.json").readText().contains("旧正文"))
    }

    @Test fun copiesAreIndependentAndCorruptionIsReported() {
        val store = NovexCardDirectoryStore(File(files.root, "cards"), syncDirectory = {})
        val original = store.prepare("WORLD:one", card("正文", byteArrayOf(1, 2)), "{}")
        val copy = store.prepare("WORLD:two", card("正文", byteArrayOf(1, 2)), "{}")
        assertNotEquals(original.directory, copy.directory)
        File(store.verify(original), "media/雾中镇口.png").delete()
        assertTrue(runCatching { store.verify(original) }.isFailure)
        assertTrue(File(store.verify(copy), "card.json").isFile)
        val hostile = card("正文").copy(media = listOf(NovexCardMedia("media/../../outside", "image/png", byteArrayOf(1))))
        assertTrue(runCatching { store.prepare("WORLD:bad", hostile, "{}") }.isFailure)
        assertFalse(File(files.root, "outside").exists())
    }
    @Test fun streamedMediaAndCleanupKeepCommittedFilesAndNeverReuseAcrossCards() {
        val root = File(files.root, "streamed")
        val store = NovexCardDirectoryStore(root, syncDirectory = {})
        val image = File(files.root, "source.png").apply { writeBytes(ByteArray(250_000) { (it % 251).toByte() }) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) }
        val payload = card("正文").copy(media = listOf(NovexCardMedia("media/图片.png", "image/png", ByteArray(0), hash)))
        val before = store.prepare("WORLD:one", payload, "{}", mapOf("media/图片.png" to image))
        val latest = store.prepare("WORLD:one", payload.copy(documentJson = """{"name":"改名"}"""), "{}", mapOf("media/图片.png" to image), previous = before)
        val copied = store.prepare("WORLD:two", payload, "{}", mapOf("media/图片.png" to image), previous = before)
        val oldDirectory = store.verify(before)
        oldDirectory.setLastModified(1)
        store.reclaimUnreferenced(setOf(latest.directory, copied.directory), System.currentTimeMillis() + 1)
        assertFalse(oldDirectory.exists())
        image.delete()
        assertEquals(250_000L, File(store.verify(latest), "media/图片.png").length())
        assertEquals(250_000L, File(store.verify(copied), "media/图片.png").length())
    }

}
