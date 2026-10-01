package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * memory_write / memory_get 的工具定义与执行（血统清剿 P3.7 就地真重写；
 * 两份 JSON 定义的全部文案与执行结果串为模型面契约冻结面）。
 * schema 与 iOS AgentToolDefinition 逐字段一致。
 */
object MemoryTools {

    // -- 工具定义（Anthropic 形态） -----------------------------------------

    fun memoryWriteToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", stringParam("A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."))
            put("content", stringParam("The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."))
        }
        return toolObject(
            name = "memory_write",
            description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. Each entry is prepended with a timestamp. Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",
            properties = properties,
            required = listOf("tool_title", "content"),
        )
    }

    fun memoryGetToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", stringParam("A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."))
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.")
                put("enum", JSONArray().apply {
                    put("daily")
                    put("all")
                })
            })
            put("keywords", stringParam("Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."))
        }
        return toolObject(
            name = "memory_get",
            description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
            properties = properties,
            required = listOf("tool_title"),
        )
    }

    // -- OpenAI Function Calling 形态 ----------------------------------------

    fun memoryWriteOpenAIDefinition(): JSONObject = openAIWrapper(memoryWriteToolDefinition())

    fun memoryGetOpenAIDefinition(): JSONObject = openAIWrapper(memoryGetToolDefinition())

    // -- 执行 ----------------------------------------------------------------

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository): ToolResult = try {
        val obj = JSONObject(inputJson)
        val content = obj.optString("content", "")
        val title = obj.optString("tool_title", "memory_write")

        if (content.isBlank()) {
            ToolResult("Error: Missing required 'content' parameter", false, title)
        } else {
            // 仓库以 "Memory saved" 前缀回告成功。
            val outcome = repository.writeMemory(content)
            ToolResult(outcome, outcome.startsWith("Memory saved"), title)
        }
    } catch (e: Exception) {
        ToolResult("Error: ${e.message}", false)
    }

    fun executeMemoryGet(
        inputJson: String,
        repository: MemoryRepository,
        excludedBranchEntries: Map<String, Int> = emptyMap(),
    ): ToolResult = try {
        val obj = JSONObject(inputJson)
        ToolResult(
            repository.getMemory(
                keywords = obj.optString("keywords", ""),
                scope = obj.optString("scope", "all"),
                excludedBranchEntries = excludedBranchEntries,
            ),
            true,
            obj.optString("tool_title", "memory_get"),
        )
    } catch (e: Exception) {
        ToolResult("Error: ${e.message}", false)
    }

    // -- 组装小件 ------------------------------------------------------------

    private fun stringParam(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun toolObject(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray(required))
            })
        }

    /** 复用 Anthropic 形态定义的 description 与 input_schema，包一层 function 壳。 */
    private fun openAIWrapper(anthropic: JSONObject): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", anthropic.getString("name"))
            put("description", anthropic.getString("description"))
            put("parameters", anthropic.getJSONObject("input_schema"))
        })
    }
}
