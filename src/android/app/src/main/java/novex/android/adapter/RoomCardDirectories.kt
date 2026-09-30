package novex.android.adapter

import com.openminis.app.data.character.NovexCardPackagePreview
import novex.android.data.cards.CardDirectoryDao
import novex.android.data.cards.CardDirectoryRow
import novex.core.*
import java.io.File

internal class RoomCardDirectories(private val dao: CardDirectoryDao, private val store: NovexCardDirectoryStore) : NovexCardDirectories {
    private fun owner(key: NovexCardCopyKey) = "${key.kind.name}:${key.id}"
    override suspend fun synchronize(key: NovexCardCopyKey, rawSnapshot: String?, render: suspend () -> NovexCardDirectoryPayload) {
        val owner = owner(key)
        if (rawSnapshot == null) { dao.drop(owner); return }
        val digest = NovexFrozenContextCodec.digest(rawSnapshot)
        val current = dao.byOwner(owner)
        if (current?.contentDigest == digest && runCatching { store.verify(current.revision()) }.isSuccess) return
        val payload = render()
        val revision = store.prepare(owner, payload.card, rawSnapshot, payload.sources, current?.revision())
        dao.put(CardDirectoryRow(owner, revision.directory, revision.digest, digest))
    }
    override suspend fun resolve(key: NovexCardCopyKey): File? = dao.byOwner(owner(key))?.let { store.verify(it.revision()) }
    override suspend fun reclaimUnreferenced(olderThan: Long) = store.reclaimUnreferenced(dao.allOwners().mapTo(hashSetOf()) { it.directory }, olderThan)
    private fun CardDirectoryRow.revision() = NovexCardDirectoryStore.Revision(ownerKey, directory, digest)
}
