package com.openminis.app.data.repository

import novex.android.data.WebShortcutDao
import novex.android.data.WebShortcutRow
import java.util.UUID

/**
 * T-pwa-1 (renamed Pwa → WebApp): thin wrapper around
 * [WebShortcutDao]. Constructed once in [com.openminis.app.MinisApp]
 * and shared across UI surfaces (chat attachment chip in T-pwa-2, file
 * browser row in T-pwa-3).
 */
class WebAppShortcutRepository(private val dao: WebShortcutDao) {

    suspend fun create(
        htmlPath: String,
        pathScope: String,
        scopeContext: String?,
        title: String,
        iconRef: String,
        iconCachePath: String?,
        sourceSessionId: String?,
    ): WebShortcutRow {
        val entity = WebShortcutRow(
            id = UUID.randomUUID().toString(),
            htmlPath = htmlPath,
            pathScope = pathScope,
            scopeContext = scopeContext,
            title = title,
            iconRef = iconRef,
            iconCachePath = iconCachePath,
            createdAt = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId,
        )
        dao.put(entity)
        return entity
    }

    suspend fun get(id: String): WebShortcutRow? = dao.byId(id)

    suspend fun list(): List<WebShortcutRow> = dao.all()

    suspend fun delete(id: String) = dao.removeById(id)

    suspend fun update(entity: WebShortcutRow) = dao.update(entity)

    companion object {
        const val SCOPE_SESSION_ATTACHMENT = "session_attachment"
        const val SCOPE_SHARED = "shared"
        const val SCOPE_MOUNT = "mount"
    }
}
