package com.openminis.app.tools

import android.content.Context
import novex.android.data.ContentPaths
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.AgentToolParam
import org.json.JSONObject

/**
 * file_edit 工具（血统清剿 P3.7 就地真重写；工具定义文案与错误/成功串为
 * 模型面契约冻结面）。
 *
 * 对既有文件做定点编辑：精确串替换。改文件前**必须**先 file_read 看过现
 * 状；改既有文件优先 file_edit 而非 file_write——只需给出变化的那一段。
 * old_string 必须在文件里恰好命中一处（含空白/缩进），除非 replace_all。
 */
object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /root/script.py)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult = try {
        runEdit(JSONObject(argsJson), sessionId, context)
    } catch (e: Exception) {
        ToolExecutionResult("Error editing file: ${e.message}", false)
    }

    private fun runEdit(args: JSONObject, sessionId: String, context: Context): ToolExecutionResult {
        val fail: (String) -> ToolExecutionResult = {
            ToolExecutionResult(it, false, toolTitle = args.optString("tool_title", NAME))
        }

        val path = args.optString("path", "")
        val oldText = args.optString("old_string", "")
        val newText = args.optString("new_string", "")
        val replaceAll = args.optBoolean("replace_all", false)

        if (path.isBlank()) return fail("Error: 'path' is required")
        if (oldText.isEmpty()) return fail("Error: 'old_string' is required and cannot be empty")

        // T123：按会话解析——理由见 FileWriteTool。
        val file = ContentPaths.resolveSessionHostPath(sessionId, path, context)
            ?: return fail("Error: Cannot resolve path: $path")
        if (!file.exists()) return fail("Error: File not found: $path")

        val original = file.readText()
        val hits = countOccurrences(original, oldText)
        if (hits == 0) return fail("Error: old_string not found in $path")
        if (hits > 1 && !replaceAll) {
            return fail(
                "Error: old_string found $hits times in $path. Use replace_all=true to replace all occurrences, " +
                    "or provide a more specific old_string that matches exactly once.",
            )
        }

        val revised = if (replaceAll) original.replace(oldText, newText)
        else original.replaceFirst(oldText, newText)
        file.writeText(revised)

        val replacements = if (replaceAll) hits else 1
        return ToolExecutionResult(
            "Edited $path ($replacements replacement(s), ${revised.length} bytes)",
            true,
            toolTitle = args.optString("tool_title", NAME),
        )
    }

    /** 非重叠出现次数：游标每次跳过整段命中。 */
    private fun countOccurrences(haystack: String, needle: String): Int {
        var total = 0
        var cursor = 0
        while (true) {
            val at = haystack.indexOf(needle, cursor)
            if (at < 0) return total
            total++
            cursor = at + needle.length
        }
    }
}
