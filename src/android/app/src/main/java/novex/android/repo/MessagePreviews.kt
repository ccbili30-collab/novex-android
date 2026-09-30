package novex.android.repo

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话文本投影引擎 —— parts_json（消息部件的 JSON 数组）到各种「给用户看
 * 或给 agent 看」的文本形态的纯转换集合。
 *
 * 三种投影各有消费者，互不混用：
 *  - [previewOf]：会话列表的一行预览（≤100 字符，markdown 已清洗）；
 *  - [fullTextOf]：offload/搜索用的全文（保留换行，剥系统提醒）；
 *  - [snippetAround]：搜索命中处的居中摘要。
 *
 * parts_json 的部件判别式是 JSON 的 "type" 字段（camelCase：text /
 * mediaRef / toolUse / toolResult），与序列化层的 @SerialName 一致。
 */
internal object MessagePreviews {

    // harness 注入的运行时提醒（<system-reminder>…</system-reminder>）不是
    // 用户输入，不允许出现在任何投影里。DOTALL 覆盖多行体；惰性量词避免
    // 相邻两个提醒被并成一个大匹配吞掉中间正文。
    private val SYSTEM_REMINDER = Regex(
        """<system-reminder>.*?</system-reminder>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun stripSystemReminders(raw: String): String =
        SYSTEM_REMINDER.replace(raw, "").trim()

    /**
     * 会话列表预览。优先级：第一段非空文本 > 图片占位 > 工具调用摘要。
     * 解析失败但内容非空时按纯文本清洗兜底；完全没有可展示内容返回 null
     * （调用方以此区分「不更新预览」与「清空预览」）。
     */
    fun previewOf(partsJson: String): String? {
        val parts = try {
            JSONArray(partsJson)
        } catch (_: Exception) {
            return if (partsJson.isNotBlank()) singleLine(partsJson) else null
        }
        var sawMedia = false
        var lastToolUse: JSONObject? = null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> {
                    val text = part.optString("value", "")
                    if (text.isNotBlank()) return singleLine(text)
                }
                "mediaRef" -> sawMedia = true
                "toolUse" -> part.optJSONObject("value")?.let { lastToolUse = it }
            }
        }
        if (sawMedia) return "[Image]"
        return lastToolUse?.let { toolUseSummary(it) }
    }

    /**
     * 工具调用的一行摘要（回合尚无文本时列表显示它）。取值顺序：
     * 模型给的 tool_title（存于 value.description 或 input.tool_title）>
     * 按工具族挑最有信息量的参数 > `🔧 工具名`。上限 100 字符。
     */
    private fun toolUseSummary(value: JSONObject): String {
        val toolName = value.optString("name", "")
        val titled = value.optString("description", "").trim()
        // input 以转义 JSON 字符串落库，防御式解析。
        val input = parseJsonObjectArg(value) ?: JSONObject()

        fun arg(key: String): String? = input.optString(key, "").trim().ifEmpty { null }

        val title = arg("tool_title") ?: titled.ifEmpty { null }
        if (title != null) return cap(singleLine(title))

        val line = when (toolName) {
            "shell_execute" -> arg("command")?.let { "$ $it" }
            "file_read" -> arg("path")?.let { "Reading $it" }
            "file_write" -> arg("path")?.let { "Writing $it" }
            "file_edit" -> arg("path")?.let { "Editing $it" }
            "browser_use" -> {
                val action = arg("action") ?: "browse"
                arg("url")?.let { "$action $it" } ?: "browser_use $action"
            }
            "memory_write" -> arg("content")?.let { "memory_write: $it" }
            "memory_get" -> {
                val words = input.optJSONArray("keywords")?.run {
                    buildString {
                        for (i in 0 until length()) {
                            if (i > 0) append(", ")
                            append(optString(i))
                        }
                    }
                }.orEmpty()
                if (words.isNotBlank()) "memory_get: $words"
                else arg("keywords")?.let { "memory_get: $it" }
            }
            else -> null
        }
        return line?.let { cap(singleLine(it)) } ?: cap("🔧 ${toolName.ifEmpty { "tool" }}")
    }

    private fun parseJsonObjectArg(value: JSONObject): JSONObject? = try {
        when (val raw = value.opt("input")) {
            is JSONObject -> raw
            is String -> if (raw.isBlank()) JSONObject() else JSONObject(raw)
            else -> JSONObject()
        }
    } catch (_: Exception) {
        JSONObject()
    }

    private fun cap(text: String, limit: Int = 100): String =
        if (text.length <= limit) text else text.take(limit) + "…"

    /** markdown → 单行纯文本，≤100 字符。规则以列表驱动，便于逐条核对。 */
    private val MARKDOWN_STRIP_RULES: List<Pair<Regex, String>> = listOf(
        Regex("[\\r\\n]+") to " ",
        Regex("#{1,6}\\s") to "",
        Regex("\\*{1,3}|_{1,3}") to "",
        Regex("~~") to "",
        Regex("`{1,3}") to "",
        Regex("^\\s*[-*+]\\s", RegexOption.MULTILINE) to "",
        Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE) to "",
        Regex("^>\\s?", RegexOption.MULTILINE) to "",
        Regex("\\[([^]]+)]\\([^)]+\\)") to "$1",
        Regex("!\\[([^]]*)]\\([^)]+\\)") to "$1",
        Regex("\\s{2,}") to " ",
    )

    internal fun singleLine(raw: String): String {
        var text = stripSystemReminders(raw)
        for ((pattern, replacement) in MARKDOWN_STRIP_RULES) {
            text = pattern.replace(text, replacement)
        }
        return text.trim().take(100)
    }

    /**
     * offload 全文：拼接全部文本块（剥系统提醒、换行保留）；无文本时依次
     * 回落 [Image] / 工具调用名（带 tool_title 优先）/ 工具结果前 200 字。
     */
    fun fullTextOf(partsJson: String): String {
        val parts = try {
            JSONArray(partsJson)
        } catch (_: Exception) {
            return stripSystemReminders(partsJson)
        }
        val texts = ArrayList<String>()
        var sawMedia = false
        val toolUses = ArrayList<JSONObject>()
        val toolResults = ArrayList<JSONObject>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> part.optString("value", "").takeIf { it.isNotBlank() }
                    ?.let { texts.add(stripSystemReminders(it)) }
                "mediaRef" -> sawMedia = true
                "toolUse" -> part.optJSONObject("value")?.let { toolUses.add(it) }
                "toolResult" -> part.optJSONObject("value")?.let { toolResults.add(it) }
            }
        }
        if (texts.isNotEmpty()) return texts.joinToString("\n")
        if (sawMedia) return "[Image]"
        if (toolUses.isNotEmpty()) {
            return toolUses.joinToString(", ") { use ->
                val titled = parseJsonObjectArg(use)?.optString("tool_title", "").orEmpty()
                if (titled.isNotBlank()) titled.take(100) else use.optString("name", "tool")
            }
        }
        if (toolResults.isNotEmpty()) {
            return toolResults.joinToString("\n") { result ->
                "[Tool result: ${result.optString("output", "").take(200)}]"
            }
        }
        return ""
    }

    /**
     * 搜索命中摘要：以最早命中的关键词为中心取 [maxLength] 字符，截断处
     * 加省略号。关键词全部没落在提取文本里（SQL LIKE 命中了工具 JSON 元
     * 数据的罕见情形）时退回开头 [maxLength] 字符 —— 至少给 agent 一点上下文。
     */
    fun snippetAround(text: String, keywords: List<String>, maxLength: Int): String {
        if (text.isEmpty()) return ""
        val folded = text.lowercase()
        var anchor = text.length
        for (keyword in keywords) {
            val at = folded.indexOf(keyword.lowercase())
            if (at in 0 until anchor) anchor = at
        }
        if (anchor == text.length) return text.take(maxLength)
        val start = (anchor - maxLength / 2).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(text.length)
        val core = text.substring(start, end)
        return buildString {
            if (start > 0) append('…')
            append(core)
            if (end < text.length) append('…')
        }
    }
}
