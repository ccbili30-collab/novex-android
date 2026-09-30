package novex.android.repo

import com.openminis.app.data.repository.ChatRepository

import androidx.sqlite.db.SimpleSQLiteQuery
import novex.android.data.chat.MessageRow

/**
 * 会话归档查询 —— minis-sessions-cli 的三个高层读（list / search /
 * messages）的仓库侧实现。DAO 负责 RawQuery 执行与列投影；这里组装
 * WHERE、做 parts_json 的文本投影与摘要裁剪。输出形状与 iOS
 * SessionsOffload 桥逐字段对齐（跨端 CLI 输出一致是验收面）。
 */

/** `list` 的一行：会话元信息 + 首条用户消息的短预览。 */
data class SessionMeta(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val startedAt: Long,    // sessions.created_at（毫秒）
    val lastActive: Long,   // sessions.updated_at（毫秒）
    val messageCount: Int,
)

/** `search` 的一条命中。 */
data class MessageSearchMatch(
    val sessionId: String,
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val snippet: String,
)

/** `messages` 的一页中的一条。 */
data class MessagePageItem(
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val text: String,
    /** 文本超过请求上限被截断时为 true（CLI 输出 "truncated": true）。 */
    val truncated: Boolean = false,
)

/**
 * `list`：按 last_active 倒序，支持 id 集合、关键词 AND、updated_at 区间。
 * 关键词命中「标题或任意消息的 parts_json」即可 —— 多关键词允许分散在
 * 不同消息里（聊 python 又聊 flask 的多轮会话必须能被两个词同时命中）。
 */
internal suspend fun ChatRepository.querySessionsMeta(
    sessionIds: List<String>?,
    keywords: List<String>?,
    limit: Int,
    startMs: Long?,
    endMs: Long?,
): List<SessionMeta> {
    val clauses = ArrayList<String>()
    val args = ArrayList<Any>()
    if (!sessionIds.isNullOrEmpty()) {
        clauses += "s.id IN (${sessionIds.joinToString(",") { "?" }})"
        args.addAll(sessionIds)
    }
    startMs?.let { clauses += "s.updated_at >= ?"; args += it }
    endMs?.let { clauses += "s.updated_at <= ?"; args += it }
    if (!keywords.isNullOrEmpty()) {
        for (keyword in keywords) {
            val pattern = "%$keyword%"
            clauses +=
                "(s.title LIKE ? OR EXISTS (SELECT 1 FROM messages m " +
                    "WHERE m.session_id = s.id AND m.parts_json LIKE ?))"
            args += pattern
            args += pattern
        }
    }
    val whereSql = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
    args += limit
    val sql = """
        SELECT s.id, s.title,
               (SELECT m2.parts_json FROM messages m2
                WHERE m2.session_id = s.id AND m2.role = 'user'
                ORDER BY m2.sort_order ASC LIMIT 1) AS first_user_msg,
               s.source, s.created_at, s.updated_at,
               (SELECT COUNT(*) FROM messages m3 WHERE m3.session_id = s.id) AS msg_count
        FROM sessions s
        $whereSql
        ORDER BY s.updated_at DESC
        LIMIT ?
    """.trimIndent()
    return dao.runSessionMetaQuery(SimpleSQLiteQuery(sql, args.toTypedArray())).map { row ->
        SessionMeta(
            id = row.id,
            title = row.title,
            preview = row.firstUserMsg?.let(MessagePreviews::fullTextOf)?.takeIf { it.isNotBlank() }?.take(60),
            source = row.source,
            startedAt = row.createdAt,
            lastActive = row.updatedAt,
            messageCount = row.msgCount,
        )
    }
}

/**
 * `search`：过量取 3 倍再裁 —— parts_json 的 LIKE 会命中工具调用的 JSON
 * 元数据（工具名里恰好含关键词），解析投影后把这类行丢弃，裁到 limit。
 */
internal suspend fun ChatRepository.searchMessages(
    sessionIds: List<String>?,
    keywords: List<String>,
    limit: Int,
    startMs: Long?,
    endMs: Long?,
): List<MessageSearchMatch> {
    if (keywords.isEmpty()) return emptyList()
    val clauses = ArrayList<String>()
    val args = ArrayList<Any>()
    for (keyword in keywords) {
        clauses += "m.parts_json LIKE ?"
        args += "%$keyword%"
    }
    if (!sessionIds.isNullOrEmpty()) {
        clauses += "m.session_id IN (${sessionIds.joinToString(",") { "?" }})"
        args.addAll(sessionIds)
    }
    startMs?.let { clauses += "m.created_at >= ?"; args += it }
    endMs?.let { clauses += "m.created_at <= ?"; args += it }
    args += limit * 3
    val sql = """
        SELECT m.session_id, m.id, m.role, m.created_at, m.parts_json
        FROM messages m
        WHERE ${clauses.joinToString(" AND ")}
        ORDER BY m.created_at DESC
        LIMIT ?
    """.trimIndent()
    val hits = ArrayList<MessageSearchMatch>()
    for (row in dao.runMessageSearch(SimpleSQLiteQuery(sql, args.toTypedArray()))) {
        val text = MessagePreviews.fullTextOf(row.partsJson)
        if (text.isBlank()) continue
        val snippet = MessagePreviews.snippetAround(text, keywords, ChatRepository.SNIPPET_MAX)
        if (snippet.isBlank()) continue
        hits += MessageSearchMatch(row.sessionId, row.id, row.role, row.createdAt, snippet)
        if (hits.size >= limit) break
    }
    return hits
}

/**
 * `messages`：跳过投影后无文本的行（纯系统提醒、纯工具回合），让 agent
 * 看到连续的用户可见转录。maxChars 默认 600；`--full` 传 50000。
 * startMs/endMs 独立可选（GH#200），null = 该侧不设界。
 */
internal suspend fun ChatRepository.loadMessagePage(
    sessionId: String,
    offset: Int,
    limit: Int,
    maxChars: Int = ChatRepository.MESSAGE_TEXT_MAX,
    startMs: Long? = null,
    endMs: Long? = null,
): List<MessagePageItem> {
    val rows: List<MessageRow> = if (startMs == null && endMs == null) {
        dao.messagePage(sessionId, offset, limit)
    } else {
        dao.messagePageBetween(sessionId, offset, limit, startMs, endMs)
    }
    return rows.mapNotNull { row ->
        val text = MessagePreviews.fullTextOf(row.partsJson)
        if (text.isBlank()) return@mapNotNull null
        MessagePageItem(
            messageId = row.id,
            role = row.role,
            createdAt = row.createdAt,
            text = text.take(maxChars),
            truncated = text.length > maxChars,
        )
    }
}

/** 与 [loadMessagePage] 同一区间的计数，保证 total 与返回切片同集。 */
internal suspend fun ChatRepository.messageCountInRange(
    sessionId: String,
    startMs: Long?,
    endMs: Long?,
): Int = if (startMs == null && endMs == null) {
    dao.messageCountIn(sessionId)
} else {
    dao.messageCountBetween(sessionId, startMs, endMs)
}
