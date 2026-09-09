package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.toPlainText
import org.json.JSONArray
import org.json.JSONObject

/** Explicit module metadata. Ordinary prose is never guessed to contain activation rules. */
object NovexWorldbookConditions {
    const val FIELD = "contextTrigger"
    const val HELP = "可选 contextTrigger（使用条件）：{version:1, enabled:true, constant:false, keys:[关键词], scanDepth:2, caseSensitive:false}。常驻设 constant:true；未填写仍为普通资料。只扫描本分支最近的可见消息，不递归。"

    fun read(raw: String): String? = runCatching { JSONObject(raw) }.getOrNull()?.let { root ->
        if (!root.has(FIELD)) null else JSONArray().put(root.get(FIELD)).toString().drop(1).dropLast(1)
    }

    /** null means usable; malformed and unsupported conditions fail closed with a readable reason. */
    fun omission(conditions: List<String>, visibleMessages: List<String>): String? {
        for (raw in conditions) {
            val reason = runCatching {
                val value = JSONObject(raw)
                require(value.keys().asSequence().all { it in setOf("version", "enabled", "constant", "keys", "scanDepth", "caseSensitive") }) { "含暂不支持的使用条件" }
                require(integer(value, "version", 1, 1..1) == 1) { "使用条件版本暂不支持" }
                fun flag(key: String, default: Boolean): Boolean = if (!value.has(key)) default else value.get(key) as? Boolean ?: error("使用条件的开关格式无效")
                if (!flag("enabled", true)) return "已关闭"
                val depth = integer(value, "scanDepth", 2, 0..100)
                val array = if (value.has("keys")) value.get("keys") as? JSONArray ?: error("关键词须为文本列表") else JSONArray()
                val keys = (0 until array.length()).map { array.get(it) as? String ?: error("关键词须为文本") }
                require(keys.none { it.contains("{{") || Regex("^/.+/[a-z]*$").matches(it) }) { "宏与正则关键词暂不支持" }
                val sensitive = flag("caseSensitive", false)
                if (!flag("constant", false) && keys.none { key -> key.isNotBlank() && visibleMessages.takeLast(depth).any { it.contains(key, ignoreCase = !sensitive) } })
                    return if (depth == 0) "扫描范围为零" else "最近 $depth 条消息未命中关键词"
                null
            }.getOrElse { it.message ?: "使用条件无效，已暂停" }
            if (reason != null) return reason
        }
        return null
    }

    private fun integer(value: JSONObject, key: String, default: Int, range: IntRange): Int {
        if (!value.has(key)) return default
        val number = value.get(key) as? Number ?: error("使用条件的数值格式无效")
        require(number.toDouble() == number.toInt().toDouble() && number.toInt() in range) { "使用条件的数值超出范围" }
        return number.toInt()
    }

    /** Module conditions apply to all entries; an entry can add a narrower condition. */
    fun candidates(base: NovexContextCandidate, type: ContentModuleType, raw: String, entryId: String? = null): List<NovexContextCandidate> {
        if (base.kind == ContextSourceKind.ANSWER_IDENTITY) return listOf(base)
        val rootCondition = listOfNotNull(read(raw))
        val doc = ContentModuleDocumentCodec.decode(type, raw)
        val collection = doc as? ContentModuleDocument.Collection
        val hasEntryConditions = collection?.items.orEmpty().any { it.contextTriggerJson != null }
        if (collection != null && (hasEntryConditions || entryId != null)) {
            return collection.items.filter { entryId == null || it.id == entryId }.map { item ->
                base.copy(sourceId = base.sourceId.substringBefore(":entry:") + ":entry:${item.id}",
                    label = base.label.substringBefore(" · 条目") + " · " + item.name.ifBlank { "未命名条目" },
                    content = ContentModuleDocument.Collection(listOf(item)).toPlainText(),
                    aliases = setOf(item.name).filterTo(linkedSetOf()) { it.isNotBlank() },
                    worldbookConditions = rootCondition + listOfNotNull(item.contextTriggerJson))
            }
        }
        return listOf(base.copy(worldbookConditions = rootCondition))
    }
}

/** One shared budget for all independently adopted books and the current role's private book. */
object NovexWorldbookRuntime {
    fun evaluate(candidates: List<NovexContextCandidate>, actorBook: Pair<String, String>?, visibleMessages: List<String>,
        tokenBudget: Int, countTokens: (String) -> Int): NovexTavernWorldbook.Result {
        var remaining = tokenBudget.coerceIn(0, 2048)
        val fragments = mutableListOf<NovexContextFragment>()
        val omissions = mutableListOf<ContextSourceOmission>()
        candidates.filter { it.worldbookConditions.isNotEmpty() }.sortedWith(compareBy<NovexContextCandidate> { it.position }.thenBy { it.sourceId }).forEach { candidate ->
            val failure = NovexWorldbookConditions.omission(candidate.worldbookConditions, visibleMessages)
            val tokens = countTokens(candidate.content).coerceAtLeast(0)
            val reason = failure ?: if (candidate.content.isBlank()) "正文为空" else if (tokens > remaining) "本轮世界书共享预算不足，整条未采用" else null
            if (reason != null) omissions += ContextSourceOmission(candidate.kind, candidate.sourceId, candidate.label, reason)
            else {
                remaining -= tokens
                fragments += NovexContextFragment(candidate.kind, candidate.sourceId, candidate.label, candidate.content, tokens)
            }
        }
        actorBook?.let { (version, book) ->
            val result = NovexTavernWorldbook.evaluate(version, book, visibleMessages, remaining, countTokens)
            fragments += result.fragments; omissions += result.omissions
        }
        return NovexTavernWorldbook.Result(fragments, omissions)
    }
}
