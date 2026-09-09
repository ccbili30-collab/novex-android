package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleType
import org.json.JSONObject

/** Per-card provenance only; never an instruction or a remote resource to load at runtime. */
internal object NovexModuleImageOrigins {
    const val FIELD = "imageSources"
    private const val OWNED = "_novexOwnedModuleImage"
    fun ownsMainImage(raw: String) = runCatching { JSONObject(raw).optBoolean(OWNED) }.getOrDefault(false)
    fun set(raw: String, type: ContentModuleType, key: String, source: String?): String {
        val root = runCatching { JSONObject(raw) }.getOrElse { JSONObject(ContentModuleDocumentCodec.encode(ContentModuleDocumentCodec.decode(type, raw))) }
        if (key == "main") root.put(OWNED, true)
        val sources = root.optJSONObject(FIELD) ?: JSONObject()
        if (source == null) sources.remove(key) else sources.put(key, source)
        if (sources.length() == 0) root.remove(FIELD) else root.put(FIELD, sources)
        return root.toString()
    }
    fun preserve(original: String, incoming: String, type: ContentModuleType): String {
        val old = runCatching { JSONObject(original) }.getOrNull() ?: return incoming
        val retainedKeys = listOf(FIELD, OWNED, NovexStoryIllustrations.FIELD)
        if (retainedKeys.none(old::has)) return incoming
        val root = runCatching { JSONObject(incoming) }.getOrElse {
            JSONObject(ContentModuleDocumentCodec.encode(ContentModuleDocumentCodec.decode(type, incoming)))
        }
        retainedKeys.forEach { key ->
            if (!root.has(key) && old.has(key)) root.put(key, old.get(key))
        }
        return root.toString()
    }
    fun copyFrom(latest: String, draft: String): String {
        val source = runCatching { JSONObject(latest).optJSONObject(FIELD) }.getOrNull()
        val target = runCatching { JSONObject(draft) }.getOrElse { JSONObject(ContentModuleDocumentCodec.encode(ContentModuleDocumentCodec.decode(draft))) }
        if (ownsMainImage(latest)) target.put(OWNED, true)
        if (source == null) target.remove(FIELD) else target.put(FIELD, source)
        return target.toString()
    }
}
