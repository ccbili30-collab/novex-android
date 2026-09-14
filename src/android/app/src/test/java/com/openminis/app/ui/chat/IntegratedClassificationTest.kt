package com.openminis.app.ui.chat

import android.app.Application
import androidx.room.Room
import com.openminis.app.cards.IntegratedCatalog
import com.openminis.app.data.creative.RoomNovexWorkGroups
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import novex.content.*
import novex.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class IntegratedClassificationTest {
    @Test fun newWorldAndRoleCanBeGroupedWithoutOldRowsAndDeletedCardsCannotBeReadded()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        val store=CardStore(java.nio.file.Files.createTempDirectory("classification"))
        try {
            for(kind in CardKind.values())store.save(ContentDocument(kind.name,kind,kind.name,emptyList()),null,ChangeSource.HUMAN,"create-${kind.name}")
            val groups=RoomNovexWorkGroups(db){IntegratedCatalog.contains(it,store)}
            val id=groups.create("分类")
            val folder=groups.createFolder(id,null,"子目录")
            val members=setOf(NovexContentAddress.world("WORLD"),NovexContentAddress.characterVersion("CHARACTER"))
            assertFalse(db.novexWorkGroupDao().targetExists("WORLD","WORLD"))
            groups.replaceMembers(id,emptySet(),members,folder)
            val reopened=RoomNovexWorkGroups(db){IntegratedCatalog.contains(it,store)}.snapshots.first()
            assertEquals(members,reopened.groups.single().contents(folder))
            assertTrue(members.all {reopened.includes(it)})
            groups.replaceMembers(id,members,emptySet())
            store.delete("WORLD",store.open("WORLD")!!.revision)
            assertTrue(runCatching {groups.replaceMembers(id,emptySet(),members)}.isFailure)
            assertTrue(groups.snapshots.first().groups.single().members.isEmpty())
            assertFalse(IntegratedCatalog.contains(NovexContentAddress.world("CHARACTER"),store))
            assertNotNull(store.open("CHARACTER"))
        } finally {db.close()}
    }
}
