package com.openminis.app.data.creative

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.domain.CreativeArtifactAttachment
import com.openminis.app.novex.domain.CreativeArtifactKind
import com.openminis.app.novex.domain.CreativeArtifactOrigin
import com.openminis.app.novex.domain.NovexContentAddress
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreativeArtifactRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private lateinit var repository: CreativeArtifactRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.cacheDir, "creative-artifact-test-${System.nanoTime()}")
        repository = CreativeArtifactRepository(database, CreativeArtifactFileStore(root))
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun editsBecomeRevisionsAndDeletingOriginConversationKeepsTheArtifact() = runBlocking {
        val chatRepository = ChatRepository(database.chatDao())
        val session = chatRepository.createSession(modelId = "test-model")
        val origin = CreativeArtifactOrigin(session.id, "branch-1", toolCallId = "tool-1")

        val first = repository.capture(
            title = "第一章",
            kind = CreativeArtifactKind.DOCUMENT,
            bytes = "第一版".toByteArray(),
            mimeType = "text/markdown",
            origin = origin,
            sourcePath = "/var/minis/workspace/chapter.md",
            now = 1L,
        )
        val second = repository.capture(
            title = "修改第一章",
            kind = CreativeArtifactKind.DOCUMENT,
            bytes = "第二版".toByteArray(),
            mimeType = "text/markdown",
            origin = origin.copy(toolCallId = "tool-2"),
            sourcePath = "/var/minis/workspace/chapter.md",
            now = 2L,
        )

        assertEquals(first.artifact.id, second.artifact.id)
        assertEquals(listOf(1, 2), second.revisions.map { it.number })
        chatRepository.deleteSession(session.id)
        assertNotNull(repository.artifact(first.artifact.id))
    }

    @Test
    fun permanentDeleteIsBlockedUntilEveryContentReferenceIsDetached() = runBlocking {
        val record = repository.capture(
            title = "地图",
            kind = CreativeArtifactKind.MAP,
            bytes = byteArrayOf(1, 2, 3),
            mimeType = "image/png",
            origin = CreativeArtifactOrigin("chat-1", "branch-1"),
        )
        val attachment = CreativeArtifactAttachment(
            artifactId = record.artifact.id,
            owner = NovexContentAddress.world("world-1"),
            moduleId = "map-module",
        )
        repository.attach(attachment)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.permanentlyDelete(record.artifact.id) }
        }
        repository.detach(attachment)
        repository.permanentlyDelete(record.artifact.id)
        assertEquals(null, repository.artifact(record.artifact.id))
    }
}
