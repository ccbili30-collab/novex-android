package com.openminis.app.ui.chat

import com.openminis.app.R
import novex.android.ui.NovexIcons

// 斜杠面板的 ViewModel 扩展：候选列表、开合状态、键盘选中位。
// 指令执行器 executeSlashCommand 留在 ChatViewModel 主类里。

private const val SLASH_ASCII = '/'
private const val SLASH_FULLWIDTH = '／'

private fun hasSlashPrefix(text: String): Boolean =
    text.startsWith(SLASH_ASCII) || text.startsWith(SLASH_FULLWIDTH)

/**
 * 斜杠托盘候选 = 内建指令（副标题按当前状态现算）+ 本会话启用的技能行。
 * MCP 行已随集成面退役。
 */
internal fun ChatViewModel.filteredSlashCommands(): List<SlashCommand> {
    val builtin = availableSlashCommands.map(::liveSlashSubtitle)
    val sid = activeSessionId
    val skillRows = skillRepository?.let { repo ->
        repo.skills.value
            .filter { repo.isEnabledForSession(it.id, sid) }
            .sortedBy { it.name.lowercase() }
            .map { skill ->
                SlashCommand(
                    id = "skill:${skill.id}",
                    icon = NovexIcons.Extension,
                    title = skill.name,
                    subtitle = skill.description.trim().ifEmpty { "Skill · v${skill.version}" },
                    isSkill = true,
                )
            }
    }.orEmpty()
    val all = builtin + skillRows
    val filter = _slashFilter.value.lowercase()
    return if (filter.isEmpty()) all else all.filter { filter in it.title.lowercase() }
}

/** 内建指令的副标题随状态刷新（记忆开关、思考档位、压缩/清空固定文案）。 */
private fun ChatViewModel.liveSlashSubtitle(cmd: SlashCommand): SlashCommand = with(cmd) { when (id) {
    "compact" -> copy(subtitle = context.getString(R.string.slash_compact_subtitle))
    "sync" -> copy(subtitle = "压缩记忆发给另一边（沟通简报并入两边历史）")
    "memory" -> copy(
        subtitle = context.getString(
            if (_memoryEnabled.value) R.string.slash_memory_writes_on
            else R.string.slash_memory_writes_off,
        ),
    )
    "thinking" -> copy(
        subtitle = if (!currentModelSupportsReasoning) {
            context.getString(R.string.slash_thinking_unsupported)
        } else {
            context.getString(R.string.slash_thinking_subtitle, _thinkingLevel.value.localizedName(context))
        },
    )
    "clear" -> copy(subtitle = context.getString(R.string.slash_clear_subtitle))
    else -> this
} }

/**
 * 随输入更新斜杠面板状态。
 *
 * 两种进入方式：
 * - 叠在原文上（savedInputBeforeSlash != null）：输入是 `/<过滤词> <原文>`，
 *   过滤词只取 "/" 到第一个空白之间，后面的原文不参与过滤。
 * - 手敲 "/"：整段输入就是查询；换行或过长视为放弃。
 */
internal fun ChatViewModel.updateSlashMenuState(text: String) {
    if (savedInputBeforeSlash != null) {
        if (!hasSlashPrefix(text)) {
            // 开头的 "/" 被删了：退出叠层模式但不还原——用户正在直接编辑。
            savedInputBeforeSlash = null
            closeSlashMenu()
            return
        }
        val body = text.drop(1)
        _slashFilter.value = body.takeWhile { !it.isWhitespace() }
        _showSlashMenu.value = true
        _slashMenuSelectedIndex.value = -1
        return
    }
    if (hasSlashPrefix(text) && '\n' !in text && text.length < 30) {
        _showSlashMenu.value = true
        _slashFilter.value = text.drop(1)
        _slashMenuSelectedIndex.value = -1
    } else {
        closeSlashMenu()
    }
}

private fun ChatViewModel.closeSlashMenu() {
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
}

/**
 * 叠卡按钮直接开卡盘：不往输入框塞 "/"，面板对用户是「指令卡托盘」。
 * dismissSlashMenu 在 savedInputBeforeSlash=null 且文本非 "/" 开头时原样返回，安全。
 */
internal fun ChatViewModel.openInstructionCards() {
    savedInputBeforeSlash = null
    _slashFilter.value = ""
    _slashMenuSelectedIndex.value = -1
    _showSlashMenu.value = true
}

/**
 * "/" 按钮按下：空输入 → 输入框变成 "/"（等价于手敲，无原文可还原）；
 * 已有内容 → 存原文、前置 "/ "、光标落在斜杠后，返回新输入文本。
 * 输入已以 "/" 开头则视同手敲，不再前置。
 */
internal fun ChatViewModel.showSlashMenuOverInput(currentInput: String): String {
    if (hasSlashPrefix(currentInput)) {
        savedInputBeforeSlash = null
        updateSlashMenuState(currentInput)
        return currentInput
    }
    if (currentInput.isBlank()) {
        savedInputBeforeSlash = null
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        _showSlashMenu.value = true
        return "/"
    }
    savedInputBeforeSlash = currentInput
    _slashFilter.value = ""
    _slashMenuSelectedIndex.value = -1
    _showSlashMenu.value = true
    _pendingCaret.value = 1
    return "/ $currentInput"
}

/**
 * 关斜杠面板，返回输入框该还原成的文本：
 * - 叠层模式（有原文）→ 还原原文，剥掉注入的 "/ " 前缀；
 * - 手敲 "/" 模式 → 输入本身就是查询，清空。
 */
internal fun ChatViewModel.dismissSlashMenu(currentInput: String): String {
    val saved = savedInputBeforeSlash
    savedInputBeforeSlash = null
    closeSlashMenu()
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return if (hasSlashPrefix(currentInput)) "" else currentInput
}

/**
 * 因「发送」结束斜杠会话：正文刚发走，暂存原文要丢弃而不是写回
 * 已清空的输入框（否则刚发送的文本会复活）。无会话时各赋值即自身值，安全。
 */
internal fun ChatViewModel.endSlashSessionForSend() {
    savedInputBeforeSlash = null
    _slashFilter.value = ""
    closeSlashMenu()
}

internal fun ChatViewModel.slashMenuSetSelectedIndex(index: Int) {
    _slashMenuSelectedIndex.value = index
}
