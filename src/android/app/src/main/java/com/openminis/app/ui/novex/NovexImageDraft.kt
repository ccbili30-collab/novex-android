package com.openminis.app.ui.novex

import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.novex.domain.NovexImageChange

/** Shared in-memory image changes. The parent page decides when they are saved. */
internal data class NovexImageDraft(
    val changes: Map<MediaAssetSlot, NovexImageChange>,
) {
    operator fun get(slot: MediaAssetSlot): NovexImageChange? = changes[slot]

    fun replace(
        slot: MediaAssetSlot,
        bytes: ByteArray,
        mimeType: String,
    ): NovexImageDraft = copy(
        changes = changes + (slot to NovexImageChange.Replace(slot, bytes, mimeType)),
    )

    fun remove(slot: MediaAssetSlot): NovexImageDraft = copy(
        changes = changes + (slot to NovexImageChange.Remove(slot)),
    )

    companion object {
        fun empty(): NovexImageDraft = NovexImageDraft(emptyMap())
    }
}
