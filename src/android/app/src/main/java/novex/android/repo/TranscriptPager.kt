package novex.android.repo

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import novex.android.data.chat.ChatDao
import novex.android.data.chat.MessageRow

/**
 * 消息全量读取的分页器。
 *
 * 为什么分页：Android 的 CursorWindow 单窗口 2 MB 上限。一条超长
 * tool_result（曾见 13 MB 的 browser_use 转储，Issue #17）就会让整批
 * SELECT 的 Cursor 物化失败，抛 SQLiteBlobTooBigException 把读线程钉死。
 * 每页 [PAGE_SIZE] 行把常规页压在窗口内；万一某页仍炸，退化为逐行取，
 * 单行还炸的那一条用占位行顶替 —— 转录保持连续，加载不崩。
 *
 * 历史遗留的超长行不迁移；新写入侧的闸门见
 * [ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH]。
 */
internal class TranscriptPager(private val dao: ChatDao) {

    suspend fun loadAll(sessionId: String): List<MessageRow> {
        // 安全模式兜底：上游 ViewModel 已设闸，但压缩、分叉、重生成标题、
        // 调试菜单等调用点可能在安全模式对话框未处理前就进来。空列表与
        // 「无消息」同形，对所有调用方无害。
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w(logTag, "history read skipped in safe mode (sessionId=$sessionId)")
            return emptyList()
        }
        val total = dao.messageCountIn(sessionId)
        if (total == 0) return emptyList()
        val collected = ArrayList<MessageRow>(total)
        var offset = 0
        while (offset < total) {
            val page = fetchPage(sessionId, offset)
            if (page.isEmpty()) break
            collected.addAll(page)
            offset += PAGE_SIZE
        }
        return collected
    }

    private suspend fun fetchPage(sessionId: String, offset: Int): List<MessageRow> {
        return try {
            dao.messagePage(sessionId, offset, PAGE_SIZE)
        } catch (e: SQLiteBlobTooBigException) {
            pageRowByRow(sessionId, offset)
        } catch (e: IllegalStateException) {
            // 某些 Room/SQLite 组合把 CursorWindow 溢出包成
            // IllegalStateException("Couldn't read row N, col N from CursorWindow")。
            if (e.message?.contains("CursorWindow", ignoreCase = true) == true) {
                pageRowByRow(sessionId, offset)
            } else {
                throw e
            }
        }
    }

    /** 逐行取一页：坏行以占位 null 跳过，其余行照常服务。 */
    private suspend fun pageRowByRow(sessionId: String, offset: Int): List<MessageRow> {
        val rows = ArrayList<MessageRow>(PAGE_SIZE)
        for (i in 0 until PAGE_SIZE) {
            val row = try {
                dao.messagePage(sessionId, offset + i, 1).firstOrNull()
            } catch (e: SQLiteBlobTooBigException) {
                null
            } catch (e: IllegalStateException) {
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) null else throw e
            }
            if (row != null) rows.add(row)
        }
        return rows
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val logTag = "NovexChatRepo"
    }
}
