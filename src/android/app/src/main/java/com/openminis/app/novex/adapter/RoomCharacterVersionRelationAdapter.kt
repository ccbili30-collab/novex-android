package com.openminis.app.novex.adapter

import com.openminis.app.data.db.NovexCharacterVersionRelationDao
import com.openminis.app.data.db.NovexCharacterVersionRelationEntity
import com.openminis.app.novex.domain.*

internal class RoomCharacterVersionRelationAdapter(private val dao: NovexCharacterVersionRelationDao) : NovexCharacterVersionRelationPort {
    override suspend fun get(id: String) = dao.get(id)?.let { NovexCharacterVersionRelationCodec.decode(it.contentJson) }
    override suspend fun forCharacter(characterId: String) = dao.forCharacter(characterId).map { NovexCharacterVersionRelationCodec.decode(it.contentJson) }
    override suspend fun forVersion(versionId: String) = dao.forVersion(versionId).map { NovexCharacterVersionRelationCodec.decode(it.contentJson) }
    override suspend fun save(characterId: String, relation: NovexCharacterVersionRelation) = dao.save(NovexCharacterVersionRelationEntity(
        relation.id, characterId, relation.sourceVersionId, relation.targetVersionId, NovexCharacterVersionRelationCodec.encode(relation)))
    override suspend fun delete(id: String) = dao.delete(id)
}
