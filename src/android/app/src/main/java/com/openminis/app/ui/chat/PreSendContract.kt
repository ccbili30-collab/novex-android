package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-presend-contract] PR 0 地震仪：请求出口三不变量。
 * 任务书：docs/tasks/2026-09-16-conversation-core-pr0-seismograph.md
 *
 * 纯函数、无 IO 无副作用；返回首个违反项，由 ChatViewModel 组装出口统一拦截报错。
 * 合同（对任何发送入口成立）：
 * - I1 历史守恒：主线会话无压缩标记时，请求历史条数 ≥ DB 活跃路径实质消息数；
 * - I2 图片守恒：本轮最后一条用户消息带附件图 ⇒ 组装结果至少一个图片块；
 * - I3 工具配对：每个 ToolUse.id 有 ToolResult.id，反之亦然。
 *
 * 计数基线依据（组装链各环节的消息数语义，净眼审查必查项）：
 * - scopedHistory（范围脱敏）/ ConversationToolRetention（工具保留投影）/
 *   applyRequestImageBudget（图片预算）/ ContextOffload（卸载）：只改内容不删消息；
 * - pureChatHistory（工具禁用）：会删"只有结构化部件"的消息 → I1 用 pureChat 之前的
 *   assembledHistory 计数，DB 侧同基线，两侧无需减项；
 * - 压缩 rebuild：唯一合法的整段删除 → 压缩标记在场时豁免 I1 严查；
 * - prependSideSnapshotHistory：只增不减，侧边会话同样满足下界。
 */
internal object PreSendContract {

    data class Violation(val invariant: String, val detail: String)

    fun firstViolation(
        assembled: List<LLMMessage>,
        requestHistory: List<LLMMessage>,
        boundedHistory: List<LLMMessage>,
        expectedFromDb: Int?,
        compactInProgress: Boolean,
    ): Violation? = historyConservation(assembled, expectedFromDb, compactInProgress)
        ?: imageConservation(requestHistory, boundedHistory)
        ?: toolPairing(boundedHistory)

    /**
     * I1 历史守恒。expectedFromDb 为 null（重读失败/工具循环中轮）或 <2（新会话噪音）
     * 或压缩进行中时放行——地震仪不制造新故障。侧边会话不豁免：快照前拼只增不减，
     * 侧边自身 DB 条数仍是合法下界。
     */
    fun historyConservation(assembled: List<LLMMessage>, expectedFromDb: Int?, compactInProgress: Boolean): Violation? {
        val expected = expectedFromDb ?: return null
        if (expected < 2 || compactInProgress) return null
        if (assembled.size >= expected) return null
        return Violation(
            invariant = "I1",
            detail = "请求历史 ${assembled.size} 条 < DB 活跃路径 $expected 条（无压缩标记），" +
                "内存历史与数据库失同步。",
        )
    }

    /**
     * I2 图片守恒。语义沿用 [T-user-image-never-offload] 护栏：最后一条用户消息带
     * 附件图（imageParts 或内联 ImageData）而组装结果零图片块 = 上下文管线丢图。
     * 历史旧图的预算裁剪不在此列——预算只裁旧图，本轮用户图受保护。
     */
    fun imageConservation(requestHistory: List<LLMMessage>, boundedHistory: List<LLMMessage>): Violation? {
        val lastUser = requestHistory.lastOrNull { it.role == LLMMessage.Role.USER } ?: return null
        val hasImage = lastUser.imageParts.isNotEmpty() ||
            lastUser.contentParts.any { it is AgentContentPart.ImageData }
        if (!hasImage) return null
        val carriesImage = boundedHistory.any { m ->
            m.imageParts.isNotEmpty() || m.contentParts.any { it is AgentContentPart.ImageData }
        }
        if (carriesImage) return null
        return Violation(
            invariant = "I2",
            detail = "本轮附件图片在上下文组装时丢失（不应发生）。",
        )
    }

    /** I3 工具配对：双向差集非空即违反；dropOrphanedToolParts 之后这是可达不变的。 */
    fun toolPairing(boundedHistory: List<LLMMessage>): Violation? {
        val uses = mutableSetOf<String>()
        val results = mutableSetOf<String>()
        for (message in boundedHistory) {
            for (part in message.contentParts) when (part) {
                is AgentContentPart.ToolUse -> uses += part.id
                is AgentContentPart.ToolResult -> results += part.id
                else -> {}
            }
        }
        val unpaired = uses - results
        val orphaned = results - uses
        if (unpaired.isEmpty() && orphaned.isEmpty()) return null
        return Violation(
            invariant = "I3",
            detail = buildString {
                if (unpaired.isNotEmpty()) append("工具调用无结果配对: ${unpaired.take(3)}")
                if (orphaned.isNotEmpty()) {
                    if (isNotEmpty()) append("; ")
                    append("工具结果无调用来源: ${orphaned.take(3)}")
                }
            },
        )
    }

    /** 与 effectiveAgentHistory 的空消息过滤同构（blank 且无任何部件才算空）。 */
    fun substantiveCount(history: List<LLMMessage>): Int = history.count { m ->
        m.content.isNotBlank() || m.contentParts.isNotEmpty() || m.imageParts.isNotEmpty() || m.audioParts.isNotEmpty()
    }
}
