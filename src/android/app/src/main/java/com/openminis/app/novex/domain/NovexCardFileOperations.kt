package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Small file-like interface; all writes still cross the existing management transaction/permission seam. */
class NovexCardFileOperations(private val sources: NovexCardSourceModules) {
    internal fun prepare(tool: String, args: JSONObject): String = when (tool) {
        "novex_write_card" -> create(args)
        "novex_update_card" -> updateCard(args)
        "novex_write_module" -> writeModule(args)
        "novex_move_module" -> moveModule(args)
        "novex_link_cards" -> link(args)
        else -> error("未知卡片操作")
    }

    /** Recheck visibility after preparation, without reopening or reparsing immutable source bytes. */
    internal fun requireSourceAccess(args: JSONObject) {
        if (args.has("document_ref")) sources.requireAccess(args.getString("document_ref"))
        args.optJSONObject("source")?.let { sources.requireAccess(it.getString("document_ref")) }
        args.optJSONArray("modules")?.let { modules ->
            repeat(modules.length()) { index -> modules.getJSONObject(index).optJSONObject("source")?.let {
                sources.requireAccess(it.getString("document_ref"))
            } }
        }
    }

    fun create(args: JSONObject): String {
        val kind = args.getString("kind")
        require(kind in setOf("world", "character", "game")) { "kind（卡片类型）请选择 world（世界）、character（角色）或 game（文游）" }
        val operation = JSONObject().put("operation", "create_$kind").put("name", args.getString("name"))
        when (kind) {
            "world" -> operation.put("overview", args.optString("summary", ""))
            "character" -> operation.put("profile_json", JSONObject().put("name", args.getString("name")).put("summary", args.optString("summary")))
            "game" -> {
                operation.put("summary", args.optString("summary", ""))
                operation.put("launch_mode", args.optString("launch_mode", "free_sandbox"))
            }
        }
        val hasSource = args.has("document_ref")
        require(!(hasSource && args.has("modules"))) { "整份文档按章节复制与自定义模块二选一；不要同时提交" }
        val modules = if (hasSource) sources.chapters(args.getString("document_ref"), args.getString("source_revision"))
            else JSONArray().apply {
                val input = requireNotNull(args.optJSONArray("modules")) { "请提供 modules（模块数组）或 document_ref（来源文档），不要只创建空容器" }
                require(input.length() in 1..1000) { "请填写一到一千个模块；多张卡可以分别创建" }
                for (i in 0 until input.length()) {
                    val row = input.getJSONObject(i)
                    put(JSONObject().put("module_type", row.optString("type", "custom"))
                        .put("name", row.getString("name")).put("content_json", moduleContent(row)))
                }
            }
        operation.put("modules", modules)
        return JSONArray().put(operation).toString()
    }

    fun updateCard(args: JSONObject): String {
        val row = JSONObject().put("operation", "update_card")
            .put("subject_kind", args.getString("kind")).put("subject_id", args.getString("card_id"))
        listOf("name", "summary", "launch_mode").forEach { if (args.has(it)) row.put(it, args.get(it)) }
        return JSONArray().put(row).toString()
    }

    fun writeModule(args: JSONObject): String {
        val row = JSONObject()
        val mode = args.optString("mode", "replace")
        require(mode in setOf("replace", "append")) { "写入方式请选择 replace（替换正文）或 append（追加文本）" }
        if (args.has("module_id")) {
            row.put("operation", "update_module").put("module_id", args.getString("module_id"))
            if (args.has("name")) row.put("name", args.getString("name"))
            if (mode == "append") {
                require(args.has("text") && !args.has("content") && !args.has("source")) { "追加使用 text（新增的文本），不要重复发送原正文" }
                row.put("append_text", args.getString("text"))
            } else if (listOf("text", "content", "source").any(args::has)) row.put("content_json", moduleContent(args))
            require(row.has("name") || row.has("content_json") || row.has("append_text")) { "请提供新名称或正文；未指定字段保持不变" }
        } else {
            require(mode == "replace") { "追加正文需要已有 module_id（模块编号）" }
            row.put("operation", "add_module").put("subject_kind", args.getString("kind"))
                .put("subject_id", args.getString("card_id")).put("module_type", args.optString("type", "custom"))
                .put("name", args.getString("name")).put("content_json", moduleContent(args))
        }
        return JSONArray().put(row).toString()
    }

    fun moveModule(args: JSONObject): String = JSONArray().put(JSONObject().put("operation", "move_module")
        .put("module_id", args.getString("module_id")).put("to_index", args.getInt("position"))).toString()

    fun link(args: JSONObject): String {
        val row = JSONObject().put("operation", if (args.optBoolean("remove")) "remove_card_reference" else "put_card_reference")
            .put("subject_kind", args.getString("kind")).put("subject_id", args.getString("card_id"))
        if (!args.optBoolean("remove")) {
            row.put("target_kind", args.getString("target_kind")).put("target_id", args.getString("target_id"))
                .put("purpose", args.getString("purpose"))
            listOf("source_module_id", "target_module_id", "target_entry_id").forEach { if (args.has(it)) row.put(it, args.getString(it)) }
        }
        val suppliedId = args.optString("reference_id").takeIf(String::isNotBlank)
        require(!args.optBoolean("remove") || suppliedId != null) { "删除关联需要原 reference_id（引用编号），请从卡片关系列表读取" }
        // A new edge is identified by stable source/target addresses and purpose, never names.
        // Different source cards cannot collide, and retrying the same edge produces the same id.
        row.put("reference_id", suppliedId ?: NovexFrozenContextCodec.digest(row.toString()))
        return JSONArray().put(row).toString()
    }

    private fun moduleContent(row: JSONObject): JSONObject {
        require(listOf("text", "content", "source").count(row::has) == 1) { "正文请选一种方式：text（文本）、content（结构化内容）或 source（原文范围）" }
        row.optJSONObject("source")?.let { source ->
            return sources.range(source.getString("document_ref"), source.getString("source_revision"),
                source.getInt("first_block"), source.getInt("last_block"))
        }
        if (row.has("content")) return JSONObject(row.get("content").toString())
        val text = row.getString("text")
        require(text.isNotBlank()) { "模块正文不能为空；如只改名，请省略正文参数" }
        return JSONObject().put("version", 1).put("kind", "article").put("text", text)
    }
}
