package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import kotlinx.coroutines.yield
import org.json.JSONObject

/** Owns one provider response, including retry rollback. It never executes a tool or writes storage.
 * The host receives immutable display snapshots; completed raw parts remain available for persistence.
 * A new response after tool results gets a new instance, so text cannot merge across response boundaries. */
internal class AssistantStreamTurn(
    private val turn: Int,
    private val toolTitle: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Snapshot(val text: String, val blocks: List<AssistantBlock>)
    private val blocks = mutableListOf<AssistantBlock>()
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private var textBlock: StringBuilder? = null
    private var textIndex = -1
    private var pendingText = false
    private var lastTextMs = 0L
    private var lastFlushedLength = 0
    private var lastFileMs = 0L
    private var lastOtherMs = 0L
    private var sawTool = false
    private var reasoningBlob: String? = null
    private val starts = mutableMapOf<String, Int>()
    private val completes = mutableMapOf<String, Int>()
    private val inFlight = mutableMapOf<String, String>()
    private val calls = mutableListOf<Triple<String, String, JSONObject>>()
    private val signatures = mutableMapOf<String, String>()
    private val inputHistory = mutableMapOf<String, MutableList<String>>()
    val toolCalls: List<Triple<String, String, JSONObject>> get() = calls.toList()
    val toolSignatures: Map<String, String> get() = signatures.toMap()
    val reasoningContent: String? get() = reasoningBlob ?: thinking.toString().takeIf(String::isNotEmpty)
    var usage: LLMUsage? = null; private set
    var finishReason: String? = null; private set
    val contextTokens: Int get() = usage?.let {
        if (it.latestContextTokens > 0) it.latestContextTokens
        else if (it.inputTokens > 0) it.inputTokens + (it.cacheReadInputTokens ?: 0) + (it.cacheCreationInputTokens ?: 0)
        else 0
    } ?: 0

    fun inputTail(id: String): String? = inputHistory[id]?.lastOrNull()
    fun takeInputHistory(id: String): List<String> = inputHistory.remove(id)?.toList().orEmpty()

    fun snapshot(): Snapshot {
        materializeText()
        return Snapshot(text.toString(), blocks.toList())
    }

    /** Returns the failed attempt's display, then clears all per-attempt state together. */
    fun reset(): Snapshot {
        val failed = snapshot()
        blocks.clear(); text.clear(); thinking.clear(); textBlock = null; textIndex = -1
        pendingText = false; lastTextMs = 0; lastFlushedLength = 0; lastFileMs = 0; lastOtherMs = 0
        sawTool = false; reasoningBlob = null; usage = null; finishReason = null
        starts.clear(); completes.clear(); inFlight.clear(); calls.clear(); signatures.clear(); inputHistory.clear()
        return failed
    }

    fun addConnectionNotice(block: AssistantBlock) { blocks += block }

    suspend fun finish(emit: suspend (Snapshot) -> Unit) {
        if (pendingText) flushText(emit)
    }

    suspend fun accept(chunk: LLMStreamChunk, monolithic: Boolean, emit: suspend (Snapshot) -> Unit) {
        when (chunk) {
            is LLMStreamChunk.Text -> {
                finishThinking()
                text.append(chunk.text)
                if (textBlock != null && (monolithic || textIndex == blocks.lastIndex)) {
                    textBlock!!.append(chunk.text)
                } else {
                    materializeText()
                    textBlock = StringBuilder(chunk.text)
                    val block = AssistantBlock("text_${turn}_${blocks.size}", "text", chunk.text,
                        executionText = sawTool)
                    // Chat Completions content is one string, including content arriving after tool_calls.
                    val beforeTool = if (monolithic) blocks.indexOfFirst { it.kind == "tool_use" } else -1
                    if (beforeTool >= 0) { blocks.add(beforeTool, block); textIndex = beforeTool }
                    else { blocks += block; textIndex = blocks.lastIndex }
                }
                pendingText = true
                val length = text.length
                val newline = length < 5_000 && '\n' in chunk.text && length - lastFlushedLength >= 50
                if (clock() - lastTextMs >= textThrottle(length) || newline) flushText(emit)
            }
            is LLMStreamChunk.ThinkingDelta -> {
                materializeText()
                thinking.append(chunk.text)
                val id = "thinking_$turn"
                val index = blocks.indexOfFirst { it.id == id && it.kind == "thinking" }
                if (index < 0) blocks += AssistantBlock(id, "thinking", thinking.toString(), toolTitle = "思考")
                else blocks[index] = blocks[index].copy(content = thinking.toString())
                emit(snapshot())
            }
            is LLMStreamChunk.ToolUseStart -> {
                markExecution()
                finishThinking()
                // Flush the entire preceding text before freezing it at an ordered tool boundary.
                if (pendingText) { flushText(emit); yield() }
                if (!monolithic) { textBlock = null; textIndex = -1 }
                val id = nextId(chunk.id, starts)
                inFlight[chunk.id] = id
                lastFileMs = 0; lastOtherMs = 0
                if (blocks.none { it.id == id }) {
                    blocks += AssistantBlock(id, "tool_use", toolName = chunk.name,
                        toolStatus = ToolBlockStatus.STREAMING, toolTitle = toolTitle(chunk.name), startTimeMs = clock())
                }
                emit(snapshot())
            }
            is LLMStreamChunk.ToolInputDelta -> {
                val id = inFlight[chunk.id] ?: chunk.id
                val history = inputHistory.getOrPut(id) { mutableListOf() }
                history += chunk.accumulated
                if (history.size > 10) history.subList(0, history.size - 10).clear()
                val index = blocks.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val old = blocks[index]
                    val title = partialString("tool_title", chunk.accumulated)?.takeIf(String::isNotEmpty)
                        ?: old.toolTitle.takeIf { it.isNotEmpty() && it != old.toolName } ?: toolTitle(old.toolName)
                    blocks[index] = old.copy(toolArgs = chunk.accumulated, toolTitle = title, content = "")
                    val heavy = old.toolName in setOf("file_write", "file_edit", "novex_write_card", "novex_write_module")
                    val now = clock()
                    if (now - (if (heavy) lastFileMs else lastOtherMs) >= (if (heavy) 1_000L else 200L)) {
                        if (heavy) lastFileMs = now else lastOtherMs = now
                        emit(snapshot())
                    }
                }
            }
            is LLMStreamChunk.ToolCallComplete -> {
                // This also covers a misbehaving provider emitting tools in read-only mode,
                // or completing a call without a start chunk. Permission is checked by the host gate.
                markExecution()
                materializeText()
                val id = nextId(chunk.id, completes)
                calls += Triple(id, chunk.name, chunk.args)
                chunk.thoughtSignature?.let { signatures[id] = it }
                val index = blocks.indexOfFirst { it.id == id }
                val old = blocks.getOrNull(index) ?: AssistantBlock(id, "tool_use", toolName = chunk.name, startTimeMs = clock())
                val complete = old.copy(toolStatus = ToolBlockStatus.PENDING,
                    toolTitle = chunk.args.optString("tool_title").takeIf(String::isNotEmpty) ?: toolTitle(chunk.name),
                    toolArgs = chunk.args.toString(), content = "", thoughtSignature = chunk.thoughtSignature ?: old.thoughtSignature)
                if (index < 0) blocks += complete else blocks[index] = complete
                emit(snapshot())
            }
            is LLMStreamChunk.Usage -> usage = chunk.usage
            is LLMStreamChunk.ReasoningContent -> reasoningBlob = chunk.content
            is LLMStreamChunk.Finished -> finishReason = chunk.stopReason
            is LLMStreamChunk.Started, is LLMStreamChunk.MediaAttachment -> Unit
        }
    }

    private fun materializeText() {
        val buffer = textBlock ?: return
        if (textIndex in blocks.indices && blocks[textIndex].isText)
            blocks[textIndex] = blocks[textIndex].copy(content = buffer.toString())
    }
    private fun markExecution() {
        sawTool = true
        for (i in blocks.indices) if (blocks[i].isText) blocks[i] = blocks[i].copy(executionText = true)
    }
    private fun finishThinking() {
        val index = blocks.indexOfFirst { it.id == "thinking_$turn" && it.kind == "thinking" }
        if (index >= 0) blocks[index] = blocks[index].copy(toolStatus = ToolBlockStatus.SUCCESS)
    }
    private suspend fun flushText(emit: suspend (Snapshot) -> Unit) {
        pendingText = false; lastTextMs = clock(); lastFlushedLength = text.length
        emit(snapshot())
    }
    private fun nextId(raw: String, counts: MutableMap<String, Int>): String {
        val count = (counts[raw] ?: 0) + 1
        counts[raw] = count
        return if (count == 1) raw else "$raw-$count"
    }
    private fun textThrottle(length: Int): Long = when {
        length < 500 -> 150L
        length < 2_000 -> 300L
        length < 32_000 -> 500L
        length < 64_000 -> 1_000L
        length < 128_000 -> 1_500L
        else -> 2_000L
    }
    private fun partialString(key: String, json: String): String? {
        for (prefix in listOf("\"$key\": \"", "\"$key\":\"")) {
            val start = json.indexOf(prefix)
            if (start < 0) continue
            val tail = json.substring(start + prefix.length)
            var end = 0
            while (end < tail.length) {
                if (tail[end] == '\\') { end += 2; continue }
                if (tail[end] == '"') break
                end++
            }
            return tail.take(end).replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
        }
        return null
    }
}
