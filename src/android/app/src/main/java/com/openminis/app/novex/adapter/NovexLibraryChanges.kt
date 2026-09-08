package com.openminis.app.novex.adapter

import androidx.room.InvalidationTracker
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Observe committed catalog data, including writes made while the library is already visible. */
fun observeNovexLibraryChanges(database: AppDatabase): Flow<Unit> = callbackFlow {
    val observer = object : InvalidationTracker.Observer(
        "worlds", "characters", "character_versions", "world_character_versions",
        "interactive_fiction_projects", "content_modules", "media_assets", "media_asset_references",
        "novex_conversation_drafts", "creative_artifacts", "creative_artifact_revisions", "creative_artifact_attachments",
    ) {
        override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
    }
    database.invalidationTracker.addObserver(observer)
    trySend(Unit)
    awaitClose { database.invalidationTracker.removeObserver(observer) }
}.conflate()
