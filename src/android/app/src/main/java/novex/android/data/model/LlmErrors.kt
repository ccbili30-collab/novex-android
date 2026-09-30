package novex.android.data.model

/*
 * Error taxonomy for model calls, plus the string forensics that turn a
 * provider's free-form failure text into a decision: which HTTP status it
 * carries, whether it is really a context-limit complaint, and how many
 * tokens the server says the messages weighed.
 *
 * The class names below are referenced by `when` branches across the app
 * (retry policy, fallback policy, UI badges); the message wording is ours
 * and may drift — nothing pattern-matches it.
 */

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Cancelled : LLMError("请求已被取消")

    class TransientError(val detail: String) : LLMError("暂时性错误：$detail")

    class NetworkError(cause: Throwable) : LLMError("网络错误：${cause.message}", cause)

    class DecodingError(cause: Throwable) : LLMError("响应解析失败：${cause.message}", cause)

    class InvalidApiKey(val detail: String = "") : LLMError(
        if (detail.isBlank()) "API 密钥无效" else "API 密钥无效：$detail",
    )

    class RateLimited(val detail: String = "") : LLMError(
        if (detail.isBlank()) "请求过于频繁，请稍后再试" else "请求过于频繁，请稍后再试：$detail",
    )

    class ProviderError(val detail: String) : LLMError(ProviderFailure.userMessage(detail))

    class Unknown(cause: Throwable?) : LLMError("未知错误：${cause?.message}", cause)

    /** The request never reached the server. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Retrying the same model after bounded backoff can still succeed. */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /** Same model will not help — move to the next group member. */
    val isFallbackable: Boolean
        get() = this is RateLimited || this is InvalidApiKey || this is ProviderError

    /** One-line reason surfaced when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is Cancelled -> "已取消"
            is NetworkError -> "网络错误"
            is DecodingError -> "响应解析失败"
            is InvalidApiKey -> "密钥无效"
            is RateLimited -> "请求频繁"
            is TransientError -> "暂时性错误"
            is ProviderError -> "提供方错误"
            is Unknown -> "未知错误"
        }
}

/** Failure-text forensics: statuses and context-limit signals out of prose. */
object ProviderFailure {
    // Status digits are only trusted when the server labels them as a status
    // ("HTTP 429", "HTTP/1.1 503", "[500]"); bare numbers inside an
    // explanation are coincidences, not statuses.
    private val statusShapes = listOf(
        Regex("""(?i)\bHTTP(?:/[^ ]+)?\s*[:=]?\s+([1-5][0-9]{2})\b"""),
        Regex("""^\[([1-5][0-9]{2})]"""),
    )

    private val tokensInMessages = Regex("""(?i)([0-9,]+)\s+in the messages""")

    private val contextLimitMarkers = listOf(
        "maximum context length",
        "context_length_exceeded",
        "context window",
        "prompt is too long",
        "input is too long",
    )

    fun httpStatus(detail: String): Int? = statusShapes.firstNotNullOfOrNull { shape ->
        shape.find(detail)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun isContextLimit(detail: String): Boolean =
        contextLimitMarkers.any(detail::contains)

    /** Token count the server attributes to the messages, when it says so. */
    fun rejectedInputTokens(detail: String): Int? {
        if (!isContextLimit(detail)) return null
        return tokensInMessages.find(detail)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }

    /** Wraps context-limit failures with the stop-retry explanation. */
    fun userMessage(detail: String): String =
        if (isContextLimit(detail)) {
            "本次请求超过模型上下文容量，已停止重试。请减少本轮携带资料或压缩历史后重试。\n$detail"
        } else {
            detail
        }
}
