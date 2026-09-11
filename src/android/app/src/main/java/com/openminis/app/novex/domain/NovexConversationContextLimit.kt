package com.openminis.app.novex.domain

/** A conversation overrides its group's default, never the model's physical window. */
object NovexConversationContextLimit {
    const val MINIMUM = 64_000

    fun minimum(modelWindow: Int): Int = minOf(MINIMUM, modelWindow.coerceAtLeast(1))

    fun effective(modelWindow: Int?, conversationLimit: Int?, groupLimit: Int?): Int? {
        val window = modelWindow?.takeIf { it > 0 } ?: return null
        val limit = conversationLimit?.takeIf { it > 0 } ?: groupLimit?.takeIf { it > 0 }
        return minOf(window, limit ?: window)
    }

    fun selection(requested: Int, modelWindow: Int): Int = requested.coerceIn(minimum(modelWindow), modelWindow)
}
