package com.openminis.app.novex.domain

import java.util.concurrent.ConcurrentHashMap

/** Existing device bridges inherit a concrete approved call, never a cached per-tool grant. */
object NovexActiveToolAuthorization {
    private val calls = ConcurrentHashMap<String, ConcurrentHashMap<String, () -> NovexExecutionMode>>()
    fun allows(conversationId: String): Boolean = calls[conversationId]?.values.orEmpty()
        .any { it() != NovexExecutionMode.READ_ONLY }

    suspend fun <T> during(operation: NovexToolOperation, mode: () -> NovexExecutionMode, effect: suspend () -> T): T {
        val active = calls.computeIfAbsent(operation.conversationId) { ConcurrentHashMap() }
        active[operation.id] = mode
        try { return effect() }
        finally {
            active.remove(operation.id)
            // Retain the empty map: removing it races with a second call entering the same conversation.
        }
    }
}
