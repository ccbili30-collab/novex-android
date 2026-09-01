package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedMediaAssetStoreInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.cacheDir, "managed-media-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun identicalBytesReuseOneManagedAsset() = runBlocking {
        val repository = MediaAssetRepository(database.mediaAssetDao()) { path -> File(path).delete() }
        val store = ManagedMediaAssetStore(root, repository)
        val bytes = byteArrayOf(8, 6, 7, 5, 3, 0, 9)

        val first = store.import(bytes, "image/png", now = 10)
        val second = store.import(bytes, "image/png", now = 20)

        assertEquals(first.id, second.id)
        assertEquals(first.managedPath, second.managedPath)
        assertTrue(File(first.managedPath).exists())
        assertEquals(1, root.listFiles()?.size)
    }
}
