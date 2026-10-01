package com.openminis.app.ui.chat

// ChatViewModel 的小型 UI 状态开关：工具详情面板、记忆面板、附件列表。
// 字段本体在 ChatViewModel 里（internal 供同包访问）。

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value += attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filterNot { it.id == id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
