package com.openminis.app.data.model

/** Parse the status label, never arbitrary numbers inside the server's explanation. */
object ProviderFailure {
    fun httpStatus(detail: String): Int? = Regex("(?i)\\bHTTP(?:/[^ ]+)?\\s*[:=]?\\s+([1-5][0-9]{2})\\b")
        .find(detail)?.groupValues?.get(1)?.toIntOrNull() ?: Regex("^\\[([1-5][0-9]{2})\\]").find(detail)?.groupValues?.get(1)?.toIntOrNull()
    fun isContextLimit(detail: String): Boolean = listOf("maximum context length", "context_length_exceeded", "context window", "prompt is too long", "input is too long")
        .any { detail.contains(it, ignoreCase = true) }
    fun rejectedInputTokens(detail: String): Int? {
        if (!isContextLimit(detail)) return null
        val messageCount = Regex("(?i)([0-9,]+)\\s+in the messages").find(detail)?.groupValues?.get(1)
        return messageCount?.replace(",", "")?.toIntOrNull()
    }
    fun userMessage(detail: String): String = if (isContextLimit(detail))
        "本次请求超过模型上下文容量，已停止重试。请减少本轮携带资料或压缩历史后重试。\n$detail" else detail
}
