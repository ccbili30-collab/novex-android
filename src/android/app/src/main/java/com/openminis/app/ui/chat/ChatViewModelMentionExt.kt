package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.FileMentionIndex

// @-mention 面板的 ViewModel 扩展：锚点探测、开合、键盘导航、回填。
// 兼容半角 '@' 与全角 '＠'（CJK 输入法），与斜杠命令收 '／' 同理。

/** 光标若落在 `@<token>` 内返回 `@` 的下标，否则 -1。`@` 前必须是行首或空白。 */
private fun findMentionAnchor(text: String, caret: Int): Int {
    var i = caret.coerceIn(0, text.length)
    while (i > 0) {
        val at = i - 1
        when {
            text[at].isWhitespace() -> return -1
            text[at] == '@' || text[at] == '＠' ->
                // 邮箱 foo@bar.com 不触发：@ 前要有空白或位于行首。
                return if (at == 0 || text[at - 1].isWhitespace()) at else -1
        }
        i = at
    }
    return -1
}

/**
 * 输入/光标变化时刷新 mention 面板状态：光标落在 `@<token>` 内则开面板
 * 并更新过滤词，否则关面板。斜杠面板优先级更高（两者互斥）。
 */
internal fun ChatViewModel.updateMentionMenuState(text: String, caret: Int) {
    if (_showSlashMenu.value) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val anchor = findMentionAnchor(text, caret)
    if (anchor < 0) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }

    val filter = text.substring(anchor + 1, caret.coerceIn(0, text.length))
    _mentionAnchor.value = anchor
    _mentionFilter.value = filter
    if (_showMentionMenu.value) {
        // 过滤词变化时把高亮钳回界内；列表实际收窄后由组合侧复位。
        if (_mentionSelectedIndex.value < 0) _mentionSelectedIndex.value = 0
        return
    }
    _showMentionMenu.value = true
    // 预选首行，硬件键盘回车可直接提交首个命中。
    _mentionSelectedIndex.value = 0
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isNotEmpty()) fileMentionIndex.refreshIfNeeded(sid)
    Log.i(ChatViewModel.TAG, "mention menu open anchor=$anchor filter=\"$filter\"")
}

internal fun ChatViewModel.dismissMentionMenu() {
    if (!_showMentionMenu.value) return
    _showMentionMenu.value = false
    _mentionFilter.value = ""
    _mentionAnchor.value = -1
    _mentionSelectedIndex.value = -1
}

/** 键盘上下导航（到头回绕）。 */
internal fun ChatViewModel.mentionMenuUp() = mentionMenuMove(-1)

internal fun ChatViewModel.mentionMenuDown() = mentionMenuMove(+1)

private fun ChatViewModel.mentionMenuMove(delta: Int) {
    val count = mentionEntries.value.size
    if (count <= 0) return
    _mentionSelectedIndex.value = Math.floorMod(_mentionSelectedIndex.value + delta, count)
}

/**
 * 把高亮项（或首个命中）回填进 [currentText]，返回新 (文本, 光标)。
 * 面板无命中时返 null，调用方回落到自己的回车处理。
 */
internal fun ChatViewModel.executeSelectedMention(
    currentText: String,
    currentCaret: Int,
): Pair<String, Int>? {
    val entries = mentionEntries.value
    if (entries.isEmpty()) return null
    val index = _mentionSelectedIndex.value.takeIf { it in entries.indices } ?: 0
    return selectMention(entries[index], currentText, currentCaret)
}

/**
 * 把 `@<token>` 换成 `@<linuxPath> ` 并返回新 (文本, 光标)，光标落在
 * 插入的空格之后。面板未开时是 no-op，原样返回。
 */
internal fun ChatViewModel.selectMention(
    entry: FileMentionIndex.Entry,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val anchor = _mentionAnchor.value
    if (anchor < 0 || anchor > currentText.length) {
        dismissMentionMenu()
        return currentText to currentCaret
    }
    // token 终点：@ 之后到下一个空白或文本末。
    val tokenEnd = generateSequence(anchor + 1) { it + 1 }
        .firstOrNull { it >= currentText.length || currentText[it].isWhitespace() }
        ?: currentText.length
    val replacement = "@${entry.linuxPath} "
    val newText = currentText.replaceRange(anchor, tokenEnd, replacement)
    // 先关面板再让文本回流，避免 updateMentionMenuState 对插入的路径重新开面板。
    dismissMentionMenu()
    return newText to anchor + replacement.length
}
