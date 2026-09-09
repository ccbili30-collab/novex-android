package com.openminis.app.novex.domain

import org.json.JSONObject

/** Saving captures software-owned originals; a model need not reconstruct them. */
data class NovexCheckpointInput(val name: String, val summary: String, val stateJson: String) {
    companion object {
        fun parse(raw: String): NovexCheckpointInput {
            val args = JSONObject(raw)
            val name = args.optString("name").trim()
            require(name.isNotEmpty()) { "存档名称不能为空" }
            val legacy = args.optString("state").trim()
            val summary = args.optString("summary").trim().ifBlank {
                legacy.lineSequence().firstOrNull(String::isNotBlank)?.take(240)
                    ?: "已保存当前对话、采用的设定与本局状态。"
            }
            val state = args.optString("state_json").trim().ifBlank {
                if (legacy.isNotEmpty()) JSONObject().put("legacy_markdown", legacy).toString() else "{}"
            }
            require(runCatching { JSONObject(state) }.isSuccess) { "补充状态必须是 JSON（结构化数据）对象" }
            return NovexCheckpointInput(name, summary, state)
        }
    }
}
