package com.openminis.app.data.character

import org.json.JSONObject

/** Backward-compatible text payload used until individual module kinds gain richer schemas. */
object ContentModuleTextCodec {
    fun encode(text: String): String = JSONObject().put("text", text).toString()

    fun decode(contentJson: String): String = runCatching {
        JSONObject(contentJson).optString("text")
    }.getOrElse { contentJson }
}
