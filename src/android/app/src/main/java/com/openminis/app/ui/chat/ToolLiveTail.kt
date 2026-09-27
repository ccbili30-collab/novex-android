package com.openminis.app.ui.chat

/**
 * [T-live-tool-tail] dsh/codex 式过程可见性（2026-09-17 用户批）：执行中的
 * 工具行下方挂一行暗色小字"滚动尾巴"，内容取自流式累积的参数/输出尾部——
 * 用户在长静默轮（如 create_card_bulk 生成 6 万字模块树的 5-11 分钟）能
 * 持续看到模型正在写什么，而不是一个静止的折叠行。
 *
 * 纯函数：输入工具名与累积参数串，输出单行尾巴文本（null=不显示）。
 * 实现约束：只扫描尾部窗口（[SCAN_WINDOW] 字符），绝不整串正则——
 * bulk 参数可长达 24 万字符，每次参数增量都重组件，整串扫描会卡帧。
 */
internal object ToolLiveTail {
    private const val SCAN_WINDOW = 2000
    private val nameField = Regex(""""name"\s*:\s*"([^"]{1,80})"""")
    // 流式半截的 "text" 值还没有闭引号——匹配到下一个引号或串尾，二者都算片段。
    private val textField = Regex(""""text"\s*:\s*"([^"]{0,2000})""")
    private val bulkTools = setOf("create_card_bulk", "add_module_bulk")
    private val writingTools = setOf("write_module_text", "write_module_markdown", "replace_text_range")

    fun liveTail(toolName: String, accumulated: String): String? {
        if (accumulated.isBlank()) return null
        return when {
            toolName in bulkTools -> {
                // 建卡宏通道：尾巴窗口里最近出现的模块名 + 累积字数（O(1)）。
                // 不做全局模块计数——那需要整串扫描，见对象注释的卡帧约束。
                val lastName = nameField.findAll(accumulated.takeLast(SCAN_WINDOW))
                    .lastOrNull()?.groupValues?.get(1)
                    ?: return rawTail(accumulated)
                "$lastName · 已生成 ${accumulated.length} 字"
            }
            toolName in writingTools -> {
                val text = textField.findAll(accumulated.takeLast(SCAN_WINDOW))
                    .lastOrNull()?.groupValues?.get(1)
                    ?: return rawTail(accumulated)
                "正文片段 · ${rawTail(text, 60)}"
            }
            else -> rawTail(accumulated)
        }
    }

    /** 原始尾部：截尾 + 压平换行 + 截断省略号。 */
    private fun rawTail(value: String, max: Int = 120): String {
        val flat = value.takeLast(max).replace('\n', ' ').replace('\r', ' ').trim()
        if (flat.isEmpty()) return ""
        return if (flat.length < value.trim().length && value.trim().length > max) "…$flat" else flat
    }
}
