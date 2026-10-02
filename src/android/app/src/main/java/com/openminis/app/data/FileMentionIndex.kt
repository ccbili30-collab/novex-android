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
 * 聊天输入框的 @-提及文件索引（4.0 闭源收尾轮整体重组；路径拼装
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
 *
 * 本版组织（与前身直译版刻意不同）：走查参数收拢为 [WalkSpec] 单对象；
 * 条目构建集中到 [selfEntry] 工厂；BFS 走查与单层收集合并为单函数；
 * 缓存判据与扫描启动拆开（[rescan]）。
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

    private val store: MutableStateFlow<List<Entry>> = MutableStateFlow(emptyList())
    val entries: StateFlow<List<Entry>> = store.asStateFlow()

    private val scanFlag: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = scanFlag.asStateFlow()

    /** 缓存判据 + 取消令牌：换会话或 TTL 过期才重扫；旧扫描一键作废。 */
    private var cachedSessionId: String? = null
    private var cachedAt: Long = 0
    private var scanToken: UUID = UUID.randomUUID()
    private var scanJob: Job? = null

    /** 会话变了或缓存过期才重扫。幂等。 */
    fun refreshIfNeeded(sessionId: String) {
        val atMs = System.currentTimeMillis()
        val cacheUsable = cachedSessionId == sessionId &&
            atMs - cachedAt < cacheTtlMs &&
            store.value.isNotEmpty()
        if (cacheUsable) return
        cachedSessionId = sessionId
        cachedAt = atMs
        rescan(sessionId)
    }

    /** 无视缓存强制重扫（清 TTL 后走统一入口，缓存元数据同步刷新）。 */
    fun refresh(sessionId: String) {
        cachedAt = 0
        refreshIfNeeded(sessionId)
    }

    /** 换发扫描令牌、取消旧扫描并启动新扫描——两个刷新入口共用的收口。 */
    private fun rescan(sessionId: String) {
        val token = UUID.randomUUID()
        scanToken = token
        scanJob?.cancel()
        scanJob = scope.launch { runScan(sessionId, token) }
    }

    // ─── 扫描管线 ──────────────────────────────────────────────────────────

    /**
     * 三层依序扫描、逐层发布。收集桶跨层累积；发布时按 linuxPath 去重、
     * 默认序排序。令牌在每层与每个挂载前校验，被取代的扫描尽快停摆。
     */
    private suspend fun runScan(sessionId: String, token: UUID) {
        scanFlag.value = true
        val bucket = mutableListOf<Entry>()
        try {
            scanSessionLayers(sessionId, bucket, token)
            scanSharedLayers(bucket, token)
            scanMountLayers(bucket, token)
        } finally {
            scanFlag.value = false
        }
    }

    /** 第 1 层：会话本地两根，一次发布。 */
    private suspend fun scanSessionLayers(sessionId: String, bucket: MutableList<Entry>, token: UUID) {
        bucket += collectRooted(
            listOf(
                Scope.WORKSPACE to File(filesDir, "workspace/$sessionId"),
                Scope.ATTACHMENTS to File(filesDir, "attachments/$sessionId"),
            ),
        ) { scope -> "/var/minis/${scope.displayLabel}/$sessionId" }
        publish(token, bucket)
    }

    /** 第 2 层：共享三根，一次发布。 */
    private suspend fun scanSharedLayers(bucket: MutableList<Entry>, token: UUID) {
        bucket += collectRooted(
            listOf(
                Scope.SHARED to File(filesDir, "shared"),
                Scope.SKILLS to File(filesDir, "skills"),
                Scope.MEMORY to File(filesDir, "memory"),
            ),
        ) { scope -> "/var/minis/${scope.displayLabel}" }
        publish(token, bucket)
    }

    /**
     * 第 3 层：挂载逐个发布——一个慢挂载不挡其余。每个挂载先无条件注入
     * 自指条目（预算耗尽也保证 `@<name>` 可用），剩余预算再下钻。
     */
    private suspend fun scanMountLayers(bucket: MutableList<Entry>, token: UUID) {
        var roomLeft = GLOBAL_SCAN_BUDGET - bucket.size
        for (mnt in mountsProvider()) {
            if (scanToken != token) return
            // T219：与 PRoot bind 路径及 iOS 提示文案对齐——挂载在沙箱内
            // 位于 /var/minis/mounts/<name>，@-提及插入该精确路径，agent 的
            // 心智模型在提及、提示文案与 shell 三处保持一致。
            val mntRoot = "/var/minis/mounts/${mnt.name}"
            bucket += selfEntry(mntRoot, Scope.MOUNT, mnt.name, mnt.root.lastModified())
            if (roomLeft > 0) {
                val batch = walkBounded(WalkSpec(mnt.root, mntRoot, Scope.MOUNT, mnt.name, MOUNT_SCAN_MAX_DEPTH, roomLeft))
                bucket += batch
                roomLeft -= batch.size
            }
            publish(token, bucket)
        }
    }

    /**
     * 一组 (作用域, 目录) 根：每根先注入根自指（`@workspace` 可引用），再
     * 全量下钻。返回本组全部条目，由调用方并桶与发布。
     */
    private fun collectRooted(
        roots: List<Pair<Scope, File>>,
        pathOfRoot: (Scope) -> String,
    ): List<Entry> {
        val out = ArrayList<Entry>()
        for ((scope, folder) in roots) {
            if (!folder.isDirectory) continue
            val linuxRoot = pathOfRoot(scope)
            out += selfEntry(linuxRoot, scope, null, folder.lastModified())
            out += walkBounded(WalkSpec(folder, linuxRoot, scope, null, Int.MAX_VALUE, GLOBAL_SCAN_BUDGET))
        }
        return out
    }

    /** 目录自指条目（根/挂载根的 `@workspace`、`@<name>` 入口）。 */
    private fun selfEntry(path: String, scope: Scope, mountName: String?, stamp: Long) =
        Entry(path, scope, mountName, stamp, true)

    /** 一次预算封顶走查的全部参数。 */
    private class WalkSpec(
        val root: File,
        val linuxRoot: String,
        val scope: Scope,
        val mountName: String?,
        val maxDepth: Int,
        val budget: Int,
    )

    /**
     * 预算封顶的 BFS 目录走查（单函数：出队 → 列目录 → 逐子项产出/入
     * 队）：跳点文件、跳 SKIP_DIR_NAMES 目录；目录在深度限内入队继续下
     * 钻；产出条目的 linux 路径 = 根映射 + 相对路径。
     */
    private fun walkBounded(spec: WalkSpec): List<Entry> {
        val hits = ArrayList<Entry>()
        val rootAbs = spec.root.absolutePath
        val queue = ArrayDeque<Pair<File, Int>>().apply { addLast(spec.root to 0) }
        while (queue.isNotEmpty() && hits.size < spec.budget) {
            val (dir, depth) = queue.removeFirst()
            if (depth > spec.maxDepth) continue
            val children = dir.listFiles() ?: continue
            for (node in children) {
                if (hits.size >= spec.budget) break
                val hidden = node.name.startsWith(".")
                val skippedDir = node.isDirectory && node.name in SKIP_DIR_NAMES
                if (hidden || skippedDir) continue
                val relPath = node.absolutePath.removePrefix(rootAbs).removePrefix("/")
                if (relPath.isEmpty()) continue
                hits += Entry("${spec.linuxRoot}/$relPath", spec.scope, spec.mountName, node.lastModified(), node.isDirectory)
                val mayDescend = node.isDirectory && depth + 1 <= spec.maxDepth
                if (mayDescend) queue.addLast(node to depth + 1)
            }
        }
        return hits
    }

    /** 令牌双重校验后主线程落值：linuxPath 去重 + 默认序。 */
    private suspend fun publish(token: UUID, soFar: List<Entry>) {
        if (scanToken != token) return
        val ordered = soFar.associateBy { it.linuxPath }.values.sortedWith(defaultOrdering)
        withContext(Dispatchers.Main) {
            if (scanToken != token) return@withContext
            store.value = ordered
        }
    }

    // ─── 查询与评分 ────────────────────────────────────────────────────────

    /**
     * 按 [query] 排名。空查询直接取默认序的前 [limit] 条（[entries] 已排好）。
     * 档位/加权/并列裁决见类注释；并列时先作用域优先、再新者、再短路径
     * （更「近」的文件）。
     */
    fun matches(query: String, limit: Int = DEFAULT_MATCH_LIMIT): List<Entry> {
        val pool = store.value
        if (query.isBlank()) return pool.take(limit)
        val needle = query.lowercase()

        val atMs = System.currentTimeMillis()
        val recencyWindowMs = 56L * 24 * 60 * 60 * 1000 // 8 周 → 0..80

        val scored = ArrayList<Pair<Int, Entry>>(pool.size)
        for (entry in pool) {
            val tier = nameMatchTier(entry, needle)
            if (tier == 0) continue
            val secondary = entry.scope.rankBoost + topLevelRootBonus(entry) +
                recencyBonus(entry, atMs, recencyWindowMs)
            scored += (tier + secondary) to entry
        }
        val byRank = compareByDescending<Pair<Int, Entry>> { it.first }
            // 并列裁决：先作用域优先、再新者、再短路径（更「近」的文件）。
            .thenBy { (_, e) -> e.scope.order }
            .thenByDescending { (_, e) -> e.modifiedAt }
            .thenBy { (_, e) -> e.linuxPath.length }
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
     * 任意位子串之上。probe 始终指向「上一个分词符之后」的候选位。
     */
    private fun wordBoundaryHit(base: String, query: String): Boolean {
        if (query.isEmpty() || base.isEmpty()) return false
        var probe = 0
        for (idx in base.indices) {
            if (probe == idx && base.startsWith(query, idx)) return true
            val c = base[idx]
            if (c == '_' || c == '-' || c == ' ' || c == '.' || c == '/') probe = idx + 1
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
        val prefix = listOf("/var/minis/skills/", "/var/minis/mounts/", "/var/minis/shared/")
            .firstOrNull { entry.linuxPath.startsWith(it) } ?: return false
        val below = entry.linuxPath.removePrefix(prefix)
        // 仅第一层子级——不能再含 '/'。
        return below.isNotEmpty() && '/' !in below
    }

    /** 新近度微加成（≤80）：8 周线性衰减，其余全等时新者胜。 */
    private fun recencyBonus(entry: Entry, now: Long, windowMs: Long): Int {
        val ageMs = (now - entry.modifiedAt).coerceAtLeast(0L)
        val decay = (ageMs.toDouble() / windowMs.toDouble()).coerceIn(0.0, 1.0)
        return (80.0 * (1.0 - decay)).toInt().coerceAtLeast(0)
    }

    /** 空查询默认序：作用域优先 → 顶层根在前 → 目录在前 → 新者在前。 */
    private val defaultOrdering: Comparator<Entry> = compareBy<Entry> { e -> e.scope.order }
        .thenByDescending { e -> isTopLevelScopeRoot(e) }
        .thenBy { e -> e.isDirectory }
        .thenByDescending { e -> e.modifiedAt }

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
