package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import com.openminis.app.novex.adapter.RoomCardReferenceAdapter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
class NovexCardCopyPersistenceTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun `detached copy retains owned modules and internal references while reuse is explicit`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("外部世界")).requireWorld()
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"summary":"原人物"}""")).requireCharacter()
            val a = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(person.original.id), ContentModuleType.CUSTOM, "甲", "原话不变")).requireModule()
            val b = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(person.original.id), ContentModuleType.CUSTOM, "乙", """{"future":{"keep":true}}""")).requireModule()
            workspace.apply(NovexCommand.AddModuleReference(a.id, ModuleReferenceTarget.module(b.id), 0))
            val source = NovexContentAddress.characterVersion(person.original.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("world", source, NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.BACKGROUND)))
            val key = NovexCardCopyKey(NovexCardKind.CHARACTER, person.character.id)
            val detached = (workspace.apply(NovexCommand.CopyCard(workspace.prepareCardCopy(key))) as NovexChange.CardsCopied).result
            val own = detached.addresses.getValue(source)
            assertTrue(workspace.referencesFrom(own).isEmpty())
            val copied = workspace.modules(ModuleOwner.characterVersion(own.id)).modules
            assertEquals(listOf(a.contentJson, b.contentJson), copied.map { it.contentJson })
            assertEquals(copied[1].id, workspace.module(copied[0].id)!!.references.single().targetId)
            val reused = (workspace.apply(NovexCommand.CopyCard(workspace.prepareCardCopy(key, NovexCardCopyPolicy.REUSE))) as NovexChange.CardsCopied).result
            assertEquals(world.id, workspace.referencesFrom(reused.addresses.getValue(source)).single().target.subject.id)
            assertEquals(1, workspace.referencesFrom(source).size)
            assertEquals("原人物", JSONObject(workspace.characterForVersion(own.id)!!.character.original.profileJson).getString("summary"))
            assertEquals(person.character.id, JSONObject(workspace.characterForVersion(own.id)!!.character.original.profileJson).getJSONObject("_novexCopyOrigin").getString("id"))
        } finally { db.close() }
    }
    @Test fun `cyclic dependency copy survives source removal and reopening without binding a missing namesake`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "copy.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("生生", "路线甲")).requireWorld()
            val other = workspace.apply(NovexCommand.CreateWorld("失落世界", "同名无关作品")).requireWorld()
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val parallel = workspace.apply(NovexCommand.CreateVariant(person.character.id, "法家")).requireVersion()
            workspace.apply(NovexCommand.PutVersionRelation(NovexCharacterVersionRelation("parallel", person.original.id, parallel.id, NovexCharacterVersionRelationKind.PARALLEL)))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生文游", "规则原文")).requireInteractiveFiction()
            val w = NovexContentAddress.world(world.id); val c = NovexContentAddress.characterVersion(parallel.id); val g = NovexContentAddress.interactiveFiction(game.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("w-c", w, NovexReferenceTarget(c), NovexReferencePurpose.BACKGROUND)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("c-g", c, NovexReferenceTarget(g), NovexReferencePurpose.RULES)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("g-w", g, NovexReferenceTarget(w), NovexReferencePurpose.BACKGROUND)))
            RoomCardReferenceAdapter(db.novexCardReferenceDao()).save(NovexCardReference("missing", g,
                NovexReferenceTarget(NovexContentAddress.world("foreign-missing")), NovexReferencePurpose.BACKGROUND, targetLabel = "失落世界"))
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.world(world.id), MediaAssetSlot.WORLD_COVER, byteArrayOf(1, 2, 3), "image/png"))
            val plan = workspace.prepareCardCopy(NovexCardCopyKey(NovexCardKind.WORLD, world.id), NovexCardCopyPolicy.DEPENDENCIES)
            assertEquals(3, plan.items.size); assertEquals(2, plan.items.sumOf { it.versionCount }); assertTrue(plan.missingTargets.isNotEmpty())
            val result = (workspace.apply(NovexCommand.CopyCard(plan)) as NovexChange.CardsCopied).result
            val copiedWorld = result.addresses.getValue(w); val copiedPerson = result.addresses.getValue(c); val copiedGame = result.addresses.getValue(g)
            assertEquals(copiedPerson, workspace.referencesFrom(copiedWorld).single().target.subject)
            assertEquals(copiedGame, workspace.referencesFrom(copiedPerson).single().target.subject)
            val copiedMissing = workspace.referencesFrom(copiedGame).single { it.unresolvedTarget != null }
            assertNotEquals(other.id, copiedMissing.target.subject.id); assertTrue(copiedMissing.target.subject.id.startsWith("missing:"))
            workspace.apply(NovexCommand.DeleteWorld(world.id)); workspace.apply(NovexCommand.DeleteCharacter(person.character.id)); workspace.apply(NovexCommand.DeleteInteractiveFiction(game.id))
            db.close(); db = open(); workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            assertEquals("路线甲", workspace.world(copiedWorld.id)!!.world.overview)
            assertEquals("规则原文", workspace.interactiveFiction(copiedGame.id)!!.project.summary)
            assertEquals(NovexReferenceTargetStatus.AVAILABLE, workspace.referenceStatus(workspace.referencesFrom(copiedWorld).single().target))
            assertTrue(File(workspace.world(copiedWorld.id)!!.media.getValue(MediaAssetSlot.WORLD_COVER).managedPath).exists())
            assertEquals(1, workspace.cardRevisions(copiedWorld).size)
            assertEquals(1, workspace.cardRevisions(copiedGame).size)
            assertEquals(1, workspace.versionRelations(copiedPerson.id).size)
        } finally { db.close() }
    }
    @Test fun `changed source rejects preview before creating any copy`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("世界", "旧正文")).requireWorld()
            val plan = workspace.prepareCardCopy(NovexCardCopyKey(NovexCardKind.WORLD, world.id))
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "最新正文")))
            try { workspace.apply(NovexCommand.CopyCard(plan)); fail("Must reject stale preview") } catch (_: IllegalArgumentException) { }
            assertEquals(1, workspace.worlds().size)
            assertEquals("最新正文", workspace.world(world.id)!!.world.overview)
        } finally { db.close() }
    }
}
