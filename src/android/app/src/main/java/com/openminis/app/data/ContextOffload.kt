package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * 按会话的上下文落盘助手（血统清剿 P3.7 就地真重写；路径布局、哨兵前缀
 * 与 stub 文案为跨平台契约冻结面）。把大工具输出写到磁盘，模型之后可以
 * `file_read` 取回；历史里的原件换成一小段
 * `[CONTEXT OFFLOADED] … <linux-path>` 存根。
 *
 * 对齐 iOS `AIChatViewModel.minisOffloadsPersistentDir(for:)`、
 * `offloadContextContent(_:toolId:toolName:ext:)`、
 * `offloadContextImage(_:toolId:mimeType:)`（AIChatViewModel.swift:6964 +
 * 7170 + 7188）。路径布局同构——`…/offloads/tools/<name>_<id>.<ext>`——
 * 安卓落盘的会话经云同步在 iOS 打开（或反向）时 file_read 路径原样可用。
 *
 * Linux 可见挂载：`/var/minis/offloads/tools/<file>`。宿主基座
 * `filesDir/minis-sessions/<sid>/offloads` 经 ContentPaths 的会话子目录
 * 解析（那里含 "offloads" 目录）。
 */
object ContextOffload {
    /** Linux 侧挂载点——与 iOS `minisOffloadsLinuxDir` 锁步。 */
    const val LINUX_OFFLOADS_DIR = "/var/minis/offloads"

    /**
     * 存根串的哨兵前缀——agent 循环查它来跳过已处理过的部件，不重复落盘。
     * 对齐 iOS。
     */
    const val OFFLOADED_PREFIX = "[CONTEXT OFFLOADED]"

    private const val TAG = "ContextOffload"

    /**
     * [sessionId] 工具落盘的宿主持久目录。首写时惰性创建——写前先调
     * [ensureToolsDir]。
     */
    fun toolsDir(context: Context, sessionId: String): File =
        File(context.filesDir, "minis-sessions/$sessionId/offloads/tools")

    private fun ensureToolsDir(context: Context, sessionId: String): File =
        toolsDir(context, sessionId).also { if (!it.exists()) it.mkdirs() }

    /**
     * 取 [toolId] 末 12 字符做盘上文件名的短后缀（局部已足够唯一）。
     * Anthropic 的 id 是 `toolu_01…`（恒定 8 字符前缀），末 12 位仍有区分度。
     * 对齐 iOS `shortToolId(_:)`。
     */
    private fun shortToolId(toolId: String): String =
        if (toolId.length <= 12) toolId else toolId.takeLast(12)

    private fun sanitize(name: String): String =
        name.ifEmpty { "tool" }.replace('/', '_')

    /**
     * 工具文本落盘，返回模型之后能传给 `file_read` 的 Linux 可见路径。任何
     * IO 失败返回空串——调用方仍应把历史部件换存根，别让模型攥着原字节。
     */
    fun offloadContent(
        context: Context,
        sessionId: String,
        content: String,
        toolId: String,
        toolName: String,
        ext: String = "txt",
    ): String {
        val fileName = "${sanitize(toolName)}_${shortToolId(toolId)}.$ext"
        return writeAndMapToLinux(ensureToolsDir(context, sessionId), fileName, tag = "offloadContent") { it.writeText(content) }
    }

    /**
     * 工具图片字节落盘，返回 Linux 可见路径。扩展名按 MIME 推导——认不出
     * 的落 `.bin`，file_read 至少还能解析出一个可预览的东西。
     */
    fun offloadImage(
        context: Context,
        sessionId: String,
        bytes: ByteArray,
        toolId: String,
        mimeType: String,
    ): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val fileName = "image_${shortToolId(toolId)}.$ext"
        return writeAndMapToLinux(ensureToolsDir(context, sessionId), fileName, tag = "offloadImage") { it.writeBytes(bytes) }
    }

    /** 统一的「写文件 → 映射 Linux 路径」骨架；失败告警并回空串。 */
    private inline fun writeAndMapToLinux(
        dir: File,
        fileName: String,
        tag: String,
        write: (File) -> Unit,
    ): String =
        try {
            val target = File(dir, fileName)
            write(target)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "$tag failed: ${e.message}")
            ""
        }

    /**
     * 造替换落盘部件的历史存根。格式与 iOS 逐字一致——任一平台打开会话，
     * 原字节的位子上看到的是同一段 `[CONTEXT OFFLOADED] …` 文本。
     */
    fun stub(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$OFFLOADED_PREFIX Content (~$approxTokens tokens, $byteCount bytes) saved to: $linuxPath\n" +
            "Use file_read tool to retrieve if needed."
}
