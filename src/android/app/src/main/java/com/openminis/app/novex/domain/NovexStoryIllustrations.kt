package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Image presentation is resolved once after a completed answer, independently of text activation. */
object NovexStoryIllustrations {
    const val FIELD = "illustrations"
    data class Rule(val enabled: Boolean = true, val keys: List<String> = emptyList(), val cooldown: Int = 1)
    fun decode(raw: String?): Rule? = runCatching {
        if (raw == null) return Rule()
        val value = JSONObject(raw)
        require(value.keys().asSequence().all { it in setOf("version", "enabled", "keys", "cooldown") })
        require(!value.has("version") || (value.get("version") as? Number)?.toDouble() == 1.0)
        require(!value.has("enabled") || value.get("enabled") is Boolean)
        val count = if (value.has("cooldown")) value.get("cooldown") as Number else 1
        require(count.toDouble() == count.toInt().toDouble() && count.toInt() in 1..20)
        val array = if (value.has("keys")) value.getJSONArray("keys") else JSONArray()
        val keys = (0 until array.length()).map { array.get(it) as String }.map(String::trim).filter(String::isNotBlank)
        require(keys.size <= 50 && keys.all { it.length <= 100 && !it.contains("{{") && !Regex("^/.+/[a-z]*$").matches(it) })
        Rule(value.optBoolean("enabled", true), keys.distinct(), count.toInt())
    }.getOrNull()
    fun encode(rule: Rule) = JSONObject().put("version", 1).put("enabled", rule.enabled)
        .put("keys", JSONArray(rule.keys)).put("cooldown", rule.cooldown).toString()
    fun read(raw: String, key: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!root.has(FIELD)) return null
        return runCatching {
            root.getJSONObject(FIELD).let { if (it.has(key)) JSONArray().put(it.get(key)).toString().drop(1).dropLast(1) else null }
        }.getOrElse { "{\"version\":0}" }
    }
    fun set(raw: String, type: com.openminis.app.data.character.ContentModuleType, key: String, rule: Rule): String {
        val value = runCatching { JSONObject(raw) }.getOrElse { JSONObject(com.openminis.app.data.character.ContentModuleDocumentCodec.encode(
            com.openminis.app.data.character.ContentModuleDocumentCodec.decode(type, raw))) }
        val entries = value.optJSONObject(FIELD) ?: JSONObject()
        entries.put(key, JSONObject(encode(rule))); value.put(FIELD, entries)
        return value.toString()
    }
    fun id(image: NovexSnapshotMedia) = NovexFrozenContextCodec.digest(listOf(image.owner?.kind, image.owner?.id,
        image.moduleId, image.entryId, image.asset.sha256).joinToString(":"))

    fun available(configuration: NovexConversationConfigurationSnapshot, visibleMessages: List<String>): List<NovexSnapshotMedia> =
        NovexSnapshotMediaProjection.visible(configuration).filter { image ->
            image.moduleId != null && decode(image.illustrationRule)?.enabled == true &&
                NovexWorldbookConditions.omission(image.usageConditions, visibleMessages) == null
        }.distinctBy(::id)

    /** Past answers are branch-local, newest last. Empty lists still count as completed answers. */
    fun choose(candidates: List<NovexSnapshotMedia>, completedText: String, pastAnswers: List<Set<String>>,
        explicitId: String? = null): NovexSnapshotMedia? {
        if (completedText.isBlank()) return null
        if (explicitId != null) return candidates.singleOrNull { id(it) == explicitId }
        return candidates.firstOrNull { image ->
            val rule = decode(image.illustrationRule) ?: return@firstOrNull false
            rule.enabled && rule.keys.any { completedText.contains(it, ignoreCase = true) } &&
                pastAnswers.takeLast(rule.cooldown).none { image.asset.sha256 in it }
        }
    }
}
