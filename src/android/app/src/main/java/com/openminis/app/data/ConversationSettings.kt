package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

const val MAX_CONVERSATION_PROMPT_CHARS = 48_000
const val MAX_IMAGE_STYLE_PROMPT_CHARS = 8_000
const val MAX_NOVEX_CONFIGURATION_CHARS = 512_000
const val MAX_PER_TURN_PROMPT_CHARS = 8_000

data class ConversationSettingsSnapshot(
    val conversationPrompt: String,
    val imageStylePrompt: String = "",
    val perTurnPrompt: String = "",
    /** Wenyou runtime: append client-rolled d100 values after the latest user turn. */
    val diceInjectionEnabled: Boolean = false,
    /** Wenyou runtime: echo the latest assistant `<账本>` block back as external state. */
    val ledgerInjectionEnabled: Boolean = false,
    /** null inherits the source background; empty explicitly hides it. */
    val backgroundPath: String? = null,
    val rolePresentationEnabled: Boolean = false,
    val assistantDisplayName: String = "",
    val assistantAvatarPath: String? = null,
    val playerDisplayName: String = "",
    val playerAvatarPath: String? = null,
    val novexConfigurationJson: String = "",
)

fun normalizeConversationSettings(value: ConversationSettingsSnapshot): ConversationSettingsSnapshot =
    value.copy(
        conversationPrompt = value.conversationPrompt.take(MAX_CONVERSATION_PROMPT_CHARS),
        imageStylePrompt = value.imageStylePrompt.trim().take(MAX_IMAGE_STYLE_PROMPT_CHARS),
        perTurnPrompt = value.perTurnPrompt.trim().take(MAX_PER_TURN_PROMPT_CHARS),
        backgroundPath = value.backgroundPath?.trim(),
        assistantDisplayName = value.assistantDisplayName.trim().take(80),
        assistantAvatarPath = value.assistantAvatarPath?.trim()?.ifBlank { null },
        playerDisplayName = value.playerDisplayName.trim().take(80),
        playerAvatarPath = value.playerAvatarPath?.trim()?.ifBlank { null },
        // Structured snapshots must stay valid JSON. Silently slicing at a character boundary
        // corrupted large games and made the next decode fall back to an empty configuration.
        novexConfigurationJson = value.novexConfigurationJson.trim(),
    )

fun mergeImageStylePrompt(requestPrompt: String, imageStylePrompt: String?): String {
    val request = requestPrompt.trim()
    val style = imageStylePrompt?.trim().orEmpty()
    if (style.isEmpty()) return request
    return buildString {
        append(request)
        append("\n\n<当前对话固定图片风格>\n")
        append(style)
        append("\n</当前对话固定图片风格>")
    }
}

/** Wraps the standing per-turn instruction; null when the feature is off (blank text). */
fun perTurnInjectionContent(perTurnPrompt: String?): String? {
    val text = perTurnPrompt?.trim().orEmpty()
    if (text.isEmpty()) return null
    return "<每轮注入>\n$text\n</每轮注入>"
}

/**
 * Appends the per-turn instruction to the LAST user message of the request copy —
 * post-history placement keeps it close to the newest turn without a separate
 * message (which would break role alternation on strict providers) and without
 * touching the persisted history. Applied once per request; falls back to a new
 * user message when no user turn exists.
 */
fun appendPerTurnInjection(history: List<LLMMessage>, perTurnPrompt: String?): List<LLMMessage> =
    appendInjectionBlocks(history, listOfNotNull(perTurnInjectionContent(perTurnPrompt)))

/** Wenyou runtime guard: the ledger echo is bounded so a runaway block cannot eat the budget. */
const val MAX_LEDGER_INJECTION_CHARS = 4_000
/** How many d100 values the client rolls per request when dice injection is on. */
const val RUNTIME_DICE_COUNT = 2

/** Plain text of a message: body plus any text content parts, in order. */
private fun LLMMessage.plainText(): String = buildString {
    append(content)
    contentParts.forEach { part ->
        if (part is AgentContentPart.Text) {
            if (isNotEmpty()) append('\n')
            append(part.text)
        }
    }
}

/**
 * Extracts the LAST `<账本>…</账本>` block from an assistant reply (the runtime
 * contract asks the model for exactly one per turn; last wins if it repeats).
 * Null when absent or blank. Capped at [MAX_LEDGER_INJECTION_CHARS].
 */
fun extractLedgerContent(text: String): String? {
    val matches = Regex("<账本>([\\s\\S]*?)</账本>").findAll(text).toList()
    if (matches.isEmpty()) return null
    return matches.last().groupValues[1].trim().take(MAX_LEDGER_INJECTION_CHARS).ifEmpty { null }
}

/** Newest ledger carried by any assistant message, scanning history back to front. */
fun latestLedgerFromHistory(history: List<LLMMessage>): String? =
    history.asReversed().firstNotNullOfOrNull { message ->
        if (message.role != LLMMessage.Role.ASSISTANT) null else extractLedgerContent(message.plainText())
    }

/** 1-based count of user turns — the turn number shown to the model. */
fun userTurnCount(history: List<LLMMessage>): Int = history.count { it.role == LLMMessage.Role.USER }

/** `<外部状态>` block: turn counter plus the latest ledger snapshot when one exists. */
fun externalStateInjectionContent(turn: Int, ledger: String?): String = buildString {
    append("<外部状态>\n第 ").append(turn).append(" 轮")
    if (ledger != null) append("\n<账本>\n").append(ledger).append("\n</账本>")
    append("\n</外部状态>")
}

/** `<骰值>` block from externally rolled values; null when none were provided. */
fun diceInjectionContent(rolls: List<Int>): String? {
    if (rolls.isEmpty()) return null
    return "<骰值>" + rolls.joinToString("; ") { "1d100=$it" } + "</骰值>"
}

/**
 * Wenyou runtime injection: appends, after the latest user turn, the external
 * state block (turn count + latest ledger), fresh dice values, and the standing
 * per-turn instruction — in that order, on the request copy only. Pure given
 * [diceRolls]; the caller rolls per request so tool-loop iterations each see
 * exactly one copy. No-op (same list instance) when every part is disabled.
 */
fun appendRuntimeInjections(
    history: List<LLMMessage>,
    perTurnPrompt: String?,
    diceEnabled: Boolean,
    ledgerEnabled: Boolean,
    diceRolls: List<Int>,
): List<LLMMessage> {
    val blocks = buildList {
        if (diceEnabled || ledgerEnabled) {
            add(externalStateInjectionContent(userTurnCount(history), if (ledgerEnabled) latestLedgerFromHistory(history) else null))
        }
        if (diceEnabled) add(diceInjectionContent(diceRolls))
        add(perTurnInjectionContent(perTurnPrompt))
    }
    return appendInjectionBlocks(history, blocks.filterNotNull())
}

private fun appendInjectionBlocks(history: List<LLMMessage>, blocks: List<String>): List<LLMMessage> {
    if (blocks.isEmpty()) return history
    val injection = blocks.joinToString("\n\n")
    val index = history.indexOfLast { it.role == LLMMessage.Role.USER }
    if (index < 0) return history + LLMMessage(role = LLMMessage.Role.USER, content = injection)
    val target = history[index]
    val updated = if (target.contentParts.isEmpty()) target.copy(
        content = buildString {
            append(target.content)
            if (target.content.isNotBlank()) append("\n\n")
            append(injection)
        },
    ) else target.copy(contentParts = target.contentParts + AgentContentPart.Text(injection))
    return history.toMutableList().also { it[index] = updated }
}
