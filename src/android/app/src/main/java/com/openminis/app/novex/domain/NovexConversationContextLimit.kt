package com.openminis.app.novex.domain

/** A conversation overrides its group's default, never the model's physical window. */
object NovexConversationContextLimit {
    // [T-default-capacity-300k] 2026-09-16 用户决策：64K 默认太小，一张图就占 37K，
    // 用户体验受限。默认放宽到 300K；小窗模型仍被 minimum(modelWindow) 钳制不受影响，
    // 旧会话持久化的 64K 经 selection() 上调到 min(300K, modelWindow)——符合"默认
    // 就该这么大"的意图，非用户显式定制。
    const val MINIMUM = 300_000

    fun minimum(modelWindow: Int): Int = minOf(MINIMUM, modelWindow.coerceAtLeast(1))

    fun effective(modelWindow: Int?, conversationLimit: Int?, groupLimit: Int?): Int? {
        val window = modelWindow?.takeIf { it > 0 } ?: return null
        val limit = conversationLimit?.takeIf { it > 0 } ?: groupLimit?.takeIf { it > 0 }
        return minOf(window, limit ?: window)
    }

    fun selection(requested: Int, modelWindow: Int): Int = requested.coerceIn(minimum(modelWindow), modelWindow)
}
