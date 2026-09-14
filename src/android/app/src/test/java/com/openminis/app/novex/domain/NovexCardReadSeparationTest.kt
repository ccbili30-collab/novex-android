package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory
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
class NovexCardReadSeparationTest {
    @get:Rule val files = TemporaryFolder()
    private fun checkRead(block: suspend (NovexWorkspace, NovexWorkspace, String) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val real = NovexTestWorkspaceFactory.create(db, File(files.root, "media"))
            val world = real.apply(NovexCommand.CreateWorld("Fixture", "Overview")).requireWorld()
            real.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "Text",
                """{"kind":"article","text":"Preserved body","extension":{"keep":true}}"""))
            val guarded = object : NovexWorkspace by real {
                override suspend fun module(id: String): NovexModuleDetail? =
                    error("A content operation requested the UI detail and its full-library picker")
            }
            block(real, guarded, world.id)
        } finally { db.close() }
    }

    @Test fun revisionAndDirectoryReadNoEditorDetails() = checkRead { _, guarded, id ->
        val snapshot = NovexCardDirectorySnapshot(guarded).read(NovexCardCopyKey(NovexCardKind.WORLD, id))
        assertTrue(requireNotNull(snapshot).contains("Preserved body"))
        var saved: String? = null
        val records = object : NovexCardRevisionPort {
            override suspend fun list(subject: NovexContentAddress) = emptyList<NovexCardRevision>()
            override suspend fun append(subject: NovexContentAddress, at: Long, content: String) { saved = content }
        }
        NovexCardRevisionJournal(guarded, records).record(NovexContentAddress.world(id), 1)
        assertTrue(requireNotNull(saved).contains("Preserved body"))
    }

    @Test fun scopedExportReadsReferencesWithoutPreparingEditorOptions() = checkRead { real, guarded, id ->
        val exporter = NovexReferencePackage(guarded, restoreReference = {},
            exportSingle = { _, key -> real.apply(NovexCommand.ExportNativeWorld(key)).requireNativeCard() },
            importSingle = { _, _, _ -> error("Read-only export must not import") },
            restoreLegacyLink = { _, _, _, _ -> error("Read-only export must not change links") })
        val exported = exporter.export(NovexCardKind.WORLD, id, explicitScope = true)
        assertTrue(exported.documentJson.contains("Preserved body"))
        assertTrue(exported.documentJson.contains("extension"))
    }
}
