package com.openminis.app.tools

import android.content.Context
import com.openminis.app.logging.AppLogger
import novex.android.data.ContentPaths
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.AgentToolParam
import org.json.JSONObject

/**
 * file_write 工具（血统清剿 P3.7 就地真重写；工具定义文案、错误/成功串与
 * 挂载写诊断日志为契约冻结面）。
 *
 * 往 Linux 文件系统写文件：比 shell_execute 快。文件不存在即建；追加模
 * 式可续写既有文件。
 */
object FileWriteTool {
    const val NAME = "file_write"

    private const val LOG_TAG = "FileWrite"
    private const val MOUNTS_PREFIX = "/var/minis/mounts/"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Write content to a file on the Linux filesystem. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to write (e.g. /root/test.txt)"),
            "content" to AgentToolParam("string", "The text content to write to the file"),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult = try {
        val args = JSONObject(argsJson)
        val req = ParsedCall(
            path = args.optString("path", ""),
            content = args.optString("content", ""),
            append = args.optBoolean("append", false),
            createDirs = args.optBoolean("create_dirs", false),
            title = args.optString("tool_title", NAME),
        )
        runWrite(req, sessionId, context)
    } catch (e: Exception) {
        ToolExecutionResult("Error writing file: ${e.message}", false)
    }

    private class ParsedCall(
        val path: String,
        val content: String,
        val append: Boolean,
        val createDirs: Boolean,
        val title: String,
    )

    private fun runWrite(req: ParsedCall, sessionId: String, context: Context): ToolExecutionResult {
        val fail: (String) -> ToolExecutionResult = { ToolExecutionResult(it, false, toolTitle = req.title) }
        if (req.path.isBlank()) return fail("Error: 'path' is required")

        // T123：按会话解析——/var/minis/workspace/...、/var/minis/attachments/…、
        // /var/minis/offloads/…、/var/minis/browser/… 要落进本会话的宿主目录，
        // 而不是全局 bind-mount 表（别的会话一开 shell 就整表覆写，后写者赢）。
        val file = ContentPaths.resolveSessionHostPath(sessionId, req.path, context)
            ?: return fail("Error: Cannot resolve path: ${req.path}")

        // UTF-8 合法性校验。
        try {
            req.content.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            return fail("Error: Content is not valid UTF-8")
        }

        // T123：对齐 iOS AIChatViewModel L8339——父目录不存在时一律自动创建，
        // 不看 create_dirs 旗标。会话子目录（workspace 等）是惰性物化的，
        // 否则新会话第一次写 /var/minis/workspace/foo/bar.md 就会撞上
        // 「父目录不存在」。
        ensureParentFor(file, req.createDirs)

        if (req.append) file.appendText(req.content) else file.writeText(req.content)

        val bytes = file.length()
        diagnoseMountLanding(req.path, file, bytes)
        return ToolExecutionResult("Wrote to ${req.path} ($bytes bytes)", true, toolTitle = req.title)
    }

    private fun ensureParentFor(file: java.io.File, explicitCreate: Boolean) {
        val parent = file.parentFile ?: return
        if (explicitCreate || !parent.exists()) parent.mkdirs()
    }

    /**
     * 诊断「写报告成功、盘上却什么都没有」（Android 10 legacy-storage 的
     * FUSE 影子写）：写完立刻回看文件是否真在、尺寸是否如预期。挂载目录
     * 的静默空写在这里现形（exists=false / 尺寸错位）。
     */
    private fun diagnoseMountLanding(path: String, file: java.io.File, bytes: Long) {
        if (!path.startsWith(MOUNTS_PREFIX)) return
        val present = file.exists()
        val landed = present && file.length() == bytes
        AppLogger.info(
            LOG_TAG,
            "mount write path=$path host=${file.absolutePath} bytes=$bytes " +
                "exists=$present landedOk=$landed",
        )
        if (!landed) {
            AppLogger.warning(
                LOG_TAG,
                "mount write to $path reported success but did NOT persist to " +
                    "${file.absolutePath} — likely missing WRITE_EXTERNAL_STORAGE / " +
                    "shadowed FUSE view on this device",
            )
        }
    }
}
