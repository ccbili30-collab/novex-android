package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.File
import org.json.JSONObject

/**
 * [T-provider-wire-capture] 出站请求抓取。
 *
 * 第一版（2026-09-16）只抓带工具/图片协议标记的请求——诊断中转吞 tool_result 用；
 * 纯文本请求不落盘。这成了盲区：队列注入 bug 发出的空请求恰好无标记，抓取器
 * 一声不吭，定位被迫绕远路。
 *
 * [T-presend-contract] PR 0 地震仪起改为：**每个出站请求无条件记一行摘要**
 * （时间/协议/URL/体积/消息数/图片数/工具调用数），带标记的请求在同一行附原文
 * （沿用头 2000+尾部截断与 4MB 环形上限）。被发送前合同拒发的请求也留一行
 * note 摘要，导出对话包时证据内联可见。
 *
 * 隐私与体积：纯文本对话只有摘要行，不含正文；文件位置
 * filesDir/provider-wire-capture.jsonl，导出时随日志目录人工查看。
 */
object ProviderWireCapture {
    @Volatile
    var captureDir: File? = null

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_BODY_CHARS = 1_000_000
    private const val HEAD_KEEP_CHARS = 2_000
    private val lock = Any()

    /** 请求数量统计；-1 = 该维度不可得（诚实缺失，不用启发式从正文猜）。 */
    data class RequestStats(
        val messageCount: Int = -1,
        val imageCount: Int = -1,
        val toolUseCount: Int = -1,
    ) {
        companion object {
            fun of(messages: List<LLMMessage>): RequestStats {
                var images = 0
                var uses = 0
                for (message in messages) {
                    images += message.imageParts.size
                    for (part in message.contentParts) when (part) {
                        is AgentContentPart.ImageData -> images++
                        is AgentContentPart.ToolUse -> uses++
                        else -> {}
                    }
                }
                return RequestStats(messages.size, images, uses)
            }
        }
    }

    fun record(
        provider: String,
        body: String,
        url: String = "",
        stats: RequestStats = RequestStats(),
        note: String? = null,
    ) {
        val dir = captureDir ?: return
        val carriesTools = body.contains("tool_result") || body.contains("tool_use") ||
            body.contains("\"tool_calls\"") || body.contains("\"role\":\"tool\"") ||
            body.contains("function_call_output")
        val carriesImages = body.contains("image_url") || body.contains("input_image") ||
            body.contains("\"type\":\"image\"") || body.contains("\"inline_data\"")
        val attachBody = body.isNotEmpty() && (carriesTools || carriesImages)
        val recorded = if (!attachBody) {
            null
        } else if (body.length > MAX_BODY_CHARS) {
            body.take(HEAD_KEEP_CHARS) + "\n…[wire-capture 截断，全长 ${body.length} 字符，保留尾部]…\n" + body.takeLast(MAX_BODY_CHARS - HEAD_KEEP_CHARS)
        } else {
            body
        }
        synchronized(lock) {
            runCatching {
                val file = File(dir, "provider-wire-capture.jsonl")
                if (file.length() > MAX_FILE_BYTES) trimOldest(file)
                val row = JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("kind", if (attachBody) "full" else "summary")
                    .put("provider", provider)
                    .put("url", url)
                    .put("bytes", body.length)
                    .put("messages", stats.messageCount)
                    .put("images", stats.imageCount)
                    .put("toolUses", stats.toolUseCount)
                if (note != null) row.put("note", note)
                if (recorded != null) row.put("body", recorded)
                file.appendText(row.toString() + "\n")
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
