package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.creative.RoomNovexWorkGroups
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexWorkGroupPersistenceTest {
    @get:Rule val files = TemporaryFolder()
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        File(files.root, "groups.db").absolutePath).addMigrations(AppDatabase.MIGRATION_29_30).allowMainThreadQueries().build()

    @Test fun `one version can belong to two works without sharing edits or losing the source after dissolving`() = runBlocking {
        var database = open()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("同名人物", """{"summary":"原件"}""")).requireCharacter()
            val variant = workspace.apply(NovexCommand.CreateVariant(person.character.id, "另一人生")).requireVersion()
            val original = NovexContentAddress.characterVersion(person.original.id)
            val parallel = NovexContentAddress.characterVersion(variant.id)
            var groups = RoomNovexWorkGroups(database)
            val a = groups.create("生生")
            val b = groups.create("独立作品")
            groups.replaceMembers(a, emptySet(), setOf(original))
            groups.replaceMembers(b, emptySet(), setOf(original, parallel))
            assertTrue(runCatching { groups.replaceMembers(a, setOf(original), setOf(NovexContentAddress.world("missing"))) }.isFailure)
            groups.select(a)
            database.close()
            database = open()
            groups = RoomNovexWorkGroups(database)
            val restored = groups.snapshots.first()
            assertEquals(a, restored.selection)
            assertTrue(restored.includes(original))
            assertFalse(restored.includes(parallel))
            groups.replaceMembers(a, setOf(original), emptySet())
            assertTrue(runCatching { groups.replaceMembers(a, setOf(original), setOf(parallel)) }.isFailure)
            val removed = groups.snapshots.first()
            assertFalse(removed.includes(original))
            assertEquals(setOf(original, parallel), removed.groups.single { it.id == b }.members)
            groups.dissolve(a)
            assertEquals(NovexWorkGroupSnapshot.ALL, groups.snapshots.first().selection)
            groups.select(NovexWorkGroupSnapshot.UNCLASSIFIED)
            assertFalse(groups.snapshots.first().includes(original))
            groups.dissolve(b)
            assertTrue(groups.snapshots.first().includes(original))
            val saved = NovexWorkspaceFactory.create(database, File(files.root, "media")).character(person.character.id)!!
            assertEquals(2, saved.character.allVersions.size)
            assertTrue(saved.character.original.profileJson.contains("原件"))
        } finally { database.close() }
    }

    @Test fun `version 29 migration preserves original content and starts with all works selected`() = runBlocking {
        var database = open()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("旧世界", "原概述")).requireWorld()
            database.openHelper.writableDatabase.apply {
                execSQL("DROP TABLE novex_work_group_members")
                execSQL("DROP TABLE novex_work_groups")
                execSQL("DROP TABLE novex_work_group_selection")
                version = 29
            }
            database.close(); database = open()
            val groups = RoomNovexWorkGroups(database)
            assertEquals(NovexWorkGroupSnapshot.ALL, groups.snapshots.first().selection)
            assertTrue(groups.snapshots.first().groups.isEmpty())
            val a = groups.create("同名")
            val b = groups.create("同名")
            assertNotEquals(a, b)
            val address = NovexContentAddress.world(world.id)
            groups.replaceMembers(a, emptySet(), setOf(address))
            groups.rename(a, "同名", "改名后")
            assertTrue(runCatching { groups.rename(a, "同名", "过时的改名") }.isFailure)
            groups.dissolve(a)
            assertNotNull(NovexWorkspaceFactory.create(database, File(files.root, "media")).world(world.id))
            assertEquals(b, groups.snapshots.first().groups.single().id)
        } finally { database.close() }
    }
}
