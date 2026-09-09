package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.character.*
import com.openminis.app.novex.adapter.NovexCardDirectoryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NovexCardDirectoryNativeTest {
    @Test fun realAndroidDirectorySyncSurvivesReopeningAndRejectsCorruption() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "card-directory-${UUID.randomUUID()}")
        try {
            val store = NovexCardDirectoryStore(root)
            val preview = NovexCardPackagePreview(NovexCardKind.WORLD, "fixture", "雾中邮局", """{"name":"雾中邮局","modules":[]}""", emptyList())
            val revision = store.prepare("WORLD:fixture", preview, """{"id":"fixture"}""")
            val reopened = NovexCardDirectoryStore(root)
            assertTrue(File(reopened.verify(revision), "card.json").readText().contains("雾中邮局"))
            File(reopened.verify(revision), "card.json").writeText("broken")
            assertTrue(runCatching { reopened.verify(revision) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
