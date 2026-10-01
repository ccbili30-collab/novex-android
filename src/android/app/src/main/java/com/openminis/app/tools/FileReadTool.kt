package com.openminis.app.tools

import android.content.Context
import java.io.File
import novex.android.data.ContentPaths
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.AgentToolParam
import org.json.JSONObject

/**
 * file_read 工具（血统清剿 P3.7 就地真重写；工具定义文案、错误串与截断
 * 头格式为模型面契约冻结面）。
 *
 * 读 Linux 文件系统的文件：比 shell_execute 快——没有 shell 开销；返回
 * 内容带元数据；拒绝二进制。
 */
object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    /** 一次读取请求的全部参数（已钳界）。 */
    private class ReadRequest(
        val path: String,
        val title: String,
        val offset: Int,
        val maxChars: Int,
        val direction: String,
        val lineCap: Int?,
    )

    /**
     * T-FILEREAD-CAP：返回内容长度的硬顶。没有它之前，agent 要
     * `max_length=1_000_000` 我们就照办——一张 400 KB 的 base64 图被整段
     * 塞进 tool_result，渲染成单条用户气泡，Compose 的 StaticLayout /
     * LineBreaker 锁死几十秒（见会话 e84882d7-2087-47f8-9300-ff2c897fe0b4
     * 的 HangDetector 报告：820 KB partsJson、43 秒卡在
     * nComputeLineBreaks）。无论请求多少都钳在 80 KB；下面的截断尾巴会把
     * 全文件大小告诉 agent，需要时用 offset/lines 翻页。iOS 在
     * AIChatViewModel.executeFileRead 镜了同一顶。
     */
    private const val HARD_CHAR_CAP = 80_000

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val parsed = try {
            parseRequest(JSONObject(argsJson))
        } catch (e: Exception) {
            return ToolExecutionResult("Error reading file: ${e.message}", false)
        }
        return try {
            executeParsed(parsed, sessionId, context)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }

    private fun parseRequest(args: JSONObject): ReadRequest {
        val requestedCap = args.optInt("max_length", 15000).coerceAtMost(HARD_CHAR_CAP)
        return ReadRequest(
            path = args.optString("path", ""),
            title = args.optString("tool_title", NAME),
            offset = args.optInt("offset", 1).coerceAtLeast(1),
            maxChars = requestedCap,
            direction = args.optString("direction", "head"),
            lineCap = if (args.has("lines")) args.optInt("lines") else null,
        )
    }

    private fun executeParsed(req: ReadRequest, sessionId: String, context: Context): ToolExecutionResult {
        val fail: (String) -> ToolExecutionResult = { ToolExecutionResult(it, false, toolTitle = req.title) }
        if (req.path.isBlank()) return fail("Error: 'path' is required")

        // T123：按会话解析——理由见 FileWriteTool。
        val file = ContentPaths.resolveSessionHostPath(sessionId, req.path, context)
            ?: return fail("Error: Cannot resolve path: ${req.path}")
        if (!file.exists()) return fail("Error: File not found: ${req.path}")
        if (file.isDirectory) return fail("Error: Path is a directory: ${req.path}")

        val size = file.length()
        if (looksBinary(file, size)) {
            return ToolExecutionResult(
                "[${req.path} | $size bytes | binary file — cannot display contents]",
                true, toolTitle = req.title,
            )
        }

        val allLines = file.readLines()
        val totalLines = allLines.size

        // 先选行窗口，再做字符截断。
        val window = selectLineWindow(allLines, req)
        var content = window.text.joinToString("\n")

        var shownStart = window.startLine
        var shownEnd = window.startLine + window.text.size - 1

        // [T-fileread-truncation-header] 头部曾报「截断前」选中的行区间、
        // 且只字不提截断——只有正文多出一行 "... (truncated)"。被掐断的
        // 读取仍宣布 "showing 1-1324 of 1324"，agent 当作全文件、从不翻
        // 页。改为重算幸存区间并交回续读 offset；只动截断分支，装得下的
        // 读取与从前逐字节一致。
        var nextOffset: Int? = null
        var truncated = false
        if (content.length > req.maxChars) {
            truncated = true
            if (req.direction == "tail") {
                // tail 要的是文件**末尾**；take() 拿到的是尾窗开头——正相反。
                content = content.takeLast(req.maxChars)
                // 丢掉开头的半行，保证第一行完整。
                content.indexOf('\n').let { first ->
                    if (first in 0 until content.length - 1) content = content.substring(first + 1)
                }
                shownStart = shownEnd - content.count { it == '\n' }
                // tail 不给 next_offset：从文件末尾向前翻页没有意义。
            } else {
                content = content.take(req.maxChars)
                // 回退到最后一个完整行，下一页不重读、不劈行。
                content.lastIndexOf('\n').let { last ->
                    if (last > 0) content = content.substring(0, last)
                }
                shownEnd = shownStart + content.count { it == '\n' }
                if (shownEnd < totalLines) nextOffset = shownEnd + 1
            }
        }

        val header = buildString {
            append("[${req.path} | $size bytes | $totalLines lines | ")
            append("showing $shownStart-$shownEnd of $totalLines")
            if (truncated) {
                append(" | truncated at ${req.maxChars} chars")
                // 与工具自己的 `offset` 参数同名——模型照抄进下一次调用即可。
                append(
                    when (val resume = nextOffset) {
                        null -> ", retry with a smaller lines value"
                        else -> ", next_offset=$resume"
                    },
                )
            }
            append("]")
        }
        return ToolExecutionResult("$header\n$content", true, toolTitle = req.title)
    }

    private class LineWindow(val text: List<String>, val startLine: Int)

    /** 按方向选行窗口：head 从 offset 起、tail 取末 N 行。 */
    private fun selectLineWindow(all: List<String>, req: ReadRequest): LineWindow {
        val total = all.size
        return if (req.direction == "tail") {
            val count = req.lineCap ?: total
            val from = (total - count).coerceAtLeast(0)
            LineWindow(all.subList(from, total), from + 1)
        } else {
            val from = (req.offset - 1).coerceIn(0, total)
            val to = if (req.lineCap != null) (from + req.lineCap).coerceAtMost(total) else total
            LineWindow(all.subList(from, to), req.offset)
        }
    }

    /** 二进制探测：头 8192 字节里找 null 字节。 */
    private fun looksBinary(file: File, size: Long): Boolean =
        file.inputStream().use { input ->
            val probe = ByteArray(minOf(8192, size.toInt()))
            val got = input.read(probe)
            got > 0 && probe.take(got).any { it == 0.toByte() }
        }
}
