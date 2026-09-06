package com.openminis.app.novex.adapter

import com.openminis.app.data.db.NovexCardReferenceDao
import com.openminis.app.data.db.NovexCardReferenceEntity
import com.openminis.app.novex.domain.NovexCardReference
import com.openminis.app.novex.domain.NovexCardReferenceCodec
import com.openminis.app.novex.domain.NovexCardReferencePort
import com.openminis.app.novex.domain.NovexContentAddress

internal class RoomCardReferenceAdapter(private val dao: NovexCardReferenceDao) : NovexCardReferencePort {
    override suspend fun get(id: String) = dao.get(id)?.let { NovexCardReferenceCodec.decode(it.contentJson) }
    override suspend fun outgoing(source: NovexContentAddress) = dao.outgoing(source.kind.name, source.id).map { NovexCardReferenceCodec.decode(it.contentJson) }
    override suspend fun incoming(target: NovexContentAddress) = dao.incoming(target.kind.name, target.id).map { NovexCardReferenceCodec.decode(it.contentJson) }
    override suspend fun save(reference: NovexCardReference) = dao.save(NovexCardReferenceEntity(
        reference.id, reference.source.kind.name, reference.source.id, reference.sourceModuleId,
        reference.target.subject.kind.name, reference.target.subject.id, reference.position,
        NovexCardReferenceCodec.encode(reference)))
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteSource(source: NovexContentAddress) = dao.deleteSource(source.kind.name, source.id)
    override suspend fun deleteSourceModule(moduleId: String) = dao.deleteSourceModule(moduleId)
}
