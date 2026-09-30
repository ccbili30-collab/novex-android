package novex.android.adapter

import novex.android.data.cards.CardLinkDao
import novex.android.data.cards.CardLinkRow
import novex.core.NovexCardReference
import novex.core.NovexCardReferenceCodec
import novex.core.NovexCardReferencePort
import novex.core.NovexContentAddress

internal class RoomCardReferenceAdapter(private val dao: CardLinkDao) : NovexCardReferencePort {
    override suspend fun get(id: String) = dao.linkById(id)?.let { NovexCardReferenceCodec.decode(it.contentJson) }
    override suspend fun outgoing(source: NovexContentAddress) = dao.outgoingLinks(source.kind.name, source.id).map { NovexCardReferenceCodec.decode(it.contentJson) }
    override suspend fun incoming(target: NovexContentAddress) = dao.incomingLinks(target.kind.name, target.id).map { NovexCardReferenceCodec.decode(it.contentJson) }
    override suspend fun save(reference: NovexCardReference) = dao.putLink(CardLinkRow(
        reference.id, reference.source.kind.name, reference.source.id, reference.sourceModuleId,
        reference.target.subject.kind.name, reference.target.subject.id, reference.position,
        NovexCardReferenceCodec.encode(reference)))
    override suspend fun delete(id: String) = dao.dropLink(id)
    override suspend fun deleteSource(source: NovexContentAddress) = dao.dropOutgoingOf(source.kind.name, source.id)
    override suspend fun deleteSourceModule(moduleId: String) = dao.dropOutgoingOfModule(moduleId)
}
