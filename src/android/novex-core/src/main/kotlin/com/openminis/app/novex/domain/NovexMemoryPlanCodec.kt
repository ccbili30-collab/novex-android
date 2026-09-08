package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Complete immutable proposals, including source and expected revisions. No user words grant permission. */
object NovexMemoryPlanCodec {
    fun encode(plan: NovexMemoryPlan): String = JSONObject().put("version", 1)
        .put("id", plan.id).put("scope", plan.scope.toJson()).put("summary", plan.summary)
        .put("changes", JSONArray(plan.changes.map { change -> when (change) {
            is NovexMemoryChange.Add -> JSONObject().put("operation", "add").put("entry", change.entry.toJson())
            is NovexMemoryChange.Remove -> JSONObject().put("operation", "remove")
                .put("memory_ref", change.ref.value).put("expected_revision", change.expectedRevision)
            is NovexMemoryChange.Replace -> JSONObject().put("operation", "replace")
                .put("memory_ref", change.ref.value).put("expected_revision", change.expectedRevision)
                .put("content", change.content).put("tags", JSONArray(change.tags))
                .put("source_conversation_id", change.sourceConversationId).put("source_branch_id", change.sourceBranchId)
                .put("source_message_id", change.sourceMessageId).put("updated_at_millis", change.updatedAtMillis)
        } })).toString()

    fun decode(raw: String): NovexMemoryPlan {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "不支持的记忆计划版本，原数据已保留" }
        val value = root.getJSONObject("scope")
        val scope = when (value.getString("kind")) {
            "nova" -> NovexMemoryScope.nova()
            "role" -> NovexMemoryScope.role(value.nullable("world_id"), value.nullable("player_identity_id"), value.getString("character_version_id"))
            else -> error("记忆身份范围无效")
        }
        val changes = root.getJSONArray("changes")
        return NovexMemoryPlan(root.getString("id"), scope, (0 until changes.length()).map { index ->
            val change = changes.getJSONObject(index)
            when (change.getString("operation")) {
                "add" -> NovexMemoryChange.Add(change.getJSONObject("entry").toMemoryEntry(scope))
                "remove" -> NovexMemoryChange.Remove(NovexMemoryRef.parse(scope, change.getString("memory_ref")), change.getString("expected_revision"))
                "replace" -> NovexMemoryChange.Replace(
                    NovexMemoryRef.parse(scope, change.getString("memory_ref")), change.getString("expected_revision"),
                    change.getString("content"), change.getJSONArray("tags").stringList(),
                    change.getString("source_conversation_id"), change.getString("source_branch_id"),
                    change.nullable("source_message_id"), change.getLong("updated_at_millis"))
                else -> error("记忆计划包含未知操作")
            }
        }, root.getString("summary"))
    }

    fun fingerprint(plan: NovexMemoryPlan): String = memorySha256(canonical(JSONObject(encode(plan))).toByteArray(Charsets.UTF_8))

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun JSONObject.nullable(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
