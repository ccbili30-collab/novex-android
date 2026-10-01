package com.openminis.app.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天输入框的 @-提及文件索引（血统清剿 P3.7 就地真重写；路径拼装
 * `/var/minis/…`、Scope 三元组、评分档位与公共常量为契约冻结面）。
 *
 * 三层扫描器，每层扫完立刻发布——用户先看到会话本地文件，再共享根，
 * 最后（有挂载时）外部挂载：
 *   1. **会话根**：`workspace/<sid>` + `attachments/<sid>`；
 *   2. **共享根**：`shared/`、`skills/`、`memory/`；
 *   3. **挂载根**：[mountsProvider] 的每一项（如 SAF 挂的目录）。每个
 *      挂载无条件带一条自指条目，`@<mountName>` 永远可用。
 *
 * 评分（[matches]，从大到小相加）：
 *   - 名字匹配档（0/1000…10000）主导主序；
 *   - Scope.rankBoost（0–600）档内再加权；
 *   - 顶层作用域根 +50——整个技能/挂载目录压在它的子文件之上；
 *   - 新近度微加成（0–80）。
 * 相邻名字档最小间隔（1000）大于全部次级信号之和上限（600+50+80=730），
 * 所以更强的名字匹配在任何作用域里都赢。
 */
class FileMentionIndex(
    private val filesDir: File,
    private val mountsProvider: () -> List<MountEntry> = { emptyList() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(File(context.filesDir, "minis-global"))

    /**
     * 作用域优先级——`order` 一职两用：空查询默认排序键（小者胜）+
     * `rankBoost` 的名字分之上的作用域加权。case 次序对齐 iOS 同名件：
     * skills > attachments > mount > shared > workspace > memory。
     * rankBoost 最大值（600）刻意压在相邻名字档间隔（1000）之下。
     */
    enum class Scope(val displayLabel: String, val order: Int, val rankBoost: Int) {
        SKILLS("skills", 0, 600),
        ATTACHMENTS("attachments", 1, 500),
        MOUNT("mount", 2, 400),
        SHARED("shared", 3, 300),
        WORKSPACE("workspace", 4, 200),
        MEMORY("memory", 5, 100),
    }

    data class MountEntry(val name: String, val root: File)

    data class Entry(
        /** Linux 可见路径（插入输入框的就是它）。 */
        val linuxPath: String,
        val scope: Scope,
        val mountName: String?,
        val modifiedAt: Long,
        val isDirectory: Boolean,
    ) {
        val basename: String get() = linuxPath.substringAfterLast('/').ifEmpty { linuxPath }
        val displayPath: String get() = linuxPath.removePrefix("/var/minis/")
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** 缓存判据 + 取消令牌：换会话或 TTL 过期才重扫；旧扫描一键作废。 */
    private var cachedSessionId: String? = null
    private var cachedAt: Long = 0
    private var scanToken: UUID = UUID.randomUUID()
    private var scanJob: Job? = null

    /** 会话变了或缓存过期才重扫。幂等。 */
    fun refreshIfNeeded(sessionId: String) {
        val now = System.currentTimeMillis()
        val cacheUsable = cachedSessionId == sessionId &&
            now - cachedAt < cacheTtlMs &&
            _entries.value.isNotEmpty()
        if (cacheUsable) return

        cachedSessionId = sessionId
        cachedAt = now
        val token = UUID.randomUUID()
        scanToken = token
        scanJob?.cancel()
        scanJob = scope.launch { runScan(sessionId, token) }
    }

    /** 无视缓存强制重扫。 */
    fun refresh(sessionId: String) {
        cachedAt = 0
        refreshIfNeeded(sessionId)
    }

    // ─── 扫描管线 ──────────────────────────────────────────────────────────

    /**
     * 三层依序扫描、逐层发布。收集桶跨层累积；发布时按 linuxPath 去重、
     * 默认序排序。令牌在每层与每个挂载前校验，被取代的扫描尽快停摆。
     */
    private suspend fun runScan(sessionId: String, token: UUID) {
        _isScanning.value = true
        val bucket = mutableListOf<Entry>()
        try {
            scanSessionLayers(sessionId, bucket, token)
            scanSharedLayers(bucket, token)
            scanMountLayers(bucket, token)
        } finally {
            _isScanning.value = false
        }
    }

    /** 第 1 层：会话本地两根，一次发布。 */
    private suspend fun scanSessionLayers(sessionId: String, bucket: MutableList<Entry>, token: UUID) {
        val batch = collectRooted(
            roots = listOf(
                File(filesDir, "workspace/$sessionId") to Scope.WORKSPACE,
                File(filesDir, "attachments/$sessionId") to Scope.ATTACHMENTS,
            ),
            pathOfRoot = { scope -> "/var/minis/${scope.displayLabel}/$sessionId" },
        )
        bucket += batch
        publish(token, bucket)
    }

    /** 第 2 层：共享三根，一次发布。 */
    private suspend fun scanSharedLayers(bucket: MutableList<Entry>, token: UUID) {
        val batch = collectRooted(
            roots = listOf(
                File(filesDir, "shared") to Scope.SHARED,
                File(filesDir, "skills") to Scope.SKILLS,
                File(filesDir, "memory") to Scope.MEMORY,
            ),
            pathOfRoot = { scope -> "/var/minis/${scope.displayLabel}" },
        )
        bucket += batch
        publish(token, bucket)
    }

    /**
     * 第 3 层：挂载逐个发布——一个慢挂载不挡其余。每个挂载先无条件注入
     * 自指条目（预算耗尽也保证 `@<name>` 可用），剩余预算再下钻。
     */
    private suspend fun scanMountLayers(bucket: MutableList<Entry>, token: UUID) {
        var budget = GLOBAL_SCAN_BUDGET - bucket.size
        for (mount in mountsProvider()) {
            if (scanToken != token) return
            // T219：与 PRoot bind 路径及 iOS 提示文案对齐——挂载在沙箱内
            // 位于 /var/minis/mounts/<name>，@-提及插入该精确路径，agent 的
            // 心智模型在提及、提示文案与 shell 三处保持一致。
            val mountRoot = "/var/minis/mounts/${mount.name}"
            bucket += Entry(
                linuxPath = mountRoot,
                scope = Scope.MOUNT,
                mountName = mount.name,
                modifiedAt = mount.root.lastModified(),
                isDirectory = true,
            )
            if (budget > 0) {
                val batch = walkBounded(
                    root = mount.root,
                    linuxRoot = mountRoot,
                    scope = Scope.MOUNT,
                    mountName = mount.name,
                    maxDepth = MOUNT_SCAN_MAX_DEPTH,
                    budget = budget,
                )
                bucket += batch
                budget -= batch.size
            }
            publish(token, bucket)
        }
    }

    /** 一组 (目录, 作用域) 根：每根先注入根自指（`@workspace` 可引用），再全量下钻。 */
    private fun collectRooted(
        roots: List<Pair<File, Scope>>,
        pathOfRoot: (Scope) -> String,
    ): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((dir, scope) in roots) {
            if (!dir.isDirectory) continue
            val linuxRoot = pathOfRoot(scope)
            out += Entry(
                linuxPath = linuxRoot,
                scope = scope,
                mountName = null,
                modifiedAt = dir.lastModified(),
                isDirectory = true,
            )
            out += walkBounded(
                root = dir,
                linuxRoot = linuxRoot,
                scope = scope,
                mountName = null,
                maxDepth = Int.MAX_VALUE,
                budget = GLOBAL_SCAN_BUDGET,
            )
        }
        return out
    }

    /**
     * 预算封顶的 BFS 目录走查：跳点文件、跳 SKIP_DIR_NAMES 目录；目录在
     * 深度限内入队继续下钻；产出条目的 linux 路径 = 根映射 + 相对路径。
     */
    private fun walkBounded(
        root: File,
        linuxRoot: String,
        scope: Scope,
        mountName: String?,
        maxDepth: Int,
        budget: Int,
    ): List<Entry> {
        val found = ArrayList<Entry>()
        val rootAbs = root.absolutePath
        val pending = ArrayDeque<Pair<File, Int>>().apply { addLast(root to 0) }
        while (pending.isNotEmpty() && found.size < budget) {
            val (dir, depth) = pending.removeFirst()
            if (depth > maxDepth) continue
            val children = dir.listFiles() ?: continue
            collectChildren(children, depth, maxDepth, budget, rootAbs, linuxRoot, scope, mountName, found, pending)
        }
        return found
    }

    /** 单层目录收集：预算内产出条目 + 深度限内的子目录入队下钻。 */
    private fun collectChildren(
        children: Array<File>,
        depth: Int,
        maxDepth: Int,
        budget: Int,
        rootAbs: String,
        linuxRoot: String,
        scope: Scope,
        mountName: String?,
        found: ArrayList<Entry>,
        pending: ArrayDeque<Pair<File, Int>>,
    ) {
        for (child in children) {
            if (found.size >= budget) return
            val hidden = child.name.startsWith(".")
            val skippedDir = child.isDirectory && child.name in SKIP_DIR_NAMES
            if (hidden || skippedDir) continue
            val relative = child.absolutePath.removePrefix(rootAbs).removePrefix("/")
            if (relative.isEmpty()) continue
            found += Entry(
                linuxPath = "$linuxRoot/$relative",
                scope = scope,
                mountName = mountName,
                modifiedAt = child.lastModified(),
                isDirectory = child.isDirectory,
            )
            val mayDescend = child.isDirectory && depth + 1 <= maxDepth
            if (mayDescend) pending.addLast(child to depth + 1)
        }
    }

    /** 令牌双重校验后主线程落值：linuxPath 去重 + 默认序。 */
    private suspend fun publish(token: UUID, collected: List<Entry>) {
        if (scanToken != token) return
        val sorted = collected.associateBy { it.linuxPath }.values.sortedWith(defaultOrdering)
        withContext(Dispatchers.Main) {
            if (scanToken != token) return@withContext
            _entries.value = sorted
        }
    }

    // ─── 查询与评分 ────────────────────────────────────────────────────────

    /**
     * 按 [query] 排名。空查询直接取默认序的前 [limit] 条（[entries] 已排好）。
     * 档位/加权/并列裁决见类注释；并列时先作用域优先、再新者、再短路径
     * （更「近」的文件）。
     */
    fun matches(query: String, limit: Int = DEFAULT_MATCH_LIMIT): List<Entry> {
        val pool = _entries.value
        if (query.isBlank()) return pool.take(limit)
        val q = query.lowercase()

        val now = System.currentTimeMillis()
        val recencyWindowMs = 56L * 24 * 60 * 60 * 1000 // 8 周 → 0..80

        val scored = ArrayList<Pair<Int, Entry>>(pool.size)
        for (entry in pool) {
            val tier = nameMatchTier(entry, q)
            if (tier == 0) continue
            val secondary = entry.scope.rankBoost + topLevelRootBonus(entry) +
                recencyBonus(entry, now, recencyWindowMs)
            scored += (tier + secondary) to entry
        }
        val byRank = compareByDescending<Pair<Int, Entry>> { it.first }
            // 并列裁决：先作用域优先、再新者、再短路径（更「近」的文件）。
            .thenBy { it.second.scope.order }
            .thenByDescending { it.second.modifiedAt }
            .thenBy { it.second.linuxPath.length }
        return scored.sortedWith(byRank).take(limit).map(Pair<Int, Entry>::second)
    }

    /** 名字匹配档：取适用的最强档，0 = 淘汰。 */
    private fun nameMatchTier(entry: Entry, q: String): Int {
        val base = entry.basename.lowercase()
        val path = entry.linuxPath.lowercase()
        val stem = base.substringBeforeLast('.', missingDelimiterValue = base)
        return when {
            base == q -> 10_000                              // 全名精确
            stem.isNotEmpty() && stem == q -> 9_000          // 词干精确（去扩展名）
            base.startsWith(q) -> 7_000                      // 前缀
            wordBoundaryHit(base, q) -> 6_000                // 词边界包含
            q in base -> 4_000                               // basename 任意位包含
            pathComponentsHit(path, q) -> 2_000              // 祖先目录命中
            q in path -> 1_000                               // 全路径任意位
            else -> 0
        }
    }

    /**
     * basename 的词边界子串匹配：query 出现在由 `_`、`-`、` `、`.`、`/`
     * 分词的词片开头即命中（如 `markdown_parser.py` 里的 `parser`、
     * `image-web-search.md` 里的 `web`）。把用户直觉里的「部分匹配」抬到
     * 任意位子串之上。
     */
    private fun wordBoundaryHit(base: String, query: String): Boolean {
        if (query.isEmpty() || base.isEmpty()) return false
        val delimiters = setOf('_', '-', ' ', '.', '/')
        var cursor = 0
        while (cursor < base.length) {
            if (base.startsWith(query, startIndex = cursor)) return true
            // 跳到下一个词边界再试。
            val nextDelimiter = (cursor until base.length).firstOrNull { delimiters.contains(base[it]) }
                ?: return false
            cursor = nextDelimiter + 1
        }
        return false
    }

    /** 跨路径分量的子串匹配——basename 不中时路径仍可能中（`@mounts` 找到
     *  `/var/minis/mounts/...` 里的文件）。 */
    private fun pathComponentsHit(path: String, query: String): Boolean =
        path.split('/').any { query in it }

    /**
     * 顶层作用域根（作用域根的直接子级，如 `/var/minis/skills/foo`、
     * `/var/minis/mounts/bar`、`/var/minis/shared/baz`）+50：敲 `@foo` 时
     * 整个技能目录浮到它的散件之上（对齐 iOS）。
     */
    private fun topLevelRootBonus(entry: Entry): Int =
        if (isTopLevelScopeRoot(entry)) 50 else 0

    private fun isTopLevelScopeRoot(entry: Entry): Boolean {
        if (!entry.isDirectory) return false
        for (top in listOf("/var/minis/skills/", "/var/minis/mounts/", "/var/minis/shared/")) {
            if (!entry.linuxPath.startsWith(top)) continue
            val depth = entry.linuxPath.removePrefix(top)
            // 仅第一层子级——不能再含 '/'。
            return depth.isNotEmpty() && '/' !in depth
        }
        return false
    }

    /** 新近度微加成（≤80）：8 周线性衰减，其余全等时新者胜。 */
    private fun recencyBonus(entry: Entry, now: Long, windowMs: Long): Int {
        val ageMs = (now - entry.modifiedAt).coerceAtLeast(0L)
        val decay = (ageMs.toDouble() / windowMs.toDouble()).coerceIn(0.0, 1.0)
        return (80.0 * (1.0 - decay)).toInt().coerceAtLeast(0)
    }

    /** 空查询默认序：作用域优先 → 顶层根在前 → 目录在前 → 新者在前。 */
    private val defaultOrdering: Comparator<Entry> = compareBy<Entry> { it.scope.order }
        .thenByDescending { isTopLevelScopeRoot(it) }
        .thenBy { it.isDirectory }
        .thenByDescending { it.modifiedAt }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 10L * 60 * 1000
        const val DEFAULT_MATCH_LIMIT = 100
        const val GLOBAL_SCAN_BUDGET = 5000
        const val MOUNT_SCAN_MAX_DEPTH = 3

        private val SKIP_DIR_NAMES = setOf(
            ".git", ".svn", ".hg",
            "node_modules", ".venv", "venv", "__pycache__",
            ".build", ".gradle", ".idea", ".tox",
            ".mypy_cache", ".pytest_cache", ".ruff_cache",
        )
    }
}
