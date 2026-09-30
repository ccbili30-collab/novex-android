package com.openminis.app.ui.chat

// [T-android-split-chat] Slash-menu STATE methods (filter / open / dismiss /
// menu-state) extracted from ChatViewModel as extension functions. The action
// dispatcher executeSlashCommand stays in the class (entangled with compact/
// thinking/memory). 7 slash-state fields flipped private->internal. Verbatim.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import novex.android.data.chat.MessageRow
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import novex.android.data.chat.CompactMarkerRow
import novex.android.data.model.AgentContentPart
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMModel
import novex.android.data.model.LLMStreamChunk
import novex.android.data.model.LLMUsage
import novex.android.data.model.ModelGroup
import novex.android.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * [A2c-cards] 指令卡托盘的金卡只剩压缩一张：清空/记忆/思考/存档组/
 * 同步已移除（开关类归设置、会话管理归 ⋯/额度环、存档由引擎按会话
 * 态自注册为银卡、同步是双边时代残留），skills/MCP 同样不出行。
 * typed-"/" 的隐藏执行路径不受影响：tryExecuteInputAsSlashCommand
 * 仍按 availableSlashCommands 派发全部内建指令。
 */
internal fun ChatViewModel.filteredSlashCommands(): List<SlashCommand> {
    val filter = _slashFilter.value.lowercase()
    val base = availableSlashCommands.map { cmd ->
        when (cmd.id) {
            "compact" -> cmd.copy(
                subtitle = context.getString(R.string.slash_compact_subtitle),
            )
            "sync" -> cmd.copy(
                subtitle = "压缩记忆发给另一边（沟通简报并入两边历史）",
            )
            "memory" -> cmd.copy(
                subtitle = context.getString(
                    if (_memoryEnabled.value) R.string.slash_memory_writes_on
                    else R.string.slash_memory_writes_off,
                ),
            )
            "thinking" -> cmd.copy(
                subtitle = if (!currentModelSupportsReasoning) {
                    context.getString(R.string.slash_thinking_unsupported)
                } else {
                    context.getString(
                        R.string.slash_thinking_subtitle,
                        _thinkingLevel.value.localizedName(context),
                    )
                },
            )
            "clear" -> cmd.copy(
                subtitle = context.getString(R.string.slash_clear_subtitle),
            )
            else -> cmd
        }
    }
    val sid = activeSessionId
    val skillRows: List<SlashCommand> = skillRepository?.skills?.value
        ?.filter { skillRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { skill ->
            val trimmed = skill.description.trim()
            val sub = if (trimmed.isNotEmpty()) trimmed else "Skill · v${skill.version}"
            SlashCommand(
                id = "skill:${skill.id}",
                icon = novex.android.ui.NovexIcons.Extension,
                title = skill.name,
                subtitle = sub,
                isSkill = true,
            )
        } ?: emptyList()
    // [P3.3 裁军] MCP 服务器行（mcpRows）随 MCP 集成面退役删除；斜杠
    // 菜单只剩基础命令与技能行。
    val all = base + skillRows
    return if (filter.isEmpty()) all else all.filter { it.title.lowercase().contains(filter) }
}

/**
 * Update slash-menu state based on composer text. Call from the composer's
 * `onValueChange`. Mirrors iOS `updateSlashMenuState()`.
 */
internal fun ChatViewModel.updateSlashMenuState(text: String) {
    // [T-android-slash-menu-align-ios-prepend] Over-content mode: the
    // composer reads `/<typed-filter> <saved-original>` (see
    // showSlashMenuOverInput). The filter is the token between the leading
    // "/" and the first whitespace — everything after that is the user's
    // preserved original text and must NOT join the filter, or the menu
    // would search the whole `/<filter> <original>` string and never match.
    // Mirrors iOS updateSlashMenuState's over-input branch.
    if (savedInputBeforeSlash != null) {
        val starts = text.startsWith("/") || text.startsWith("／")
        if (!starts) {
            // The leading "/" was deleted — close the menu and leave
            // over-content mode WITHOUT restoring (the user is editing the
            // composer directly now). Drop the saved original so we don't
            // later re-strip a prefix that no longer exists.
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            return
        }
        val afterSlash = text.drop(1)
        val end = afterSlash.indexOfFirst { it.isWhitespace() }.let { if (it < 0) afterSlash.length else it }
        _slashFilter.value = afterSlash.substring(0, end)
        _showSlashMenu.value = true
        _slashMenuSelectedIndex.value = -1
        return
    }
    // Typed-"/" path (no saved original): the whole input is the slash
    // query. Accept full-width "／" (U+FF0F) too — see tryExecuteInputAsSlashCommand.
    val starts = text.startsWith("/") || text.startsWith("／")
    if (starts && !text.contains("\n") && text.length < 30) {
        _showSlashMenu.value = true
        _slashFilter.value = text.drop(1)
        _slashMenuSelectedIndex.value = -1
    } else {
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
    }
}

/**
 * [A2c-cards] 叠卡按钮开卡盘：直接弹窗，不往输入框注入 "/"——卡盘对用户是
 * 「指令卡托盘」而非斜杠过滤框；键入 "/" 的过滤路径不受影响
 * （updateSlashMenuState 仍在每次输入时驱动同一弹窗）。dismissSlashMenu
 * 在 savedInputBeforeSlash=null 且文本非 "/" 开头时会原样返回输入，安全。
 */
internal fun ChatViewModel.openInstructionCards() {
    savedInputBeforeSlash = null
    _slashFilter.value = ""
    _slashMenuSelectedIndex.value = -1
    _showSlashMenu.value = true
}

/**
 * "/" button tapped. If composer is empty, set input to "/"; otherwise save
 * and show the menu directly without clobbering existing input.
 * Returns the (possibly updated) input text.
 */
internal fun ChatViewModel.showSlashMenuOverInput(currentInput: String): String {
    val trimmed = currentInput.trim()
    // [T-android-slash-menu-align-ios-prepend] Boundary: the composer text
    // already starts with "/" (the user hand-typed a slash command). Don't
    // prepend another "/ " (which would make "/ /foo") — treat it exactly
    // like the typed-"/" path: open the menu over the existing text and let
    // updateSlashMenuState derive the filter from it. No saved original.
    if (currentInput.startsWith("/") || currentInput.startsWith("／")) {
        savedInputBeforeSlash = null
        updateSlashMenuState(currentInput)
        return currentInput
    }
    return if (trimmed.isEmpty()) {
        // Empty composer: behave like typing "/" — the input becomes the
        // slash query itself, so we're NOT "over content" (no saved text).
        savedInputBeforeSlash = null
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        "/"
    } else {
        // [T-android-slash-menu-align-ios-prepend] iOS parity: PREPEND "/ "
        // so the composer reads `/ <original>`, save the original for
        // dismiss/skill-prepend, and land the caret right after the slash
        // (index 1) so the user types into the menu's filter slot —
        // identical to having typed "/" at the start themselves.
        savedInputBeforeSlash = currentInput
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        _pendingCaret.value = 1
        "/ $currentInput"
    }
}

/**
 * Dismiss the slash menu. Returns the text the composer should restore to —
 * either the previously-saved text (if opened via button over content) or
 * an empty string (if opened by typing "/").
 */
internal fun ChatViewModel.dismissSlashMenu(currentInput: String): String {
    // [T-android-slash-menu-align-ios-prepend] iOS parity:
    // - Over-content (saved != null): restore the saved ORIGINAL, stripping
    //   the "/ " prefix showSlashMenuOverInput injected. We restore `saved`,
    //   NOT the live `currentInput` (which is "/ <original>" possibly with a
    //   filter token), so the prefix never lingers and the body is intact.
    // - Typed-"/" (saved == null): the input was the slash query → clear it.
    val saved = savedInputBeforeSlash
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return if (currentInput.startsWith("/") || currentInput.startsWith("／")) "" else currentInput
}

/**
 * [T-android-slash-send-keeps-text] End the slash session because the composer
 * was SENT.
 *
 * Unlike [dismissSlashMenu] this restores nothing and returns nothing: the body
 * text has just been sent as a message, so the stashed original must be dropped
 * rather than written back into the (now cleared) composer. Without this, the
 * "/" button's over-content mode survived the send and the just-sent text
 * reappeared in the input — reported on Android only.
 *
 * Safe to call unconditionally: when no slash session is open every assignment
 * below is already its own value.
 */
internal fun ChatViewModel.endSlashSessionForSend() {
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    _slashFilter.value = ""
}

internal fun ChatViewModel.slashMenuSetSelectedIndex(index: Int) {
    _slashMenuSelectedIndex.value = index
}
