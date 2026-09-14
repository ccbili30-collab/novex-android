package com.openminis.app.diagnostics

import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.json.JSONArray
import org.json.JSONObject

/** Durable per-run evidence, independent of whether an assistant message was ever saved.
 * No request bodies, authorization headers, or credentials enter this journal. */
class ModelRequestAudit(root: File, conversationId: String, requestId: String?, selection: JSONObject) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelRequestAudit> {
        private val lock = Any()
        private fun directory(root: File, id: String) = File(root, MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray()).joinToString("") { "%02x".format(it) })
        fun export(root: File, conversationId: String): List<Pair<String, String>> = synchronized(lock) {
            directory(root, conversationId).listFiles().orEmpty().filter { it.extension == "json" }
                .sortedBy { it.name }.map { it.name to AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() } }
        }
        fun promoteDraft(root: File, fromId: String, toId: String) = synchronized(lock) {
            if (fromId == toId) return@synchronized
            val source = directory(root, fromId)
            source.listFiles().orEmpty().filter { it.extension == "json" }.forEach { old ->
                try {
                    val record = JSONObject(AtomicFile(old).openRead().bufferedReader().use { it.readText() })
                        .put("conversationId", toId).put("draftConversationId", fromId)
                    val target = AtomicFile(File(directory(root, toId).apply { mkdirs() }, old.name))
                    val output = target.startWrite()
                    try { output.write(record.toString().toByteArray()); target.finishWrite(output) }
                    catch (failure: Exception) { target.failWrite(output); throw failure }
                    AtomicFile(old).delete()
                } catch (failure: Exception) {
                    android.util.Log.w("ModelRequestAudit", "Could not promote draft evidence: ${failure.javaClass.simpleName}")
                }
            }
        }
        fun safeText(value: String, secrets: List<String> = emptyList()): String {
            var result = value
            secrets.filter { it.isNotBlank() }.forEach { result = result.replace(it, "[REDACTED]") }
            return result.replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [REDACTED]")
                .replace(Regex("sk-[A-Za-z0-9_-]+"), "[REDACTED]")
                .replace(Regex("(?i)(api[_-]?key|token|authorization|secret)([\\s\"':=]+)[^\\s,}\"']+"), "$1$2[REDACTED]")
                .take(2000)
        }
        fun safeUrl(raw: String): String = runCatching {
            val uri = URI(raw)
            // Deliberately omit credentials, query and fragment; path segments can also contain keys.
            safeText(URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString())
        }.getOrDefault("[invalid URL]")
    }
    private val id = UUID.randomUUID().toString()
    private val file = AtomicFile(File(directory(root, conversationId).apply { mkdirs() }, "$id.json"))
    private val events = JSONArray()
    private val record = JSONObject().put("version", 1).put("conversationId", conversationId)
        .put("requestMessageId", requestId).put("runId", id).put("selection", selection).put("events", events)

    init { event("preparing") }

    fun event(stage: String, details: JSONObject = JSONObject()) = synchronized(lock) {
        // Keep the earliest and latest evidence; explicitly account for omitted intermediate events.
        if (events.length() >= 256) {
            events.remove(1)
            record.put("omittedIntermediateEvents", record.optInt("omittedIntermediateEvents") + 1)
        }
        events.put(JSONObject().put("stage", stage).put("at", System.currentTimeMillis()).put("details", details))
        // Diagnostics must never prevent sending or mask the original provider failure.
        try {
            val output = file.startWrite()
            try { output.write(record.toString().toByteArray()); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        } catch (failure: Exception) {
            android.util.Log.w("ModelRequestAudit", "Could not persist request evidence: ${failure.javaClass.simpleName}")
        }
        Unit
    }
}
