package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-request-assembler] PR 1 装配线：请求历史的**唯一编排顺序**。
 * 任务书：docs/tasks/2026-09-16-conversation-core-pr1-assembler.md
 *
 * 只拥有顺序与诊断，不拥有步骤实现：各步骤以 `(List) -> List` 函数注入
 * （IO 与 ViewModel 状态留在调用方闭包）。九段顺序只在此定义一次：
 *
 *  scoped(入参) → compactRebuild → blank 过滤 → orphanRepair →
 *  sideSnapshot（段内先过 snapshotOrphanRepair 再前拼）→ retentionProject →
 *  pureChat → imageBudget → injections
 *
 * 产物：assembled（pureChat 前基线，I1/PreSendContract 用）、request、bounded、
 * injected（真正发出的），各阶段条数诊断。
 */
internal object RequestAssembler {

    /**
     * 只存在于内存、不持久化的 Text 部件前缀（净眼 P1-1：影子指纹噪音源）。
     * 与 ChatViewModel.TOOL_RESULT_HINT / TOOL_TURN_BUDGET_NOTE、附件图路径
     * 注释保持同步——改动文案时两处都要动。
     */
    internal val MEMORY_ONLY_TEXT_PREFIXES = listOf(
        "(以下是本轮工具的执行结果",
        "(系统提示：本轮工具调用轮数已达到上限",
        "[attached image: ",
    )

    /** 步骤全部注入；默认恒等，便于影子装配只跑到需要的阶段。 */
    data class Inputs(
        val scopedHistory: List<LLMMessage>,
        /** 已解析的侧边快照主线（IO 在外完成）；null = 非侧边或无快照。 */
        val sideSnapshotMainline: List<LLMMessage>? = null,
        val compactRebuild: (List<LLMMessage>) -> List<LLMMessage> = { it },
        val orphanRepair: (List<LLMMessage>) -> List<LLMMessage> = { it },
        /**
         * 快照段专用孤儿修复：快照是冻结态，不存在"在飞"轮次，必须绕过
         * dropOrphanedToolParts 的段尾豁免（净眼 P1-2）——否则定格在崩溃窗口
         * 的快照尾 use 恰好被豁免放行，I3 拒发且无自愈。null 时退回 orphanRepair。
         */
        val snapshotOrphanRepair: ((List<LLMMessage>) -> List<LLMMessage>)? = null,
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
        val snapshotRepair = inputs.snapshotOrphanRepair ?: inputs.orphanRepair
        val afterSnapshot = inputs.sideSnapshotMainline
            ?.let { mainline -> snapshotRepair(mainline) + afterOrphan }
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
     * 影子装配的**强信号**指纹（净眼 P1-1 分流后）：角色/DB id/工具对 id/部件类
     * 计数——不含任何文本长度或哈希；内存专属 Text 部件（提示语/图路径注释）剔除。
     * 结构分歧 = 双真相源真的丢了/多了消息或工具对，才是要盯的信号。
     */
    fun structural(history: List<LLMMessage>): List<String> = history
        .filter { it.dbMessageId != null }
        .map { message ->
            val parts = message.contentParts
                .filterNot { it is AgentContentPart.Text && isMemoryOnlyText(it.text) }
                .joinToString(",") { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> "U:${part.id}"
                        is AgentContentPart.ToolResult -> "R:${part.id}"
                        is AgentContentPart.Text -> "T"
                        is AgentContentPart.ImageData -> "I"
                    }
                }
            "${message.role}|${message.dbMessageId}|${message.imageParts.size}|${message.audioParts.size}|[$parts]"
        }

    /**
     * 全量指纹（弱信号）：在 structural 之上叠加文本长度与哈希。两侧 structural
     * 相同而 fingerprint 不同 = 已知投影噪音类（卸载改写、提示语版本差异等），
     * 只计数不定位。桥接消息（无 dbMessageId）不参与。
     */
    fun fingerprint(history: List<LLMMessage>): List<String> = history
        .filter { it.dbMessageId != null }
        .map { message ->
            val parts = message.contentParts
                .filterNot { it is AgentContentPart.Text && isMemoryOnlyText(it.text) }
                .joinToString(",") { part ->
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

    private fun isMemoryOnlyText(text: String): Boolean =
        MEMORY_ONLY_TEXT_PREFIXES.any { text.startsWith(it) }
}
