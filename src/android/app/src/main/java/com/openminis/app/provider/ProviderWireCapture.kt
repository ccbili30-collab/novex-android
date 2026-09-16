package com.openminis.app.provider

import java.io.File
import org.json.JSONObject

/**
 * [T-provider-wire-capture] 工具轮请求原文抓取（2026-09-16）。
 *
 * 背景：经中转（如用户自建 Anthropic2）调用 claude 时，模型侧多次反馈
 * 「用户连续发送空消息」，而应用侧逐环核验（拼装/重放/两家序列化/id 一致）
 * 均无问题——嫌疑锁定中转的协议翻译层吞掉 tool_result。本抓取在请求发出前
 * 把**含工具协议标记**的请求体落盘一份 JSONL，用于拿到实锤后决定是否需要
 * 「工具结果改纯文本」兼容模式。
 *
 * 隐私与体积：纯文本对话不落盘（无工具标记即跳过）；单条正文超限时保留
 * 头部 2000 字符 + 尾部（最近的工具轮在尾部）；文件环形上限 4MB，超出丢最旧。
 * 文件位置：filesDir/provider-wire-capture.jsonl，导出时随日志目录人工查看。
 */
object ProviderWireCapture {
    @Volatile
    var captureDir: File? = null

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_BODY_CHARS = 1_000_000
    private const val HEAD_KEEP_CHARS = 2_000
    private val lock = Any()

    fun record(provider: String, body: String) {
        val dir = captureDir ?: return
        val carriesTools = body.contains("tool_result") || body.contains("tool_use") ||
            body.contains("\"tool_calls\"") || body.contains("\"role\":\"tool\"") ||
            body.contains("function_call_output")
        if (!carriesTools) return
        val recorded = if (body.length > MAX_BODY_CHARS) {
            body.take(HEAD_KEEP_CHARS) + "\n…[wire-capture 截断，全长 ${body.length} 字符，保留尾部]…\n" + body.takeLast(MAX_BODY_CHARS - HEAD_KEEP_CHARS)
        } else body
        synchronized(lock) {
            runCatching {
                val file = File(dir, "provider-wire-capture.jsonl")
                if (file.length() > MAX_FILE_BYTES) trimOldest(file)
                file.appendText(
                    JSONObject()
                        .put("at", System.currentTimeMillis())
                        .put("provider", provider)
                        .put("bytes", body.length)
                        .put("body", recorded)
                        .toString() + "\n",
                )
            }
        }
    }

    /** 超限时从头部整行丢弃，保留约一半上限的最新记录。 */
    private fun trimOldest(file: File) {
        val lines = file.readLines()
        if (lines.isEmpty()) return
        var total = 0L
        var dropCount = lines.size
        for (i in lines.indices.reversed()) {
            total += lines[i].length + 1
            if (total > MAX_FILE_BYTES / 2) break
            dropCount = i
        }
        if (dropCount > 0) {
            file.writeText(lines.drop(dropCount).joinToString(separator = "\n", postfix = "\n"))
        }
    }
}
