package com.openminis.app.ui.chat

/** User-facing errors must explain the next useful action in Chinese. */
internal fun novexErrorMessage(raw: String): String {
    val text = raw.trim()
    val lower = text.lowercase()
    val summary = when {
        "tool call was interrupted" in lower ||
            ("interrupted" in lower && "tool" in lower) ->
            "工具调用被中断，系统没有收到执行结果。为避免重复操作，请先确认它是否已经生效。"
        "invalid gemini function call history" in lower ||
            ("function call history" in lower && "does not match" in lower) ->
            "模型返回的工具调用记录不一致。本轮已停止以避免误操作，请重试；如果仍然出现，请新建一轮对话继续。"
        "401" in text || "unauthorized" in lower || "invalid api key" in lower ->
            "身份验证失败。请检查 API 密钥是否正确、是否已经失效。"
        "403" in text || "forbidden" in lower ->
            "当前密钥没有访问这个模型或接口的权限，请检查套餐、模型名和中转站权限。"
        "404" in text || "model_not_found" in lower || "not found" in lower ->
            "没有找到请求的模型或接口。请检查接口地址和模型名称。"
        "408" in text || "timeout" in lower || "timed out" in lower ->
            "请求超时。请检查网络后重试，或换用响应更快的模型。"
        "429" in text || "rate limit" in lower || "too many requests" in lower ->
            "请求过于频繁，或当前账户额度不足。请稍后重试并检查账户余额。"
        Regex("\\b(500|502|503|504|529)\\b").containsMatchIn(text) ||
            "service unavailable" in lower || "bad gateway" in lower ->
            "模型服务暂时不可用。这通常是服务端拥堵或中转站异常，请稍后重试。"
        "network" in lower || "connection" in lower || "dns" in lower ||
            "failed to connect" in lower ->
            "网络连接失败。请检查网络、代理和接口地址后重试。"
        "context" in lower && ("length" in lower || "window" in lower || "token" in lower) ->
            "对话内容超过了模型可处理的长度。请压缩上下文，或换用上下文更大的模型。"
        "insufficient" in lower && ("quota" in lower || "balance" in lower) ->
            "账户额度或余额不足，请充值或更换可用的接口。"
        text.isEmpty() -> "请求失败，请稍后重试。"
        else -> "请求没有成功。请重试；如果仍然失败，请检查接口地址、模型名称和网络连接。"
    }
    return appendOriginalHttpDetail(summary, text)
}

private fun appendOriginalHttpDetail(summary: String, raw: String): String {
    if (raw.isBlank()) return summary
    val isHttpFailure = Regex("(?i)\\bHTTP\\s*[45]\\d{2}\\b|\\b[45]\\d{2}\\b")
        .containsMatchIn(raw)
    if (!isHttpFailure) return summary
    val safeDetail = raw
        .replace(
            Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"),
            "\$1<已隐藏>",
        )
        .replace(
            Regex("(?i)(api[-_ ]?key\\s*[:=]\\s*)[^\\s,;]+"),
            "\$1<已隐藏>",
        )
        .take(1_500)
    return "$summary\n\n原始 HTTP 错误：$safeDetail"
}
