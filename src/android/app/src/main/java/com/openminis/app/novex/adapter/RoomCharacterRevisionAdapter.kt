package com.openminis.app.novex.adapter

import com.openminis.app.data.db.NovexCharacterRevisionDao
import com.openminis.app.data.db.NovexCharacterRevisionEntity
import com.openminis.app.novex.domain.*

internal class RoomCharacterRevisionAdapter(private val dao: NovexCharacterRevisionDao) : NovexCharacterRevisionPort {
    override suspend fun list(versionId: String) = dao.list(versionId).map {
        NovexCharacterRevision(it.versionId, it.sequence, it.savedAt, it.contentJson)
    }
    override suspend fun append(versionId: String, savedAt: Long, contentJson: String) {
        val previous = dao.latest(versionId)
        if (previous?.contentJson == contentJson) return
        dao.insert(NovexCharacterRevisionEntity(versionId, (previous?.sequence ?: 0) + 1, savedAt, contentJson))
    }
}
