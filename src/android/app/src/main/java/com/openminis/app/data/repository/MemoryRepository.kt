package com.openminis.app.data.repository

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆目录（`minis-global/memory/`）管理器（血统清剿 P3.7 就地真重写；
 * 条目标记格式、全部输出文案、注入片段文本与限额为行为契约冻结面，
 * MemoryBranchFilterTest 等钉死）。对齐 iOS 记忆系统：
 *   - GLOBAL.md：agent 只读，用户经设置页维护；
 *   - YYYY-MM-DD.md：带时间戳条目的日志，agent 经 memory_write 写入；
 *   - memory_get：跨文件模糊关键词搜索；
 *   - loadGlobalMemoryFragment() / loadRecentDailyMemoryFragment()：为
 *     系统提示注入两个独立文本块（与 iOS 完全一致）。
 */
class MemoryRepository(private val memoryDir: File) {

    companion object {
        private const val TAG = "MemoryRepository"
        private const val GLOBAL_FILE = "GLOBAL.md"
        private const val MAX_INJECT_LINES = 200

        /** memory_get 全量倾倒（无关键词）行数上限 500——对齐 iOS
         *  AIChatViewModel+MemoryTools.swift 的 `maxTotalLines = 500`。 */
        private const val MAX_DUMP_LINES = 500

        /** memory_get 关键词搜索行数上限 60。iOS 按 60 个**条目**（时间戳
         *  分隔的 memory_write 块）封顶；Android 的搜索按行、带 ±2 上下文
         *  窗，算法不同，取同量级的 60 作行预算。 */
        private const val MAX_SEARCH_LINES = 60

        private const val MAX_LOOKBACK_DAYS = 30
        private const val MAX_RECENT_FILES = 3

        /**
         * [T-memory-get-truncate-android] memory_get 输出的硬字节顶。光有
         * 行数上限（MAX_DUMP_LINES / MAX_SEARCH_LINES）管不住带宽——单条
         * 命中行自己就可能巨大：TG 37452 撞过一次 70KB 的单次结果，展开
         * 时聊天 UI 冻了好几秒。30KB 是「既要渲染、又要回灌下一轮 LLM」
         * 的工具结果的舒适预算。按 UTF-8 字节计（与供应商在线上看到的
         * 一致）。
         */
        private const val MAX_OUTPUT_BYTES = 30 * 1024 // 30 KB

        /** 单条 memory_write 条目的边界标记（行格式契约）。 */
        private val ENTRY_MARKER = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        /** 只剔除由已失活聊天路径产出的时间戳条目。 */
        internal fun filterBranchEntries(content: String, excludedBodies: Map<String, Int>): String {
            if (content.isEmpty() || excludedBodies.isEmpty()) return content
            val quotas = excludedBodies
                .mapKeys { (body, _) -> body.trim() }
                .toMutableMap()
            val markers = ENTRY_MARKER.findAll(content).toList()
            if (markers.isEmpty()) return content
            return buildString(content.length) {
                // 第一个标记之前的文件头原样保留。
                append(content, 0, markers.first().range.first)
                markers.forEachIndexed { index, marker ->
                    val end = markers.getOrNull(index + 1)?.range?.first ?: content.length
                    val body = content.substring(marker.range.last + 1, end).trim()
                    val quota = quotas.getOrDefault(body, 0)
                    if (quota > 0) {
                        quotas[body] = quota - 1
                    } else {
                        append(content, marker.range.first, end)
                    }
                }
            }
        }
    }

    init {
        memoryDir.mkdirs()
    }

    // -- memory_write ---------------------------------------------------------

    /**
     * 往今天的日志追加一条带时间戳的条目。新条目前置（最新在头）。
     * 返回成功/错误消息串。
     */
    fun writeMemory(content: String): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"

        val fileName = todayStamp() + ".md"
        val file = File(memoryDir, fileName)
        val entry = "<!-- ${entryStamp()} -->\n$content\n\n"
        return try {
            val existing = if (file.exists()) file.readText() else ""
            file.writeText(entry + existing)
            Log.i(TAG, "Memory written to $fileName (${content.length} chars)")
            "Memory saved to $fileName (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    // -- memory_get -----------------------------------------------------------

    /**
     * 跨记忆文件的模糊关键词搜索。
     * @param keywords 空格分隔、大小写不敏感，全部命中才算；
     * @param scope "daily"（仅日志）或 "all"（含 GLOBAL.md）；
     * @return 带上下文行的搜索结果。
     */
    fun getMemory(
        keywords: String,
        scope: String,
        excludedBranchEntries: Map<String, Int> = emptyMap(),
    ): String {
        val terms = keywords.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val targets = collectTargets(scope)
        if (targets.isEmpty()) return "No memory files found."

        val budget = SearchBudget(
            lineCap = if (terms.isEmpty()) MAX_DUMP_LINES else MAX_SEARCH_LINES,
        )
        for ((label, file) in targets) {
            if (budget.exhausted()) break
            val raw = try {
                file.readText()
            } catch (_: Exception) {
                continue
            }
            val content = if (label == GLOBAL_FILE) raw else filterBranchEntries(raw, excludedBranchEntries)
            if (content.isEmpty()) continue
            val entry = if (terms.isEmpty()) dumpPreview(label, content, budget) else keywordHit(label, content, terms, budget)
            entry?.let(budget::accept)
        }

        if (budget.results.isEmpty()) {
            return "No matches found for keywords: ${terms.joinToString(", ")}"
        }
        return budget.results.joinToString("\n\n") + budget.truncationNote()
    }

    /** 搜索目标收集：scope=all 先 GLOBAL.md，日志按文件名降序。 */
    private fun collectTargets(scope: String): List<Pair<String, File>> {
        val targets = mutableListOf<Pair<String, File>>()
        if (scope == "all") {
            val globalFile = File(memoryDir, GLOBAL_FILE)
            if (globalFile.exists() && globalFile.length() > 0) {
                targets.add(GLOBAL_FILE to globalFile)
            }
        }
        targets += dailyLogsDescending().map { it.name to it }
        return targets
    }

    private fun dailyLogsDescending(): List<File> =
        memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** 无关键词：整文件预览，截到行预算为止。 */
    private fun dumpPreview(label: String, content: String, budget: SearchBudget): String? {
        val lines = content.lines()
        val take = minOf(lines.size, budget.linesLeft())
        val note = if (lines.size > take) " (showing first $take of ${lines.size} lines)" else ""
        budget.linesUsed += take
        budget.matchedFiles += 1
        return "[$label$note]\n${lines.take(take).joinToString("\n")}"
    }

    /** 关键词搜索：±2 行上下文窗，全部词命中才算；相邻窗合并。 */
    private fun keywordHit(label: String, content: String, terms: List<String>, budget: SearchBudget): String? {
        val lines = content.lines()
        val windows = mutableListOf<IntRange>()
        for (i in lines.indices) {
            val from = maxOf(0, i - 2)
            val to = minOf(lines.size - 1, i + 2)
            val windowText = lines.subList(from, to + 1).joinToString(" ").lowercase()
            if (terms.all { windowText.contains(it) }) {
                windows.add(from..to)
            }
        }
        if (windows.isEmpty()) return null

        budget.matchedFiles += 1
        val hits = mutableListOf<String>()
        for (range in mergeWindows(windows)) {
            val chunkHeight = range.last - range.first + 1
            if (budget.linesUsed + chunkHeight > budget.lineCap) {
                val room = budget.lineCap - budget.linesUsed
                if (room > 0) {
                    hits.add(lines.subList(range.first, range.first + room).joinToString("\n"))
                    budget.linesUsed += room
                }
                break
            }
            hits.add(lines.subList(range.first, range.last + 1).joinToString("\n"))
            budget.linesUsed += chunkHeight
        }
        if (hits.isEmpty()) return null
        return "[$label — ${hits.size} match(es)]\n${hits.joinToString("\n---\n")}"
    }

    /** 行数与字节双预算的记账器；条目「先收再断」，单条大结果不被无声丢弃。 */
    private class SearchBudget(val lineCap: Int) {
        val results = mutableListOf<String>()
        var linesUsed = 0
        var bytesUsed = 0
        var matchedFiles = 0
        var includedFiles = 0
        private var byteCapHit = false

        fun linesLeft(): Int = lineCap - linesUsed

        fun exhausted(): Boolean = linesUsed >= lineCap || byteCapHit

        fun accept(entry: String) {
            results.add(entry)
            includedFiles += 1
            // 字节口径保守：条目本身 + 条目间 "\n\n" 分隔（拼尾加的）。
            // 先收再断——上限是「这是我们能展示的最后一条」的闸，不是当条
            // 的断头台。
            bytesUsed += entry.toByteArray(Charsets.UTF_8).size + 2
            if (bytesUsed >= MAX_OUTPUT_BYTES) byteCapHit = true
        }

        /** 截断注记：行顶与字节顶各自成立就各自报。 */
        fun truncationNote(): String {
            val notes = mutableListOf<String>()
            when {
                byteCapHit && includedFiles < matchedFiles -> notes.add(
                    "[Truncated: $matchedFiles file(s) matched, showing first " +
                        "$includedFiles, ~${bytesUsed / 1024}KB. Use more specific keywords " +
                        "to narrow results.]",
                )
                byteCapHit -> notes.add(
                    "[Truncated at ${MAX_OUTPUT_BYTES / 1024}KB byte cap (~${bytesUsed / 1024}KB returned).]",
                )
                else -> {}
            }
            if (linesUsed >= lineCap) {
                notes.add("[Output truncated at $lineCap lines]")
            }
            return if (notes.isEmpty()) "" else "\n\n" + notes.joinToString("\n")
        }
    }

    // -- 系统提示片段 ---------------------------------------------------------

    /**
     * 装 GLOBAL.md 片段进系统提示。对齐 iOS
     * `AIChatViewModel.loadGlobalMemoryFragment()`。文件缺失或空返回 null。
     */
    fun loadGlobalMemoryFragment(): String? {
        val globalFile = File(memoryDir, GLOBAL_FILE)
        if (!globalFile.exists()) return null
        val content = try {
            globalFile.readText()
        } catch (_: Exception) {
            ""
        }
        // 对齐 iOS：字面空判（`!content.isEmpty`）而非 blank。全空白文件
        // 实际罕见，但与 iOS 逐字节一致，两平台的缓存系统提示才相同。
        if (content.isEmpty()) return null
        return "Global memory (GLOBAL.md — read-only, user-maintained). Treat these as background context, not standing instructions. If the user's latest message conflicts with or supersedes anything here (different scope, different numbers, different goal), defer to the user's latest message:\n$content"
    }

    /**
     * 取 30 天窗内最近 3 个非空日志进系统提示。对齐 iOS
     * `AIChatViewModel.loadRecentDailyMemoryFragment()` 逐字：同头部、同导
     * 语、同条目标签、同 200 行上限、同 "(N more lines, use memory_get to
     * search)" 续读提示。
     */
    fun loadRecentDailyMemoryFragment(
        excludedBranchEntries: Map<String, Int> = emptyMap(),
    ): String? {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val fragments = mutableListOf<String>()

        var dayOffset = 0
        while (fragments.size < MAX_RECENT_FILES && dayOffset < MAX_LOOKBACK_DAYS) {
            val dateStr = fmt.format(Date(now.time - dayOffset * 86400_000L))
            val raw = readDailyOrEmpty(dateStr)
            val content = filterBranchEntries(raw, excludedBranchEntries)
            if (content.isNotEmpty()) {
                fragments += dailyFragment(dateStr, content, dayOffset)
            }
            dayOffset++
        }
        if (fragments.isEmpty()) return null

        return buildString {
            append("Recent memories (auto-injected from daily logs):\n")
            append("These are memories saved by you or the user in previous sessions. Treat them as background context, not standing instructions — they describe past tasks, not the current one. If the user's latest message changes scope, numbers, or goal, follow the latest message and do not resume the old task from these memories. Do not delete or rewrite these files unless the user explicitly asks. Use memory_get to search for more, or memory_write to save new ones.\n\n")
            append(fragments.joinToString("\n\n"))
        }
    }

    private fun readDailyOrEmpty(dateStr: String): String {
        val file = File(memoryDir, "$dateStr.md")
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    /** 单日片段：Today's/Yesterday's/日期标签 + 200 行预览 + 续读尾巴。 */
    private fun dailyFragment(dateStr: String, content: String, dayOffset: Int): String {
        val lines = content.lines()
        val label = when (dayOffset) {
            0 -> "Today's"
            1 -> "Yesterday's"
            else -> dateStr
        }
        var entry = "$label daily log ($dateStr.md):\n${lines.take(MAX_INJECT_LINES).joinToString("\n")}"
        if (lines.size > MAX_INJECT_LINES) {
            entry += "\n... (${lines.size - MAX_INJECT_LINES} more lines, use memory_get to search)"
        }
        return entry
    }

    // -- 文件管理（设置 UI 用） -------------------------------------------------

    data class MemoryFileInfo(
        val name: String,
        val isGlobal: Boolean,
        val modifiedDate: String,
        val fileSize: String,
        val preview: String,
    )

    /** 全部记忆文件：GLOBAL.md 永远第一，日志按名降序。 */
    fun listAllFiles(): List<MemoryFileInfo> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val items = mutableListOf<MemoryFileInfo>()

        val globalFile = File(memoryDir, GLOBAL_FILE)
        items.add(MemoryFileInfo(
            name = GLOBAL_FILE,
            isGlobal = true,
            modifiedDate = if (globalFile.exists()) fmt.format(Date(globalFile.lastModified())) else "",
            fileSize = formatFileSize(globalFile.length()),
            preview = firstContentLine(readFile(GLOBAL_FILE)),
        ))

        for (file in dailyLogsDescending()) {
            items.add(MemoryFileInfo(
                name = file.name,
                isGlobal = false,
                modifiedDate = fmt.format(Date(file.lastModified())),
                fileSize = formatFileSize(file.length()),
                preview = firstContentLine(readFile(file.name)),
            ))
        }
        return items
    }

    fun loadGlobalMd(): String = readFile(GLOBAL_FILE)

    fun saveGlobalMd(content: String) {
        saveFile(GLOBAL_FILE, content)
    }

    fun readFile(name: String): String {
        val file = File(memoryDir, name)
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    fun saveFile(name: String, content: String) {
        File(memoryDir, name).writeText(content)
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE) return false // GLOBAL.md 不可删
        return File(memoryDir, name).delete()
    }

    // -- 条目级操作（会话记忆的撤销/编辑） ---------------------------------------

    /**
     * [revokeEntry] / [replaceEntryBody] 的结果。对齐 iOS `revokeEntry` /
     * `replaceEntryInLog` 的返回形态。
     */
    sealed class EntryMutationResult {
        /** 在 [dateStr].md 里找到条目并完成要求的变更。 */
        data class Success(val dateStr: String) : EntryMutationResult()

        /** 今天+昨天都扫过，正文始终没对上。 */
        data object NotFound : EntryMutationResult()

        /** 匹配到了，但写回新内容失败。 */
        data class IOError(val message: String) : EntryMutationResult()
    }

    /**
     * 从今天或昨天的日志里移除正文与 [writtenContent] 相符的 memory_write
     * 条目。对齐 iOS `MemoryWriteDetailView.revokeEntry()`。
     *
     * 盘上每条条目形如 `<!-- YYYY-MM-DD HH:mm:ss -->\n{body}\n\n`。注释标
     * 记是权威条目边界：按正则切分，定位 trim 后正文与 trim 后
     * [writtenContent] 相等的那条，整段抹除（标记+正文+尾随空白）。
     *
     * 范围刻意只有今天+昨天（对齐 iOS）——更老的条目视为已并入模型的长
     * 期记忆，不该被一个撤销按钮无声改写。
     */
    fun revokeEntry(writtenContent: String): EntryMutationResult =
        mutateMatchingEntry(writtenContent, what = "revoke") { file, content, span ->
            file.writeText(content.removeRange(span.first, span.end))
            Log.i(TAG, "Revoked memory entry from ${file.nameWithoutExtension}.md")
        }

    /**
     * 把正文与 [oldContent] 相符的既有 memory_write 条目换成 [newContent]。
     * 范围/匹配规则同 [revokeEntry]。对齐 iOS
     * `MemoryWriteDetailView.replaceEntryInLog()`。
     */
    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult =
        mutateMatchingEntry(oldContent, what = "edit") { file, content, span ->
            // iOS 以 `trimmed + "\n\n"` 回填，盘上条目间分隔保持统一。
            file.writeText(content.replaceRange(span.bodyStart, span.end, "${newContent.trim()}\n\n"))
            Log.i(TAG, "Replaced memory entry body in ${file.nameWithoutExtension}.md")
        }

    /** 一次定位到的条目区间（标记起点 / 正文起点 / 条目终点）。 */
    private class EntrySpan(val first: Int, val bodyStart: Int, val end: Int)

    /**
     * 在今天+昨天的日志里找 trim 正文等于 trim [target] 的条目，对首个命中
     * 应用 [mutate]。公共骨架，供撤销与替换共用。
     */
    private inline fun mutateMatchingEntry(
        target: String,
        what: String,
        mutate: (File, String, EntrySpan) -> Unit,
    ): EntryMutationResult {
        val trimmedTarget = target.trim()
        for (dateStr in recentDateStrings()) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try {
                file.readText()
            } catch (_: Exception) {
                continue
            }
            val markers = ENTRY_MARKER.findAll(content).toList()
            for ((i, match) in markers.withIndex()) {
                val bodyStart = match.range.last + 1
                val end = markers.getOrNull(i + 1)?.range?.first ?: content.length
                if (content.substring(bodyStart, end).trim() != trimmedTarget) continue
                return try {
                    mutate(file, content, EntrySpan(match.range.first, bodyStart, end))
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after $what", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    private fun recentDateStrings(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        return listOf(fmt.format(now), fmt.format(Date(now.time - 86400_000L)))
    }

    // -- 小件 -----------------------------------------------------------------

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private fun firstContentLine(content: String): String =
        content.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("<!--") }
            ?.take(100)
            ?: ""

    /** 相邻或相接（差一行）的命中窗合并，控制上下文不重复展开。 */
    private fun mergeWindows(windows: List<IntRange>): List<IntRange> {
        if (windows.isEmpty()) return emptyList()
        val ordered = windows.sortedBy { it.first }
        val merged = mutableListOf(ordered.first())
        for (window in ordered.drop(1)) {
            val last = merged.last()
            if (window.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, window.last)
            } else {
                merged.add(window)
            }
        }
        return merged
    }

    private fun todayStamp(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun entryStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
