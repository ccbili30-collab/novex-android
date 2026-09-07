package com.openminis.app.novex.domain

data class NovexLearningModelLimits(val contextTokens: Int?, val maxOutputTokens: Int = 4096) {
    init {
        require(contextTokens == null || contextTokens > 0) { "模型上下文上限必须大于零" }
        require(maxOutputTokens > 0) { "模型输出上限必须大于零" }
    }
}

/** Shared reservations, not provider-reported billing. */
object NovexLearningBudgetPolicy {
    // A conservative byte-based reservation, not a tokenizer or a bill. Count the
    // exact two messages sent by the adapter, plus envelope/provider headroom.
    fun inputReservation(prompt: NovexLearningPrompt): Int =
        (prompt.system.toByteArray(Charsets.UTF_8).size.toLong() +
            prompt.user.toByteArray(Charsets.UTF_8).size + 512L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun outputReservation(inputTokens: Int, limits: NovexLearningModelLimits): Int =
        minOf(4096, limits.maxOutputTokens, (window(limits) - inputTokens).coerceAtLeast(0))

    fun fits(prompt: NovexLearningPrompt, limits: NovexLearningModelLimits): Boolean =
        inputReservation(prompt).toLong() + minOf(4096, limits.maxOutputTokens,
            if (window(limits) >= 16_384) 4096 else maxOf(512, window(limits) / 8)) <= window(limits)

    private fun window(limits: NovexLearningModelLimits): Int = requireNotNull(limits.contextTokens) {
        "模型上下文上限未知，不能安全开始批量学习；请先设置模型窗口"
    }
}
