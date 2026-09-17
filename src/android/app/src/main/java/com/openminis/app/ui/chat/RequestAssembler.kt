package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-request-assembler] PR 1 装配线：请求历史的**唯一编排顺序**。
 * 任务书：docs/tasks/2026-09-16-conversation-core-pr1-assembler.md
 *
 * 只拥有顺序与诊断，不拥有步骤实现：各步骤以 `(List) -> List` 函数注入
 * （IO 与 ViewModel 状态留在调用方闭包）。顺序唯一意味着：
 *  - 内存侧（effectiveAgentHistory）与 DB 侧（影子装配）走同一顺序；
 *  - 内联组装路径不复存在，顺序漂移从根上不可能；
 *  - PR 2 状态机切换入口时，装配顺序无需再动。
 *
 * 阶段顺序（A1 不变量）：
 *  scoped(入参) → compactRebuild → blank 过滤 → orphanRepair →
 *  sideSnapshot（段内先过 orphanRepair 再前拼）→ retentionProject →
 *  pureChat → imageBudget → injections
 *
 * 产物：assembled（pureChat 前基线，I1/PreSendContract 用）、request、bounded、
 * injected（真正发出的），各阶段条数诊断。
 */
internal object RequestAssembler {

    /** 步骤全部注入；默认恒等，便于影子装配只跑到需要的阶段。 */
    data class Inputs(
        val scopedHistory: List<LLMMessage>,
        /** 已解析的侧边快照主线（IO 在外完成）；null = 非侧边或无快照。段内孤儿修复由装配线负责。 */
        val sideSnapshotMainline: List<LLMMessage>? = null,
        val compactRebuild: (List<LLMMessage>) -> List<LLMMessage> = { it },
        val orphanRepair: (List<LLMMessage>) -> List<LLMMessage> = { it },
        val retentionProject: (List<LLMMessage>) -> List<LLMMessage> = { it },
        /** 工具禁用时传 pureChatHistory，否则恒等。 */
        val pureChat: (List<LLMMessage>) -> List<LLMMessage> = { it },
        val imageBudget: (List<LLMMessage>) -> List<LLMMessage> = { it },
        val injections: (List<LLMMessage>) -> List<LLMMessage> = { it },
    )

    data class Diagnostics(
        val afterCompact: Int,
        val afterBlank: Int,
        val afterOrphan: Int,
        val snapshotPrepended: Int,
        val assembled: Int,
        val afterPureChat: Int,
        val afterImageBudget: Int,
        val afterInjections: Int,
    )

    data class Result(
        /** pureChat 之前的基线——I1 历史守恒用（两侧同基线，见 PreSendContract 头注）。 */
        val assembled: List<LLMMessage>,
        val request: List<LLMMessage>,
        val bounded: List<LLMMessage>,
        val injected: List<LLMMessage>,
        val diagnostics: Diagnostics,
    )

    fun assemble(inputs: Inputs): Result {
        val afterCompact = inputs.compactRebuild(inputs.scopedHistory)
        val afterBlank = afterCompact.filter(::substantive)
        val afterOrphan = inputs.orphanRepair(afterBlank)
        // 快照段先过同款孤儿修复再前拼（PR0 P2-2 修复上收为结构步骤）：
        // 定格在"tool_use 已入库、result 未入库"崩溃窗口的快照不能让 I3 永久拒发。
        val afterSnapshot = inputs.sideSnapshotMainline
            ?.let { mainline -> inputs.orphanRepair(mainline) + afterOrphan }
            ?: afterOrphan
        val assembled = inputs.retentionProject(afterSnapshot)
        val request = inputs.pureChat(assembled)
        val bounded = inputs.imageBudget(request)
        val injected = inputs.injections(bounded)
        return Result(
            assembled = assembled,
            request = request,
            bounded = bounded,
            injected = injected,
            diagnostics = Diagnostics(
                afterCompact = afterCompact.size,
                afterBlank = afterBlank.size,
                afterOrphan = afterOrphan.size,
                snapshotPrepended = afterSnapshot.size - afterOrphan.size,
                assembled = assembled.size,
                afterPureChat = request.size,
                afterImageBudget = bounded.size,
                afterInjections = injected.size,
            ),
        )
    }

    /** 与 effectiveAgentHistory 的空消息过滤及 PreSendContract.substantiveCount 同构。 */
    fun substantive(message: LLMMessage): Boolean =
        message.content.isNotBlank() || message.contentParts.isNotEmpty() ||
            message.imageParts.isNotEmpty() || message.audioParts.isNotEmpty()

    /**
     * 影子装配对比用结构指纹（A3）：只认有 dbMessageId 的消息——队列注入的桥接
     * spacer 无 dbId（合法内存专属），在飞轮次的消息同理不应产生伪差异；
     * 图片字节不参与（ImagePart 的 ByteArray 是引用相等，逐字节比会产生噪声）。
     */
    fun fingerprint(history: List<LLMMessage>): List<String> = history
        .filter { it.dbMessageId != null }
        .map { message ->
            val parts = message.contentParts.joinToString(",") { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> "U:${part.id}"
                    is AgentContentPart.ToolResult -> "R:${part.id}:${part.content.length}:${part.isError}"
                    is AgentContentPart.Text -> "T:${part.text.length}"
                    is AgentContentPart.ImageData -> "I:${part.data.size}"
                }
            }
            "${message.role}|${message.dbMessageId}|${message.content.length}|${message.content.hashCode()}|" +
                "${message.imageParts.size}|${message.audioParts.size}|[$parts]"
        }
}
