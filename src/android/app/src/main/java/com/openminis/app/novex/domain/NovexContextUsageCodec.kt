package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Stable storage payload for the exact context sources used by one model request. */
object NovexContextUsageCodec {
    fun encode(record: ContextUsageRecord): String = JSONObject().apply {
        put("version", 1)
        put("id", record.id)
        put("requestMessageId", record.requestMessageId)
        record.responseMessageId?.let { put("responseMessageId", it) }
        put("branchId", record.branchId)
        put("answerIdentity", NovexAnswerIdentityCodec.encode(record.answerIdentity))
        put("includedSources", JSONArray(record.includedSources.map { source ->
            JSONObject()
                .put("kind", source.kind.name)
                .put("sourceId", source.sourceId)
                .put("label", source.label)
                .put("tokenCount", source.tokenCount)
        }))
        put("omittedSources", JSONArray(record.omittedSources.map { source ->
            JSONObject()
                .put("kind", source.kind.name)
                .put("sourceId", source.sourceId)
                .put("label", source.label)
                .put("reason", source.reason)
        }))
        put("usedTokens", record.usedTokens)
        put("effectiveWindowTokens", record.effectiveWindowTokens)
        put("createdAt", record.createdAt)
        put("teachingTraceRef", record.teachingTraceRef)
        put("teachingTraceError", record.teachingTraceError)
        put("sourceReads", JSONArray(record.sourceReads.map { read ->
            JSONObject().put("sourceId", read.sourceId).put("label", read.label).put("revision", read.revision)
                .put("start", read.start).put("end", read.end).put("totalCharacters", read.totalCharacters).put("method", read.method.name)
        }))
    }.toString()

    fun decode(raw: String): ContextUsageRecord {
        val root = JSONObject(raw)
        require(root.optInt("version", 1) == 1) { "不支持的上下文引用记录版本" }
        return ContextUsageRecord(
            id = root.getString("id"),
            requestMessageId = root.getString("requestMessageId"),
            responseMessageId = root.optString("responseMessageId").takeIf(String::isNotBlank),
            branchId = root.getString("branchId"),
            answerIdentity = NovexAnswerIdentityCodec.decode(root.optJSONObject("answerIdentity")),
            includedSources = root.optJSONArray("includedSources").objects().map { source ->
                ContextSourceUsage(
                    kind = ContextSourceKind.valueOf(source.getString("kind")),
                    sourceId = source.getString("sourceId"),
                    label = source.getString("label"),
                    tokenCount = source.optInt("tokenCount"),
                )
            },
            omittedSources = root.optJSONArray("omittedSources").objects().map { source ->
                ContextSourceOmission(
                    kind = ContextSourceKind.valueOf(source.getString("kind")),
                    sourceId = source.getString("sourceId"),
                    label = source.getString("label"),
                    reason = source.getString("reason"),
                )
            },
            usedTokens = root.optInt("usedTokens"),
            effectiveWindowTokens = root.getInt("effectiveWindowTokens"),
            createdAt = root.optLong("createdAt"),
            teachingTraceRef = root.optString("teachingTraceRef").takeIf(String::isNotBlank),
            teachingTraceError = root.optString("teachingTraceError").takeIf(String::isNotBlank),
            sourceReads = root.optJSONArray("sourceReads").objects().map { read ->
                NovexSourceRead(read.getString("sourceId"), read.getString("label"), read.getString("revision"),
                    read.getInt("start"), read.getInt("end"), read.getInt("totalCharacters"),
                    NovexSourceReadMethod.valueOf(read.getString("method")))
            },
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        val array = this@objects ?: return@buildList
        repeat(array.length()) { index -> array.optJSONObject(index)?.let(::add) }
    }
}
