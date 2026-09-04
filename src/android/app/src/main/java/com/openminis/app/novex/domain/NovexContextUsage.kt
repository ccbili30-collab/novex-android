package com.openminis.app.novex.domain

enum class ContextSourceKind {
    ANSWER_IDENTITY,
    CONVERSATION_PROMPT,
    ACTIVE_BRANCH_MESSAGE,
    BACKGROUND_MODULE,
    SUMMARY,
    MEMORY,
    PLAYTHROUGH_STATE,
    TOOL_DEFINITION,
    TOOL_RESULT,
}

data class ContextSourceUsage(
    val kind: ContextSourceKind,
    val sourceId: String,
    val label: String,
    val tokenCount: Int,
) {
    init {
        require(sourceId.isNotBlank()) { "上下文来源编号不能为空" }
        require(label.isNotBlank()) { "上下文来源名称不能为空" }
        require(tokenCount >= 0) { "上下文来源词元数不能为负数" }
    }
}

data class ContextSourceOmission(
    val kind: ContextSourceKind,
    val sourceId: String,
    val label: String,
    val reason: String,
) {
    init {
        require(sourceId.isNotBlank()) { "省略来源编号不能为空" }
        require(label.isNotBlank()) { "省略来源名称不能为空" }
        require(reason.isNotBlank()) { "省略原因不能为空" }
    }
}

data class ContextUsageRecord(
    val id: String,
    val requestMessageId: String,
    val responseMessageId: String? = null,
    val branchId: String,
    val answerIdentity: AnswerIdentity,
    val includedSources: List<ContextSourceUsage>,
    val omittedSources: List<ContextSourceOmission> = emptyList(),
    val usedTokens: Int,
    val effectiveWindowTokens: Int,
    val createdAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "上下文引用记录编号不能为空" }
        require(requestMessageId.isNotBlank()) { "请求消息编号不能为空" }
        require(responseMessageId == null || responseMessageId.isNotBlank()) {
            "回复消息编号不能为空"
        }
        require(branchId.isNotBlank()) { "消息分支编号不能为空" }
        require(usedTokens >= 0) { "上下文使用词元数不能为负数" }
        require(effectiveWindowTokens > 0) { "有效上下文窗口必须大于零" }
    }
}

data class NovexContextUsageLedgerSnapshot(
    val conversationId: String,
    val records: List<ContextUsageRecord> = emptyList(),
)

class NovexContextUsageLedger private constructor(
    val snapshot: NovexContextUsageLedgerSnapshot,
) {
    fun record(value: ContextUsageRecord): NovexContextUsageLedger {
        require(snapshot.records.none { it.id == value.id }) { "上下文引用记录已经存在" }
        return open(snapshot.copy(records = snapshot.records + value))
    }

    fun recordsForBranch(branchId: String): List<ContextUsageRecord> {
        require(branchId.isNotBlank()) { "消息分支编号不能为空" }
        return snapshot.records.filter { it.branchId == branchId }
    }

    fun latestByRequestForActivePath(activeMessageIds: Set<String>): Map<String, ContextUsageRecord> =
        snapshot.records.filter { record ->
            record.responseMessageId?.let { it in activeMessageIds }
                ?: (record.branchId in activeMessageIds || record.requestMessageId in activeMessageIds)
        }.associateBy(ContextUsageRecord::requestMessageId)

    companion object {
        fun empty(conversationId: String): NovexContextUsageLedger {
            return open(
                NovexContextUsageLedgerSnapshot(conversationId = conversationId),
            )
        }

        fun open(snapshot: NovexContextUsageLedgerSnapshot): NovexContextUsageLedger {
            require(snapshot.conversationId.isNotBlank()) { "对话编号不能为空" }
            require(snapshot.records.map(ContextUsageRecord::id).distinct().size == snapshot.records.size) {
                "上下文引用记录编号不能重复"
            }
            return NovexContextUsageLedger(snapshot.copy(records = snapshot.records.toList()))
        }
    }
}
