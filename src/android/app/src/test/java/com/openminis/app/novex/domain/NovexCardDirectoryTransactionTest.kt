package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.character.*
import com.openminis.app.novex.adapter.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexCardDirectoryTransactionTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun outerDatabaseRollbackNeverPublishesPreparedFilesAndRestartResolvesCommittedRevision() = runBlocking {
        val path = File(files.root, "directory.db").absolutePath
        fun database() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, path).allowMainThreadQueries().build()
        var db = database()
        val root = File(files.root, "cards")
        val key = NovexCardCopyKey(NovexCardKind.WORLD, "world")
        fun card(text: String) = NovexCardPackagePreview(NovexCardKind.WORLD, "world", "邮局", """{"name":"邮局","overview":"$text"}""", emptyList())
        try {
            val dirs = RoomCardDirectories(db.novexCardDirectoryDao(), NovexCardDirectoryStore(root, syncDirectory = {}))
            db.withTransaction { dirs.synchronize(key, """{"text":"旧版"}""") { NovexCardDirectoryPayload(card("旧版")) } }
            val old = requireNotNull(dirs.resolve(key))
            assertTrue(runCatching {
                db.withTransaction {
                    dirs.synchronize(key, """{"text":"新版"}""") { NovexCardDirectoryPayload(card("新版")) }
                    error("模拟外层对话保存失败")
                }
            }.isFailure)
            assertEquals(old, dirs.resolve(key))
            db.close(); db = database()
            val reopened = RoomCardDirectories(db.novexCardDirectoryDao(), NovexCardDirectoryStore(root, syncDirectory = {}))
            assertTrue(File(reopened.resolve(key), "card.json").readText().contains("旧版"))
            db.withTransaction { reopened.synchronize(key, """{"text":"新版"}""") { NovexCardDirectoryPayload(card("新版")) } }
            assertTrue(File(reopened.resolve(key), "card.json").readText().contains("新版"))
            val latest = reopened.resolve(key)
            db.withTransaction { reopened.synchronize(key, """{"text":"新版"}""") { error("未修改不得重新导出") } }
            assertEquals(latest, reopened.resolve(key))
        } finally { db.close() }
    }
}
