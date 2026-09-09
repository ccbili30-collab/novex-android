package com.openminis.app.novex.domain

import com.openminis.app.data.character.NovexCardPackagePreview
import java.io.File

internal data class NovexCardDirectoryPayload(val card: NovexCardPackagePreview, val sources: Map<String, File> = emptyMap())

/** Caller already holds the card transaction; all filesystem work must succeed before publication. */
internal interface NovexCardDirectories {
    suspend fun synchronize(key: NovexCardCopyKey, rawSnapshot: String?, render: suspend () -> NovexCardDirectoryPayload)
    suspend fun resolve(key: NovexCardCopyKey): File?
    suspend fun reclaimUnreferenced(olderThan: Long)
}
