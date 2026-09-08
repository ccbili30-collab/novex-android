package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openminis.app.data.creative.*
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

/** Rebuild exactly the previous group table while preserving its members for upgrade fixtures. */
internal fun restoreVersion31LibraryTable(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TEMP TABLE library_groups_backup AS SELECT id, name FROM novex_work_groups")
    db.execSQL("CREATE TEMP TABLE library_members_backup AS SELECT * FROM novex_work_group_members")
    db.execSQL("DROP TABLE novex_work_group_members")
    db.execSQL("DROP TABLE novex_work_groups")
    AppDatabase.MIGRATION_29_30.migrate(db)
    db.execSQL("INSERT INTO novex_work_groups SELECT * FROM library_groups_backup")
    db.execSQL("INSERT INTO novex_work_group_members SELECT * FROM library_members_backup")
    db.execSQL("DROP TABLE library_groups_backup")
    db.execSQL("DROP TABLE library_members_backup")
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexLibraryOrganizationTest {
    @get:Rule val files = TemporaryFolder()
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        File(files.root, "library.db").absolutePath).addMigrations(AppDatabase.MIGRATION_31_32).allowMainThreadQueries().build()

    @Test fun `folders files and shared cards persist and removal preserves originals`() = runBlocking<Unit> {
        var db = open()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "西幻", "已有规则")).requireInteractiveFiction()
            val card = NovexContentAddress.interactiveFiction(game.id)
            val repository = CreativeArtifactRepository(db, CreativeArtifactFileStore(File(files.root, "artifacts")))
            val file = repository.capture("资料.txt", CreativeArtifactKind.DOCUMENT, "原文".toByteArray(), "text/plain", CreativeArtifactOrigin("chat", "branch"))
            val address = NovexContentAddress(NovexContentKind.CREATIVE_ARTIFACT, file.artifact.id)
            var libraries = RoomNovexWorkGroups(db)
            val a = libraries.create("西幻")
            val b = libraries.create("另一库")
            val folder = libraries.createFolder(a, null, "资料")
            val nested = libraries.createFolder(a, folder, "人物")
            libraries.replaceMembers(a, emptySet(), setOf(card, address), nested)
            libraries.replaceMembers(b, emptySet(), setOf(card))
            assertTrue(runCatching { libraries.createFolder(a, null, "资料") }.isFailure)
            assertTrue(runCatching { libraries.removeFolder(a, folder) }.isFailure)
            assertTrue(runCatching { libraries.moveMembers(a, setOf(card), null, folder) }.isFailure)
            db.close(); db = open(); libraries = RoomNovexWorkGroups(db)
            var group = libraries.snapshots.first().groups.first { it.id == a }
            assertEquals(setOf(card, address), group.contents(folder, true))
            assertEquals("西幻 / 资料 / 人物", group.folderPath(nested))
            libraries.moveMembers(a, setOf(card), nested, null)
            group = libraries.snapshots.first().groups.first { it.id == a }
            assertEquals(setOf(card), group.contents())
            assertEquals(setOf(address), group.contents(nested))
            libraries.dissolve(a)
            assertEquals(setOf(card), libraries.snapshots.first().groups.single().members)
            assertNotNull(NovexWorkspaceFactory.create(db, File(files.root, "media")).interactiveFiction(game.id))
            assertEquals("原文", CreativeArtifactRepository(db, CreativeArtifactFileStore(File(files.root, "artifacts"))).bytes(file.artifact.id).toString(Charsets.UTF_8))
        } finally { db.close() }
    }

    @Test fun `version 31 groups upgrade without losing ids members or filter`() = runBlocking<Unit> {
        var db = open()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "旧文游", "规则")).requireInteractiveFiction()
            val address = NovexContentAddress.interactiveFiction(game.id)
            val id = RoomNovexWorkGroups(db).create("旧作品")
            RoomNovexWorkGroups(db).replaceMembers(id, emptySet(), setOf(address))
            restoreVersion31LibraryTable(db.openHelper.writableDatabase)
            db.openHelper.writableDatabase.version = 31
            db.close(); db = open()
            val snapshot = RoomNovexWorkGroups(db).snapshots.first()
            assertEquals(id, snapshot.selection)
            assertEquals(setOf(address), snapshot.groups.single().members)
            assertTrue(snapshot.groups.single().folders.isEmpty())
        } finally { db.close() }
    }

    @Test fun `invalid folder placement rolls back membership and empty placeholders stay out of directory`() = runBlocking<Unit> {
        val db = open()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("conversation"))
            val repository = CreativeArtifactRepository(db, CreativeArtifactFileStore(File(files.root, "artifacts")))
            assertTrue(workspace.libraryDirectory(repository).isEmpty())
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "唯一文游", "规则")).requireInteractiveFiction()
            val address = NovexContentAddress.interactiveFiction(game.id)
            assertEquals(listOf(address), workspace.libraryDirectory(repository).map { it.address })
            val groups = RoomNovexWorkGroups(db)
            val id = groups.create("作品")
            assertTrue(runCatching { groups.replaceMembers(id, emptySet(), setOf(address), "missing-folder") }.isFailure)
            assertTrue(groups.snapshots.first().groups.single().members.isEmpty())
            val privateCard = workspace.conversationDrafts("conversation")!!.cards.first().subject
            assertTrue(runCatching { groups.replaceMembers(id, emptySet(), setOf(privateCard)) }.isFailure)
        } finally { db.close() }
    }

    @Test fun `display names omit storage suffixes without changing user titles`() {
        assertEquals("西幻人生模拟器.md", NovexDisplayName.file("西幻人生模拟器.txt-" + "a".repeat(64) + ".md"))
        assertEquals("资料与主题索引.json", NovexDisplayName.file("资料与主题索引.json"))
        assertEquals("历史-20260908.txt", NovexDisplayName.file("历史-20260908.txt"))
        assertEquals("很长但由用户明确指定的完整作品名称", NovexDisplayName.file("很长但由用户明确指定的完整作品名称"))
    }

    @Test fun `cyclic hierarchy is rejected and folders do not broaden group membership`() {
        val address = NovexContentAddress.world("world")
        val cyclic = NovexWorkGroup("g", "作品", setOf(address), listOf(NovexLibraryFolder("a", "甲", "b"), NovexLibraryFolder("b", "乙", "a")))
        assertTrue(runCatching { NovexLibraryOrganization.encode(cyclic) }.isFailure)
        assertTrue(NovexWorkGroupSnapshot(listOf(NovexWorkGroup("g", "作品", setOf(address))), "g").includes(address))
        assertFalse(NovexWorkGroupSnapshot(listOf(NovexWorkGroup("g", "作品", setOf(address))), "g").includes(NovexContentAddress.world("other")))
    }
}
