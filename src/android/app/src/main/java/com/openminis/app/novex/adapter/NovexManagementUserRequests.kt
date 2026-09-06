package com.openminis.app.novex.adapter

import com.openminis.app.data.db.MessageEntity
import org.json.JSONArray

/** Reads host-owned rows from the repository's selected conversation path. */
object NovexManagementUserRequests {
    fun fromActiveMessages(rows: List<MessageEntity>): List<String> = rows.mapNotNull { row ->
        if (row.role != "user") return@mapNotNull null
        val parts = JSONArray(row.partsJson)
        val values = (0 until parts.length()).map { parts.getJSONObject(it) }
        if (values.any { it.optString("type") == "toolResult" }) return@mapNotNull null
        values.filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("value") }.trim().takeIf(String::isNotEmpty)
    }
}
