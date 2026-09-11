package com.openminis.app.data.model

import org.json.JSONObject

/** 只解析上游明确声明的上下文容量，不把最大输出长度当作上下文。 */
object ReportedContextWindow {
    fun read(model:JSONObject):Int? {
        fun number(value:Any?):Int? {
            val raw=value?.toString()?.trim()?.replace(",","")?:return null
            val match=Regex("(?i)^([0-9]+(?:\\.[0-9]+)?)\\s*([km]?)$").matchEntire(raw)?:return null
            val factor=when(match.groupValues[2].lowercase()){ "m"->1_000_000;"k"->1000;else->1 }
            val result=match.groupValues[1].toDouble()*factor
            return result.takeIf {it>=1 && it<=Int.MAX_VALUE}?.toInt()
        }
        val direct=listOf("context_length","context_window","contextWindow","max_model_len","max_context_length","max_position_embeddings", "inputTokenLimit", "input_token_limit", "max_input_tokens")
        direct.forEach {key->number(model.opt(key))?.let {return it}}
        listOf("limit","limits","architecture","capabilities").forEach {name->
            model.optJSONObject(name)?.let {nested->
                (direct+"context").forEach {key->number(nested.opt(key))?.let {return it}}
            }
        }
        return null
    }
}
