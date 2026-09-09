package com.openminis.app.novex.adapter

import com.openminis.app.data.character.NovexCardPackagePreview
import com.openminis.app.data.db.NovexCardDirectoryDao
import com.openminis.app.data.db.NovexCardDirectoryEntity
import com.openminis.app.novex.domain.*
import java.io.File

internal class RoomCardDirectories(private val dao: NovexCardDirectoryDao, private val store: NovexCardDirectoryStore) : NovexCardDirectories {
    private fun owner(key: NovexCardCopyKey) = "${key.kind.name}:${key.id}"
    override suspend fun synchronize(key: NovexCardCopyKey, rawSnapshot: String?, render: suspend () -> NovexCardDirectoryPayload) {
        val owner = owner(key)
        if (rawSnapshot == null) { dao.delete(owner); return }
        val digest = NovexFrozenContextCodec.digest(rawSnapshot)
        val current = dao.get(owner)
        if (current?.contentDigest == digest && runCatching { store.verify(current.revision()) }.isSuccess) return
        val payload = render()
        val revision = store.prepare(owner, payload.card, rawSnapshot, payload.sources, current?.revision())
        dao.save(NovexCardDirectoryEntity(owner, revision.directory, revision.digest, digest))
    }
    override suspend fun resolve(key: NovexCardCopyKey): File? = dao.get(owner(key))?.let { store.verify(it.revision()) }
    override suspend fun reclaimUnreferenced(olderThan: Long) = store.reclaimUnreferenced(dao.list().mapTo(hashSetOf()) { it.directory }, olderThan)
    private fun NovexCardDirectoryEntity.revision() = NovexCardDirectoryStore.Revision(ownerKey, directory, digest)
}
