package novex.android.adapter

import novex.android.data.cards.CardRevisionDao
import novex.android.data.cards.GameRevisionRow
import novex.android.data.cards.WorldRevisionRow
import novex.core.*

internal class RoomCardRevisionAdapter(private val dao: CardRevisionDao) : NovexCardRevisionPort {
    override suspend fun list(subject: NovexContentAddress): List<NovexCardRevision> = when (subject.kind) {
        NovexContentKind.WORLD -> dao.worldRevisions(subject.id).map { NovexCardRevision(subject, it.sequence, it.savedAt, it.contentJson) }
        NovexContentKind.INTERACTIVE_FICTION -> dao.gameRevisions(subject.id).map { NovexCardRevision(subject, it.sequence, it.savedAt, it.contentJson) }
        else -> emptyList()
    }
    override suspend fun append(subject: NovexContentAddress, at: Long, content: String) {
        when (subject.kind) {
            NovexContentKind.WORLD -> {
                val prior = dao.newestWorldRevision(subject.id)
                if (prior?.contentJson != content) dao.appendWorldRevision(WorldRevisionRow(subject.id, (prior?.sequence ?: 0) + 1, at, content))
            }
            NovexContentKind.INTERACTIVE_FICTION -> {
                val prior = dao.newestGameRevision(subject.id)
                if (prior?.contentJson != content) dao.appendGameRevision(GameRevisionRow(subject.id, (prior?.sequence ?: 0) + 1, at, content))
            }
            else -> Unit
        }
    }
}
