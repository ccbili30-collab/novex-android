package com.openminis.app.novex.domain

/** Conversation policy; neither persona nor library membership changes execution permission. */
enum class NovexExecutionMode(val wireName: String, val label: String, val description: String) {
    READ_ONLY("read_only", "只读", "只交流和阅读已提供的资料，不调用工具"),
    APPROVAL("approval", "逐项批准", "每次执行工具前，先由你确认具体操作"),
    FREE("free", "自由执行", "直接使用本对话可用的工具"),
    ;

    val exposesTools: Boolean get() = this != READ_ONLY

    companion object {
        // Existing edit mounts never imply consent to unrestricted execution.
        val DEFAULT = APPROVAL
        fun decode(value: String?): NovexExecutionMode = if (value == null) DEFAULT
            else entries.singleOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("无法识别对话执行权限，原配置需要保留")
    }
}
