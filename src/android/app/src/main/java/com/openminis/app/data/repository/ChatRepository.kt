package com.openminis.app.data.repository

import novex.android.data.chat.ChatDao
import novex.android.data.chat.CompactMarkerRow

import novex.android.repo.MessagePreviews
import novex.android.repo.TranscriptPager
import novex.android.data.chat.ContextUsageRow
import novex.android.data.chat.MessageRow
import novex.android.data.chat.SessionFolderRow
import novex.android.data.chat.SessionRow
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * 会话仓库门面 —— sessions / messages / folders 三张表之上的业务层。
 *
 * P3.5a 重写后的职责划分：
 *  - 分页读取与 CursorWindow 兜底在 [TranscriptPager]；
 *  - parts_json 的文本投影在 [MessagePreviews]；
 *  - offload 用的三条大查询在 [ChatArchiveQueries]（扩展函数）；
 *  - SQL 与事务全部留在 novex.android.data.chat 的 DAO。
 *
 * 门面本身只做三件事：把调用方的意图编排成 DAO 调用、维护分支图
 * （ConversationBranchGraph）与持久化路径锚点的一致性、保证写入前的
 * 防御性约束（超长截断、级联删除）。
 */
class ChatRepository(internal val dao: ChatDao) {

    /** 一场会话的完整视图：全量行、活跃路径行、分支图。 */
    data class ActiveConversation(
        val allMessages: List<MessageRow>,
        val activeMessages: List<MessageRow>,
        val graph: com.openminis.app.data.ConversationBranchGraph,
    )

    data class DeletedConversationBranch(
        val conversation: ActiveConversation,
        val deletedMessages: List<MessageRow>,
    )

    private val pager = TranscriptPager(dao)

    // ── 会话行 ─────────────────────────────────────────────────────────

    fun observeSessionIndex(): Flow<List<SessionRow>> = dao.observeSessionIndex()

    suspend fun createSession(
        modelId: String,
        title: String? = null,
        // 建行时落全局记忆默认值（调用方读 MemoryGlobalPrefs 传入）；省略
        // 的旧调用点保持 memoryEnabled=1 的历史默认。
        memoryEnabled: Boolean = true,
        characterId: String? = null,
        characterSnapshotJson: String? = null,
        worldSnapshotJson: String? = null,
        personaId: String? = null,
        personaSnapshotJson: String? = null,
        worldId: String? = null,
        characterVersionId: String? = null,
        chatBackgroundPath: String? = null,
        conversationPrompt: String? = null,
        imageStylePrompt: String? = null,
        rolePresentationEnabled: Boolean = characterSnapshotJson != null,
        assistantDisplayName: String? = null,
        assistantAvatarPath: String? = null,
        playerDisplayName: String? = null,
        playerAvatarPath: String? = null,
        novexConfigurationJson: String? = null,
        sideOfSession: String? = null,
        perTurnPrompt: String? = null,
        textStylePrompt: String? = null,
        runtimeDiceEnabled: Int = 0,
        runtimeLedgerEnabled: Int = 0,
    ): SessionRow {
        val stamp = System.currentTimeMillis()
        val row = SessionRow(
            id = UUID.randomUUID().toString(),
            title = title,
            modelId = modelId,
            createdAt = stamp,
            updatedAt = stamp,
            memoryEnabled = if (memoryEnabled) 1 else 0,
            characterId = characterId,
            characterSnapshotJson = characterSnapshotJson,
            worldSnapshotJson = worldSnapshotJson,
            personaId = personaId,
            personaSnapshotJson = personaSnapshotJson,
            worldId = worldId,
            characterVersionId = characterVersionId,
            chatBackgroundPath = chatBackgroundPath,
            conversationPrompt = conversationPrompt,
            imageStylePrompt = imageStylePrompt,
            rolePresentationEnabled = if (rolePresentationEnabled) 1 else 0,
            assistantDisplayName = assistantDisplayName,
            assistantAvatarPath = assistantAvatarPath,
            playerDisplayName = playerDisplayName,
            playerAvatarPath = playerAvatarPath,
            novexConfigurationJson = novexConfigurationJson,
            perTurnPrompt = perTurnPrompt,
            textStylePrompt = textStylePrompt,
            runtimeDiceEnabled = runtimeDiceEnabled,
            runtimeLedgerEnabled = runtimeLedgerEnabled,
            sideOfSession = sideOfSession,
        )
        dao.upsertSession(row)
        return row
    }

    /** 每条主线的侧边对话上限（用户裁决 2026-09-14）。 */
    val MAX_SIDE_CONVERSATIONS = 10

    /**
     * 开一条侧边对话：继承主线全部呈现设定，标题取「侧边 N」（N = 现有
     * 编号最大值 + 1，新旧命名都计数，删除中间条目后不重号）。记忆默认关
     * 闭 —— 主线记忆由分裂点快照提供，再写全局库是重复污染；用户可自行打开。
     */
    suspend fun createSideSession(parentId: String): SessionRow {
        val parent = requireNotNull(dao.sessionById(parentId)) { "主对话不存在" }
        val sides = dao.sideSessionsOf(parentId)
        require(sides.size < MAX_SIDE_CONVERSATIONS) {
            "侧边对话最多 ${MAX_SIDE_CONVERSATIONS} 条，请先删除旧的"
        }
        return createSession(
            title = nextSideConversationTitle(sides.map { it.title.orEmpty() }),
            modelId = parent.modelId,
            memoryEnabled = false,
            characterId = parent.characterId,
            characterSnapshotJson = parent.characterSnapshotJson,
            worldSnapshotJson = parent.worldSnapshotJson,
            personaId = parent.personaId,
            personaSnapshotJson = parent.personaSnapshotJson,
            worldId = parent.worldId,
            characterVersionId = parent.characterVersionId,
            chatBackgroundPath = parent.chatBackgroundPath,
            conversationPrompt = parent.conversationPrompt,
            imageStylePrompt = parent.imageStylePrompt,
            perTurnPrompt = parent.perTurnPrompt,
            textStylePrompt = parent.textStylePrompt,
            runtimeDiceEnabled = parent.runtimeDiceEnabled,
            runtimeLedgerEnabled = parent.runtimeLedgerEnabled,
            rolePresentationEnabled = parent.rolePresentationEnabled != 0,
            assistantDisplayName = parent.assistantDisplayName,
            assistantAvatarPath = parent.assistantAvatarPath,
            playerDisplayName = parent.playerDisplayName,
            playerAvatarPath = parent.playerAvatarPath,
            novexConfigurationJson = parent.novexConfigurationJson,
            sideOfSession = parentId,
        )
    }

    suspend fun sideSessionsOf(parentId: String): List<SessionRow> = dao.sideSessionsOf(parentId)

    suspend fun sessionById(id: String): SessionRow? = dao.sessionById(id)

    suspend fun saveComposerDraft(id: String, text: String) =
        dao.saveComposerDraft(id, text.takeIf { it.isNotEmpty() })

    /** 会话全部 token_usage JSON 串（每次 LLM 调用一条）。 */
    suspend fun sessionTokenUsages(sessionId: String): List<String> = dao.tokenUsageJsonFor(sessionId)

    suspend fun renameSession(id: String, title: String) {
        dao.renameSession(id, title, System.currentTimeMillis())
    }

    suspend fun renameSessionWithCategory(id: String, title: String, category: String?) {
        dao.renameSessionWithCategory(id, title, category, System.currentTimeMillis())
    }

    suspend fun updateSessionModel(sessionId: String, modelId: String) {
        dao.switchSessionModel(sessionId, modelId)
    }

    suspend fun setChatWallpaper(sessionId: String, path: String?) {
        dao.setChatWallpaper(sessionId, path)
    }

    suspend fun writeConversationSettings(
        sessionId: String,
        settings: com.openminis.app.data.ConversationSettingsSnapshot,
    ) {
        // 归一化后落库：空串 → null，开关 → 0/1，保证读回语义稳定。
        val value = com.openminis.app.data.normalizeConversationSettings(settings)
        dao.writeConversationSettings(
            sessionId = sessionId,
            conversationPrompt = value.conversationPrompt,
            imageStylePrompt = value.imageStylePrompt.ifBlank { null },
            perTurnPrompt = value.perTurnPrompt.ifBlank { null },
            textStylePrompt = value.textStylePrompt.ifBlank { null },
            runtimeDiceEnabled = if (value.diceInjectionEnabled) 1 else 0,
            runtimeLedgerEnabled = if (value.ledgerInjectionEnabled) 1 else 0,
            chatBackgroundPath = value.backgroundPath,
            rolePresentationEnabled = if (value.rolePresentationEnabled) 1 else 0,
            assistantDisplayName = value.assistantDisplayName.ifBlank { null },
            assistantAvatarPath = value.assistantAvatarPath,
            playerDisplayName = value.playerDisplayName.ifBlank { null },
            playerAvatarPath = value.playerAvatarPath,
            novexConfigurationJson = value.novexConfigurationJson.ifBlank { null },
        )
    }

    suspend fun rebindSessionModel(sessionId: String, binding: String, modelId: String) {
        dao.rebindSessionModel(sessionId, binding, modelId)
    }

    /**
     * 删除会话（级联其侧边对话 —— 侧边从属主线，留着只会变成列表里永远
     * 看不到的孤儿）。这是 UI 各入口共用的唯一删除路径。
     */
    suspend fun dropSession(id: String) {
        dao.sideSessionsOf(id).forEach { side -> dao.removeConversation(side.id) }
        dao.removeConversation(id)
    }

    /**
     * 中断会话 id 集：只看各会话消息尾部的形状（user 尾全 tool_result /
     * assistant 尾含未执行 tool_use），让 PAUSED 徽标挺过硬杀进程 ——
     * 生命周期回调里的上报在硬杀时根本没跑。与 ChatViewModel.loadSession
     * 的判据刻意保持同一份。
     */
    suspend fun interruptedSessionIds(): Set<String> {
        val tails = runCatching { dao.sessionTails() }.getOrElse { emptyList() }
        val interrupted = HashSet<String>()
        for (tail in tails) {
            if (interruptedTail(tail.role, tail.partsJson)) interrupted.add(tail.sessionId)
        }
        return interrupted
    }

    private fun interruptedTail(role: String, partsJson: String): Boolean {
        val array = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return false
        val types = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { types.add(it.optString("type")) }
        }
        val onlyText = array.takeIf { it.length() == 1 }?.optJSONObject(0)
            ?.takeIf { it.optString("type") == "text" }?.optString("value")
        return com.openminis.app.ui.chat.isInterruptedAgentTail(role, types, onlyText)
    }

    // ── 分组（UI 叫「分组」，行叫 Folder）──────────────────────────────

    fun observeFolders(): Flow<List<SessionFolderRow>> = dao.observeFolders()

    suspend fun allFolders(): List<SessionFolderRow> = dao.allFolders()

    suspend fun getFolder(id: String): SessionFolderRow? = dao.folderById(id)

    suspend fun createFolder(
        name: String,
        description: String? = null,
        origin: String = SessionFolderRow.MANUAL_ORIGIN,
    ): SessionFolderRow {
        val stamp = System.currentTimeMillis()
        val folder = SessionFolderRow(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            origin = origin,
            description = description?.trim()?.take(SessionFolderRow.DESCRIPTION_MAX_CHARS)?.ifBlank { null },
            createdAt = stamp,
            updatedAt = stamp,
        )
        dao.upsertFolder(folder)
        return folder
    }

    /**
     * 改名 / 改描述。UUID 主键不动，成员永不迁移。description 参数：
     * null = 保留现值，空串 = 清除 —— 直传字段的调用方必须先播种现值。
     */
    suspend fun renameFolder(id: String, name: String, description: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renameFolder(
            folderId = id,
            name = trimmed,
            description = description?.trim()?.take(SessionFolderRow.DESCRIPTION_MAX_CHARS),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 翻转置顶；返回翻转后的状态。同时盖 updated_at 让改动留痕。 */
    suspend fun toggleFolderPin(id: String): Boolean {
        val current = dao.folderById(id) ?: return false
        val pinNow = current.pinnedAt == null
        dao.setFolderPinStamp(id, if (pinNow) System.currentTimeMillis() else null, System.currentTimeMillis())
        return pinNow
    }

    /**
     * 解散分组：成员全部回到未分组，**不删任何会话** —— 这是分组唯一的
     * 删除性操作，永远不可能让用户丢对话。成员 id 在清空前先读出来，供
     * 调用方刷新（也是未来同步层要推送的对象）。
     */
    suspend fun dissolveFolder(id: String): List<String> {
        val memberIds = dao.sessionIdsFiledUnder(id)
        dao.releaseFolderSessions(id)
        dao.dropFolder(id)
        return memberIds
    }

    suspend fun sessionIdsFiledUnder(folderId: String): List<String> = dao.sessionIdsFiledUnder(folderId)

    /** 归档是组织行为，只写 folder_id、不盖 updated_at（不能因此重排会话列表）。 */
    suspend fun setFolderForSessions(folderId: String?, sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        for (sessionId in sessionIds) dao.setSessionFolder(sessionId, folderId)
    }

    /**
     * 仅当仍处于未分组状态时归入。条件写进 UPDATE 本身，自动归档永远
     * 不可能赢过与它并发的手工归档。返回本次是否真的归了档。
     */
    suspend fun setFolderIfUnfiled(folderId: String, sessionId: String): Boolean =
        dao.claimUnfiledSession(sessionId, folderId) > 0

    /** 名字 → 分组（大小写与空白不敏感）。重名容忍：取最近更新的那个。 */
    suspend fun findFolderByName(name: String): SessionFolderRow? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        return dao.allFolders().firstOrNull { it.name.trim().lowercase() == needle }
    }

    suspend fun searchSessions(query: String): List<SessionRow> =
        dao.searchSessions("%$query%")

    // ── 消息读取与分支 ─────────────────────────────────────────────────

    fun observeHistory(sessionId: String): Flow<List<MessageRow>> =
        dao.observeHistory(sessionId)

    suspend fun historyFor(sessionId: String): List<MessageRow> = pager.loadAll(sessionId)

    suspend fun messageCount(sessionId: String): Int = dao.messageCountIn(sessionId)

    /** UI、模型上下文、导出共用的那条「活跃路径」视图。 */
    suspend fun loadActiveConversation(sessionId: String): ActiveConversation {
        val rows = pager.loadAll(sessionId)
        return conversationOver(sessionId, rows)
    }

    private suspend fun conversationOver(sessionId: String, rows: List<MessageRow>): ActiveConversation {
        val anchors = dao.branchAnchorsOf(sessionId)
        val graph = com.openminis.app.data.ConversationBranchGraph.open(
            nodes = rows.map { row ->
                com.openminis.app.data.ConversationBranchGraph.Node(
                    id = row.id,
                    parentId = row.parentMessageId,
                    activeChildId = row.activeChildId,
                    order = row.sortOrder,
                )
            },
            activeRootId = anchors?.activeRootMessageId ?: rows.firstOrNull()?.id,
            activeLeafId = anchors?.activeLeafMessageId ?: rows.lastOrNull()?.id,
        )
        val byId = rows.associateBy(MessageRow::id)
        return ActiveConversation(
            allMessages = rows,
            activeMessages = graph.activePathIds.mapNotNull(byId::get),
            graph = graph,
        )
    }

    suspend fun loadActiveMessages(sessionId: String): List<MessageRow> =
        loadActiveConversation(sessionId).activeMessages

    /** 落在当前所选路径上的最新摘要边界（更旧的 marker 只服务旧路径）。 */
    suspend fun latestActiveCompactMarker(
        sessionId: String,
        activeMessages: List<MessageRow>,
    ): CompactMarkerRow? {
        val activeIds = activeMessages.mapTo(hashSetOf(), MessageRow::id)
        return dao.markersFor(sessionId).asReversed().firstOrNull { marker ->
            val anchor = marker.lastCompactedMessageId?.takeIf(String::isNotEmpty)
                ?: marker.firstKeptMessageId?.takeIf(String::isNotEmpty)
                ?: marker.boundaryMessageId?.takeIf(String::isNotEmpty)
            (anchor == null || anchor in activeIds) && (marker.version < 3 || marker.firstKeptMessageId in activeIds)
        }
    }

    suspend fun forkReplyFrom(sessionId: String, messageId: String): ActiveConversation =
        mutateBranch(sessionId) { it.forkReplyFrom(messageId) }

    suspend fun forkEditedMessageFrom(sessionId: String, messageId: String): ActiveConversation =
        mutateBranch(sessionId) { it.forkEditedMessageFrom(messageId) }

    suspend fun switchMessageSibling(
        sessionId: String,
        messageId: String,
        delta: Int,
    ): ActiveConversation = mutateBranch(sessionId) { it.switchSibling(messageId, delta) }

    suspend fun deleteMessageBranch(
        sessionId: String,
        messageId: String,
    ): DeletedConversationBranch {
        val before = loadActiveConversation(sessionId)
        val plan = before.graph.deleteBranchFrom(messageId)
        val removed = before.allMessages.filter { it.id in plan.deletedMessageIds }
        return DeletedConversationBranch(
            conversation = commitBranchPlan(sessionId, before, plan),
            deletedMessages = removed,
        )
    }

    private suspend fun mutateBranch(
        sessionId: String,
        plan: (com.openminis.app.data.ConversationBranchGraph) -> com.openminis.app.data.ConversationBranchGraph.Mutation,
    ): ActiveConversation {
        val before = loadActiveConversation(sessionId)
        return commitBranchPlan(sessionId, before, plan(before.graph))
    }

    /** 把图算法产出的路径变更一次性落库，再回读新的活跃视图。 */
    private suspend fun commitBranchPlan(
        sessionId: String,
        before: ActiveConversation,
        plan: com.openminis.app.data.ConversationBranchGraph.Mutation,
    ): ActiveConversation {
        val byId = before.allMessages.associateBy(MessageRow::id)
        val activeRows = plan.activePathIds.mapNotNull(byId::get)
        dao.applyBranchMutation(
            sessionId = sessionId,
            rootId = plan.activeRootId,
            leafId = plan.activeLeafId,
            childUpdates = plan.activeChildUpdates,
            deletedMessageIds = plan.deletedMessageIds,
            preview = activeRows.lastOrNull()?.let { MessagePreviews.previewOf(it.partsJson) },
            updatedAt = System.currentTimeMillis(),
        )
        return loadActiveConversation(sessionId)
    }

    /** 按消息 ID 只读拉取（分裂点快照回放：认 ID 不认活跃路径）。 */
    suspend fun findMessageById(messageId: String) = dao.messageById(messageId)

    /**
     * 原始行分页 —— 导出流式读长会话用（区别于 [loadMessagePage] 的投影
     * 裁剪，这里保留完整 parts_json 供序列化）。
     */
    suspend fun loadMessagePageRaw(
        sessionId: String,
        offset: Int,
        limit: Int,
    ): List<MessageRow> = dao.messagePage(sessionId, offset, limit)

    // ── 消息写入 ───────────────────────────────────────────────────────

    /**
     * 追加一条消息。parts_json 超 [MAX_MESSAGE_PARTS_JSON_LENGTH] 时截断并
     * 包成单文本部件（Issue #17：超长 tool_result 落库后读回必然炸
     * CursorWindow）。助手回合走 checkpointRunningTurn —— 首次落行、此后
     * 原位重写，回合在分支图上的槽位保持不变。
     */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        partsJson: String,
        tokenUsage: String? = null,
        reasoningContent: String? = null,
        messageId: String = UUID.randomUUID().toString(),
    ): MessageRow {
        val stamp = System.currentTimeMillis()
        val capped = if (partsJson.length > MAX_MESSAGE_PARTS_JSON_LENGTH) {
            buildTruncatedPartsJson(partsJson)
        } else {
            partsJson
        }
        val row = MessageRow(
            id = messageId,
            sessionId = sessionId,
            role = role,
            partsJson = capped,
            createdAt = stamp,
            tokenUsage = tokenUsage,
            sortOrder = -1, // 由 appendOnActivePath 的事务赋予真实序号。
            reasoningContent = reasoningContent,
        )
        val preview = MessagePreviews.previewOf(capped)
        return if (role == "assistant") {
            dao.checkpointRunningTurn(row, preview, stamp)
        } else {
            dao.appendOnActivePath(row, preview, stamp)
        }
    }

    /** [T-error-persist-android] 按行 id 设置/清除错误贴纸。 */
    suspend fun setMessageSticker(messageId: String, errorInfo: String?) =
        dao.setMessageSticker(messageId, errorInfo)

    /**
     * 在所选路径的最后一条助手行上设置/清除错误贴纸。选路径内的最后一条
     * —— 会话级「最后一条助手消息」不是分支安全的（更晚创建的兄弟行
     * sort_order 更大）。新用户回合若尚无已存回复，不许给上一条回复贴错。
     */
    suspend fun updateLastActiveAssistantError(sessionId: String, errorInfo: String?) {
        val path = loadActiveConversation(sessionId).activeMessages
        val lastAssistant = path.indexOfLast { it.role.equals("assistant", ignoreCase = true) }
        val lastUser = path.indexOfLast { it.role.equals("user", ignoreCase = true) }
        if (lastAssistant > lastUser) dao.setMessageSticker(path[lastAssistant].id, errorInfo)
    }

    /**
     * 流式回合中途刷新会话列表预览（只写 last_message，不落消息行）：
     * 长工具调用期间列表不该停留在陈旧预览或「暂无消息」。提取不到预览
     * 时不动 —— 不能用好端端的现值换一个 null。
     */
    suspend fun updateSessionPreview(sessionId: String, partsJson: String) {
        val preview = MessagePreviews.previewOf(partsJson) ?: return
        dao.storePreview(sessionId, preview, System.currentTimeMillis())
    }

    /** 历史回退（含边界）之后按剩余消息重算列表预览。 */
    suspend fun refreshSessionPreviewFromHistory(
        sessionId: String,
        remainingMessages: List<MessageRow>? = null,
    ) {
        val remaining = remainingMessages ?: loadActiveMessages(sessionId)
        val preview = remaining.lastOrNull()?.let { MessagePreviews.previewOf(it.partsJson) }
        dao.storePreview(sessionId, preview, System.currentTimeMillis())
    }

    /**
     * 把助手行改判为「已正式化」：全部文本部件的 execution 标记清掉。
     * 保持 token_usage / reasoning 原样回写。
     */
    suspend fun markAssistantTextFormal(messageId: String) {
        val row = dao.messageById(messageId) ?: return
        require(row.role == "assistant")
        val parts = org.json.JSONArray(row.partsJson)
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "text") part.put("execution", false)
        }
        dao.overwriteAssistantBody(messageId, parts.toString(), row.tokenUsage, row.reasoningContent)
    }

    // ── Novex 上下文用量账本 ────────────────────────────────────────────

    suspend fun recordNovexContextUsage(sessionId: String, record: novex.core.ContextUsageRecord) {
        dao.recordContextUsage(
            ContextUsageRow(
                id = record.id,
                sessionId = sessionId,
                requestMessageId = record.requestMessageId,
                responseMessageId = record.responseMessageId,
                branchId = record.branchId,
                payloadJson = novex.core.NovexContextUsageCodec.encode(record),
                createdAt = record.createdAt,
            ),
        )
    }

    suspend fun novexContextUsage(sessionId: String): List<novex.core.ContextUsageRecord> =
        dao.contextUsageFor(sessionId).mapNotNull { row ->
            runCatching { novex.core.NovexContextUsageCodec.decode(row.payloadJson) }.getOrNull()
        }

    companion object {
        /**
         * harness 注入的运行时提醒（<system-reminder>…）不代表用户输入，
         * 预览与投影必须剥掉。经 [MessagePreviews] 实现并在此透出
         * （调用方以 ChatRepository.stripSystemReminders 的形式引用）。
         */
        internal fun stripSystemReminders(raw: String): String =
            MessagePreviews.stripSystemReminders(raw)

        /** 单条消息 parts_json 的硬上限（Issue #17 的写入侧闸门）。 */
        internal const val MAX_MESSAGE_PARTS_JSON_LENGTH = 500_000

        /** offload 摘要与单条消息文本上限（对齐 iOS SessionsOffload）。 */
        internal const val SNIPPET_MAX = 600
        internal const val MESSAGE_TEXT_MAX = 600

        /** `--full` 模式的单条消息上限；超限仍截断并标 truncated。 */
        internal const val MESSAGE_TEXT_MAX_FULL = 50_000

        /** 截断并包成单文本部件：下游所有 JSONArray 消费者都能继续解析。 */
        internal fun buildTruncatedPartsJson(original: String): String {
            val head = original.take(MAX_MESSAGE_PARTS_JSON_LENGTH)
            val marker = "\n\n[Content truncated at ${MAX_MESSAGE_PARTS_JSON_LENGTH / 1000} KB" +
                " — original length ${original.length} chars]"
            val textPart = org.json.JSONObject()
                .put("type", "text")
                .put("value", head + marker)
            return org.json.JSONArray().put(textPart).toString()
        }
    }
}

/**
 * 侧边对话标题 → 编号。兼容现命名「侧边 N」与旧命名「主对话标题·侧N」；
 * 无编号返回 null。
 */
internal fun sideConversationNumber(title: String): Int? =
    Regex("·侧(\\d+)\\s*$").find(title)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("侧边\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()

/** 下一个侧边标题 = 现有最大编号 +1；删除中间条目后新建不会重号。 */
internal fun nextSideConversationTitle(existingTitles: List<String>): String =
    "侧边 ${(existingTitles.mapNotNull(::sideConversationNumber).maxOrNull() ?: 0) + 1}"
