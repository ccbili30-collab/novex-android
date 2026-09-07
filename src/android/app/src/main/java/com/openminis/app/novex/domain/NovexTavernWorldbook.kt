package com.openminis.app.novex.domain

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Local, single-pass subset. Archived data is never itself a prompt or a tool permission. */
object NovexTavernWorldbook {
    const val COMPATIBILITY = "只在该版本担任回答角色时，按采用快照执行启用、常驻、纯文本关键词、最近消息范围及角色定义前后插入。默认扫描最近两条可见消息，区分大小写关闭；本地每本最多 2048 词元并受本轮剩余额度限制。次关键词、递归、分组、概率、计时、宏、正则及未知运行配置暂不执行，受影响条目保留并暂停。"
    data class Result(val fragments: List<NovexContextFragment>, val omissions: List<ContextSourceOmission>)
    private data class Entry(val index: Int, val id: String, val title: String, val content: String,
        val constant: Boolean, val keys: List<String>, val depth: Int, val caseSensitive: Boolean,
        val order: Int, val position: String)

    fun adopted(configuration: NovexConversationConfigurationSnapshot): Pair<String, String>? {
        val actor = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId ?: return null
        val sources = NovexEffectiveFrozenContext.sources(configuration).filter { it.actorVersionId == actor && it.tavernWorldbookJson != null }
        val books = sources.mapNotNull { it.tavernWorldbookJson }.distinct()
        require(books.size <= 1) { "回答角色存在不同世界书采用修订，请明确刷新当前身份" }
        return books.singleOrNull()?.let { actor to it }
    }

    fun capture(profileJson: String): String? {
        val original = NovexTavernExchange.originalSource(profileJson) ?: return null
        val root = JSONObject(original); val data = root.optJSONObject("data") ?: root
        if(!data.has("character_book") || data.isNull("character_book")) return null
        // Keep the exact field, including malformed types, so unsupported data fails closed.
        return JSONObject().put("runtimeVersion", 1).put("book", data.get("character_book"))
            .put("defaultScanDepth", 2).put("defaultCaseSensitive", false).put("defaultPosition", "after_char")
            .put("localTokenBudget", 2048).toString()
    }
    fun evaluate(versionId: String, snapshot: String, visibleMessages: List<String>, tokenBudget: Int,
        countTokens: (String) -> Int): Result {
        val revision = NovexFrozenContextCodec.digest(snapshot)
        val prefix = "tavern-worldbook:$versionId:$revision"
        val omissions = mutableListOf<ContextSourceOmission>()
        fun omit(id: String, label: String, reason: String) {
            omissions += ContextSourceOmission(ContextSourceKind.BACKGROUND_MODULE, id, "世界书 · $label", reason)
        }
        val envelope = runCatching { JSONObject(snapshot) }.getOrElse {
            omit(prefix, "采用快照", "格式无效，未执行"); return Result(emptyList(), omissions)
        }
        val book = envelope.optJSONObject("book")
        if(envelope.optInt("runtimeVersion") != 1 || book == null) {
            omit(prefix, "采用快照", "不支持的世界书格式，未执行"); return Result(emptyList(), omissions)
        }
        val bookFailure = runCatching {
            require(book.keys().asSequence().all { it in setOf("name", "description", "entries", "scan_depth", "token_budget", "recursive_scanning", "extensions") }) { "含未支持的书级字段" }
            require(!bool(book, "recursive_scanning", false)) { "递归扫描尚未执行" }
            require((book.optJSONObject("extensions")?.length() ?: 0) == 0) { "含未支持的书级扩展" }
            if(book.has("extensions")) require(book.get("extensions") is JSONObject) { "书级扩展格式无效" }
            require(book.optJSONArray("entries") != null) { "条目列表格式无效" }
        }.exceptionOrNull()?.message
        if(bookFailure != null) { omit(prefix, book.optString("name").ifBlank { "整本" }, bookFailure); return Result(emptyList(), omissions) }
        val candidates = mutableListOf<Entry>()
        val entries = book.getJSONArray("entries")
        for(index in 0 until entries.length()) {
            val id = "$prefix:$index"
            val row = entries.optJSONObject(index)
            val title = row?.optString("name")?.ifBlank { row.optString("comment") }?.ifBlank { "条目 ${index + 1}" } ?: "条目 ${index + 1}"
            if(row == null) { omit(id, title, "条目不是对象"); continue }
            if(row.opt("enabled") == false) { omit(id, title, "已关闭"); continue }
            val parsed = runCatching {
                require(bool(row, "enabled", null)) { "已关闭" }
                require(row.keys().asSequence().all { it in setOf("id", "name", "comment", "enabled", "constant", "keys", "secondary_keys", "selective", "case_sensitive", "content", "insertion_order", "position", "use_regex", "extensions") }) { "含未支持的条目字段" }
                val ext = if(row.has("extensions")) row.get("extensions") as? JSONObject ?: error("扩展格式无效") else JSONObject()
                val allowed = setOf("scan_depth", "case_sensitive", "position")
                val inertBooleans = setOf("exclude_recursion", "prevent_recursion", "vectorized", "useProbability", "match_whole_words", "group_override", "use_group_scoring", "addMemo", "ignore_budget")
                val inertNumbers = setOf("sticky", "cooldown", "delay", "delay_until_recursion", "selectiveLogic")
                ext.keys().asSequence().forEach { key -> when {
                    key in allowed -> Unit
                    key in inertBooleans -> require(ext.get(key) == false) { "尚未支持扩展 $key" }
                    key in inertNumbers -> require(ext.get(key) is Number && (ext.get(key) as Number).toDouble() == 0.0) { "尚未支持扩展 $key" }
                    key in setOf("group", "automation_id", "outlet") -> require(ext.get(key) == "") { "尚未支持扩展 $key" }
                    key == "probability" -> require(ext.get(key) is Number && (ext.get(key) as Number).toDouble() == 100.0) { "概率条件尚未支持" }
                    else -> error("尚未支持扩展 $key")
                } }
                require(!bool(row, "use_regex", false)) { "正则条件尚未支持" }
                require(!bool(row, "selective", false)) { "次关键词筛选尚未支持" }
                val keys = strings(row, "keys")
                val secondary = if(row.has("secondary_keys")) strings(row, "secondary_keys") else emptyList()
                require((keys + secondary).none { it.contains("{{") || Regex("^/.+/[a-z]*$").matches(it) }) { "宏或正则关键词尚未支持" }
                val body = row.get("content") as? String ?: error("正文格式无效")
                require(!body.contains("{{") && !body.trimStart().startsWith("@@")) { "宏或修饰指令尚未支持" }
                val standardCase = bool(row, "case_sensitive", envelope.getBoolean("defaultCaseSensitive"))
                val sensitive = bool(ext, "case_sensitive", standardCase)
                require(!row.has("case_sensitive") || !ext.has("case_sensitive") || standardCase == sensitive) { "大小写设置冲突" }
                val standardPosition = if(row.has("position")) row.get("position") as? String ?: error("插入位置格式无效") else envelope.getString("defaultPosition")
                val position = if(ext.has("position")) when(integer(ext, "position", 1, 0..1)) { 0 -> "before_char"; else -> "after_char" } else standardPosition
                require(position in setOf("before_char", "after_char")) { "插入位置尚未支持" }
                require(!row.has("position") || !ext.has("position") || standardPosition == position) { "插入位置冲突" }
                Entry(index, id, title, body, bool(row, "constant", false), keys.filter { it.isNotBlank() },
                    integer(ext, "scan_depth", integer(book, "scan_depth", envelope.getInt("defaultScanDepth"), 0..100), 0..100),
                    sensitive, integer(row, "insertion_order", 100, Int.MIN_VALUE..Int.MAX_VALUE), position)
            }
            if(parsed.isFailure) { omit(id, title, parsed.exceptionOrNull()?.message ?: "条目配置无效"); continue }
            val entry = parsed.getOrThrow()
            val scan = visibleMessages.takeLast(entry.depth)
            val hit = entry.constant || entry.keys.any { key -> scan.any { message ->
                if(entry.caseSensitive) message.contains(key) else message.lowercase(Locale.ROOT).contains(key.lowercase(Locale.ROOT))
            } }
            if(!hit) { omit(id, title, if(entry.depth == 0) "扫描范围为零" else "最近 ${entry.depth} 条消息未命中主关键词"); continue }
            if(entry.content.isBlank()) { omit(id, title, "正文为空"); continue }
            candidates += entry
        }
        val bookBudget = runCatching { integer(book, "token_budget", envelope.getInt("localTokenBudget"), 0..1_000_000) }.getOrElse {
            omit(prefix, "整本", it.message ?: "预算无效"); return Result(emptyList(), omissions)
        }
        var remaining = minOf(tokenBudget.coerceAtLeast(0), envelope.getInt("localTokenBudget"), bookBudget)
        val chosen = mutableListOf<Pair<Entry, Int>>()
        // Local deterministic budget order, explicitly not the full Tavern ranking algorithm.
        candidates.sortedWith(compareByDescending<Entry> { it.constant }.thenBy { it.order }.thenBy { it.index }).forEach { entry ->
            val tokens = countTokens(entry.content).coerceAtLeast(0)
            if(tokens > remaining) omit(entry.id, entry.title, "本轮或全书预算不足，整条未注入")
            else { remaining -= tokens; chosen += entry to tokens }
        }
        return Result(chosen.sortedWith(compareBy<Pair<Entry, Int>> { it.first.order }.thenBy { it.first.index }).map { (entry, tokens) ->
            val reason = if(entry.constant) "常驻" else "主关键词命中，最近 ${entry.depth} 条消息"
            NovexContextFragment(ContextSourceKind.BACKGROUND_MODULE, "${entry.id}:${entry.position}", "世界书 · ${entry.title} · $reason", entry.content, tokens)
        }, omissions)
    }
    private fun bool(json: JSONObject, key: String, default: Boolean?): Boolean {
        if(!json.has(key)) return default ?: error("缺少明确的 $key（启用）设置")
        return json.get(key) as? Boolean ?: error("$key 必须是真或假")
    }
    private fun integer(json: JSONObject, key: String, default: Int, range: IntRange): Int {
        if(!json.has(key)) return default
        val number = json.get(key) as? Number ?: error("$key 必须为整数")
        val value = number.toDouble()
        require(value.isFinite() && value == number.toInt().toDouble() && number.toInt() in range) { "$key 超出支持范围" }
        return number.toInt()
    }
    private fun strings(json: JSONObject, key: String): List<String> {
        val array = json.get(key) as? JSONArray ?: error("$key 必须为文本数组")
        return (0 until array.length()).map { array.get(it) as? String ?: error("$key 含非文本关键词") }
    }
}
