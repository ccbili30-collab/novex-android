package novex.android.adapter

import novex.android.data.cards.RoleVersionLinkDao
import novex.android.data.cards.RoleVersionLinkRow
import novex.core.*

internal class RoomCharacterVersionRelationAdapter(private val dao: RoleVersionLinkDao) : NovexCharacterVersionRelationPort {
    override suspend fun get(id: String) = dao.linkById(id)?.let { NovexCharacterVersionRelationCodec.decode(it.contentJson) }
    override suspend fun forCharacter(characterId: String) = dao.linksOfCharacter(characterId).map { NovexCharacterVersionRelationCodec.decode(it.contentJson) }
    override suspend fun forVersion(versionId: String) = dao.linksTouchingVersion(versionId).map { NovexCharacterVersionRelationCodec.decode(it.contentJson) }
    override suspend fun save(characterId: String, relation: NovexCharacterVersionRelation) = dao.putLink(RoleVersionLinkRow(
        relation.id, characterId, relation.sourceVersionId, relation.targetVersionId, NovexCharacterVersionRelationCodec.encode(relation)))
    override suspend fun delete(id: String) = dao.dropLink(id)
}
