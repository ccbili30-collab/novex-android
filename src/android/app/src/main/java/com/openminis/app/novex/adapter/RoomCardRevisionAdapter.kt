package com.openminis.app.novex.adapter

import com.openminis.app.data.db.*
import com.openminis.app.novex.domain.*

internal class RoomCardRevisionAdapter(private val dao: NovexCardRevisionDao) : NovexCardRevisionPort {
    override suspend fun list(subject: NovexContentAddress): List<NovexCardRevision> = when (subject.kind) {
        NovexContentKind.WORLD -> dao.worlds(subject.id).map { NovexCardRevision(subject, it.sequence, it.savedAt, it.contentJson) }
        NovexContentKind.INTERACTIVE_FICTION -> dao.games(subject.id).map { NovexCardRevision(subject, it.sequence, it.savedAt, it.contentJson) }
        else -> emptyList()
    }
    override suspend fun append(subject: NovexContentAddress, at: Long, content: String) {
        when (subject.kind) {
            NovexContentKind.WORLD -> {
                val prior = dao.latestWorld(subject.id)
                if (prior?.contentJson != content) dao.insertWorld(NovexWorldRevisionEntity(subject.id, (prior?.sequence ?: 0) + 1, at, content))
            }
            NovexContentKind.INTERACTIVE_FICTION -> {
                val prior = dao.latestGame(subject.id)
                if (prior?.contentJson != content) dao.insertGame(NovexGameRevisionEntity(subject.id, (prior?.sequence ?: 0) + 1, at, content))
            }
            else -> Unit
        }
    }
}
