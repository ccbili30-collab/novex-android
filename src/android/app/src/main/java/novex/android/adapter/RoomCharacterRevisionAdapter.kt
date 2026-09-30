package novex.android.adapter

import novex.android.data.cards.RoleRevisionDao
import novex.android.data.cards.RoleRevisionRow
import novex.core.*

internal class RoomCharacterRevisionAdapter(private val dao: RoleRevisionDao) : NovexCharacterRevisionPort {
    override suspend fun list(versionId: String) = dao.revisions(versionId).map {
        NovexCharacterRevision(it.versionId, it.sequence, it.savedAt, it.contentJson)
    }
    override suspend fun append(versionId: String, savedAt: Long, contentJson: String) {
        val previous = dao.newestRevision(versionId)
        if (previous?.contentJson == contentJson) return
        dao.appendRevision(RoleRevisionRow(versionId, (previous?.sequence ?: 0) + 1, savedAt, contentJson))
    }
}
