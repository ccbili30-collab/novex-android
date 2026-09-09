package com.openminis.app.novex.adapter

import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.domain.DefaultNovexWorkspace
import com.openminis.app.novex.domain.NovexWorkspace
import java.io.File

/** Windows-hosted Robolectric cannot fsync directories. All persistence remains real;
 * Android directory durability is separately exercised by NovexCardDirectoryNativeTest. */
internal object NovexTestWorkspaceFactory {
    fun create(database: AppDatabase, mediaRoot: File): NovexWorkspace =
        NovexWorkspaceFactory.createWithDirectoryStore(database, mediaRoot,
            NovexCardDirectoryStore(File(mediaRoot.canonicalFile.parentFile, "novex-cards"), syncDirectory = {}))
    fun createDeferred(database: AppDatabase, mediaRoot: File): NovexWorkspace =
        DeferredNovexWorkspace { create(database, mediaRoot).also { (it as DefaultNovexWorkspace).recoverSavedCards() } }
}
