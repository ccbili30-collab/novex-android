package com.openminis.app.data.repository

import android.content.Context
import android.util.Log

import novex.android.repo.BundledSkillSeed
import novex.android.repo.SkillArchives
import novex.android.repo.SkillFileStore
import novex.android.repo.SkillMarkdown
import novex.android.repo.SkillRegistry
import novex.android.repo.SkillSyncClient
import novex.android.repo.GitHubLocator
import novex.android.repo.ArchiveEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * 技能仓库门面 —— 对外只回答两件事：「现在有哪些技能」「怎么进出技能」。
 *
 * 结构（P3.5a 重写后的职责划分）：
 *  - [SkillFileStore] / [SkillRegistry]：磁盘目录与 skills.db 的唯一出口；
 *  - [SkillMarkdown]：SKILL.md 编解码与虚拟路径换算（纯函数）；
 *  - [SkillArchives] / [GitHubLocator] / [SkillSyncClient]：zip 与 GitHub 搬运；
 *  - [BundledSkillSeed]：捆绑技能的种植与升级。
 *
 * 门面持有状态流（[skills]）并对注册表 + 磁盘做双写；所有改动先落库再改
 * 内存，保证冷启动读到的是同一份事实。对模型侧的唯一出口是
 * [skillPromptFragment]，其文本是提示协议，逐字冻结。
 */
class SkillRepository(context: Context) {

    /** 技能来源四分类；[value] 是 skills.db import_source 列的存值。 */
    enum class ImportSource(val value: String) {
        URL("url"),
        FILE("file"),
        BUNDLED("bundled"),
        SESSION("session");

        companion object {
            fun from(value: String): ImportSource =
                entries.firstOrNull { it.value == value } ?: FILE
        }
    }

    /**
     * 一个技能的完整视图。id 即目录名（slug），是磁盘、注册表、模型提示
     * 三方共用的主键，创建后不可变。
     */
    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val version: String = "1.0.0",
        val importSource: ImportSource = ImportSource.FILE,
        val isEnabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val body: String = "",
        /** 仅 URL 导入有值：原始 GitHub 地址，供「检查更新」回源。 */
        val sourceURL: String? = null,
        /** SKILL.md 累计读取次数；越过阈值后整体缩放到 0–100。 */
        val useCount: Double = 0.0,
    )

    /** UI 展示用的粗粒度使用频段（相对全体技能的最大值）。 */
    enum class UsageFrequency { NEVER, LOW, REGULAR, HIGH }

    /** GitHub 地址解析结果（user/repo/branch + 目录路径，不含 SKILL.md 叶子）。 */
    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        val dirPath: String,
    )

    /** [updateFromURL] 的三种结局；PartialSuccess 携带「能用但缺资源」的原因。 */
    sealed class UpdateResult {
        data class Success(val skill: Skill) : UpdateResult()
        data class PartialSuccess(val skill: Skill, val reason: String) : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
    )

    private val tag = "NovexSkillRepo"

    internal val registry = SkillRegistry(context)
    internal val files = SkillFileStore(context)
    private val context = context.applicationContext
    private val sync = SkillSyncClient()

    /**
     * 仓库自有的后台作用域：SKILL.md 导入成功后的兄弟文件下载在这里跑，
     * 与调用方（导入屏 / ViewModel）的生命周期解耦 —— 调用方提前退出不应
     * 中断下载（那会留下只有 SKILL.md 的半成品技能）。SupervisorJob 让
     * 一次下载失败不污染下一次。
     */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    init {
        // 构造在 Application.onCreate 内联执行：任何逃逸异常都会让
        // Application 永久残废并进入崩溃循环（GH#147）。装载与种植各自
        // 兜底 —— 最坏损失部分技能，而不是应用起不来。
        runCatching { reloadFromStorage() }.onFailure {
            Log.e(tag, "skill store reload failed; serving ${_skills.value.size} skill(s): ${it.message}", it)
        }
        runCatching { BundledSkillSeed(context, this).plantAll() }.onFailure {
            Log.e(tag, "bundled skill seeding failed; continuing anyway: ${it.message}", it)
        }
    }

    // ── 增删改 ─────────────────────────────────────────────────────────

    /**
     * 新建技能。id 由名称 slug 化而来：空 slug（纯非 ASCII 名）或撞已有
     * id 时返回 null，由 UI 决定如何提示。
     */
    fun add(
        name: String,
        description: String,
        body: String,
        version: String = "1.0.0",
        source: ImportSource = ImportSource.FILE,
        sourceURL: String? = null,
    ): Skill? {
        val id = SkillMarkdown.slugOf(name)
        if (id.isBlank()) return null
        if (_skills.value.any { it.id == id }) return null
        val skill = Skill(
            id = id,
            name = name,
            description = description,
            version = version,
            importSource = source,
            body = body,
            sourceURL = sourceURL,
        )
        registry.insert(skill)
        files.writeSkillMd(skill)
        publishAppend(skill)
        Log.i(tag, "skill added: ${skill.id}")
        return skill
    }

    /** 就地更新名称/描述/正文（null 参数 = 保持原值）。 */
    fun update(id: String, name: String? = null, description: String? = null, body: String? = null): Boolean {
        val current = _skills.value.firstOrNull { it.id == id } ?: return false
        val updated = current.copy(
            name = name ?: current.name,
            description = description ?: current.description,
            body = body ?: current.body,
            updatedAt = System.currentTimeMillis(),
        )
        registry.updateCoreFields(updated)
        files.writeSkillMd(updated)
        replaceInState(updated)
        return true
    }

    fun delete(id: String) {
        registry.delete(id)
        files.wipe(id)
        _skills.value = _skills.value.filterNot { it.id == id }
        Log.i(tag, "skill deleted: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        registry.setEnabled(id, enabled)
        mutateInState(id) { it.copy(isEnabled = enabled) }
    }

    // ── 会话级开关 ─────────────────────────────────────────────────────

    /** 会话开关优先于全局启用位；无覆盖行时回落全局。 */
    fun isEnabledForSession(skillId: String, sessionId: String): Boolean =
        registry.sessionOverrideOf(sessionId, skillId)
            ?: _skills.value.firstOrNull { it.id == skillId }?.isEnabled
            ?: false

    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        registry.upsertSessionOverride(sessionId, skillId, enabled)
    }

    fun clearSessionOverrides(sessionId: String) {
        registry.dropSessionOverrides(sessionId)
    }

    /**
     * 草稿会话 id（如 `__new__<uuid>`）的覆盖行改挂到落库正式 id 上。
     * ensureSession 建行后必须调用，否则首条消息前的技能开关会绑死在
     * 草稿键上，下次进聊天即「失效」。
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        registry.retargetSessionOverrides(fromDraft, toReal)
    }

    // ── 模型提示片段 ───────────────────────────────────────────────────

    /**
     * 生成系统提示里的技能披露片段。技能总量超过上限时按三层优先级挑选：
     * 捆绑 > 七天内更新 > 使用最多；未入选的技能以名单形式给出并指路
     * /var/minis/skills/ 让模型自行 grep。片段文本是提示协议，逐字冻结。
     * 会话无可用技能时返回 null（不注入）。
     */
    fun skillPromptFragment(sessionId: String): String? {
        val active = _skills.value.filter { isEnabledForSession(it.id, sessionId) }
        if (active.isEmpty()) return null
        val disclosed = if (active.size <= PROMPT_SKILL_LIMIT) {
            active.sortedByDescending { it.updatedAt }
        } else {
            pickPromptSkills(active)
        }
        val hasMore = disclosed.size < active.size
        val xml = buildString {
            append("<available_skills>\n")
            for (skill in disclosed) {
                val description = skill.description.truncateWithEllipsis(PROMPT_DESC_LIMIT)
                append("  <skill>\n")
                append("    <name>").append(SkillMarkdown.escapeXml(skill.name)).append("</name>\n")
                append("    <description>").append(SkillMarkdown.escapeXml(description)).append("</description>\n")
                append("    <path>").append(SkillMarkdown.virtualPath(skill.id)).append("</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }
        return buildString {
            append("Skills:\n")
            append(
                "Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. " +
                    "Load a skill by calling the file_read tool with the skill's <path> below " +
                    "(workspace tools cannot read these files).\n\n",
            )
            append(xml)
            if (hasMore) {
                val disclosedIds = disclosed.mapTo(HashSet(disclosed.size)) { it.id }
                val omitted = active.filter { it.id !in disclosedIds }
                val nameBudget = (100 - disclosed.size).coerceAtLeast(0)
                val names = omitted.take(nameBudget).joinToString(", ") { it.name }
                append("\n\n")
                append(omitted.size).append(" more skills not shown above: ").append(names)
                append(". List /var/minis/skills/ or grep to search all.")
            }
        }
    }

    private fun pickPromptSkills(active: List<Skill>): List<Skill> {
        val picked = LinkedHashMap<String, Skill>() // 保序 + 按 id 去重
        active.filter { it.importSource == ImportSource.BUNDLED }
            .forEach { picked.putIfAbsent(it.id, it) }
        val recentCutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val recentBudget = minOf(RECENT_SLOTS, PROMPT_SKILL_LIMIT - picked.size).coerceAtLeast(0)
        active.asSequence()
            .filter { it.updatedAt > recentCutoff && it.id !in picked }
            .sortedByDescending { it.updatedAt }
            .take(recentBudget)
            .forEach { picked.putIfAbsent(it.id, it) }
        active.asSequence()
            .filter { it.id !in picked }
            .sortedByDescending { it.useCount }
            .take((PROMPT_SKILL_LIMIT - picked.size).coerceAtLeast(0))
            .forEach { picked.putIfAbsent(it.id, it) }
        return picked.values.toList()
    }

    // ── 使用统计 ───────────────────────────────────────────────────────

    /**
     * 记一次 SKILL.md 读取。任何技能越过 [USE_COUNT_CEILING] 后，全体计数
     * 按最大值缩放到 0–100，长装机用户不会看到几千次的畸形读数。
     */
    fun recordSkillUse(skillId: String) {
        val current = _skills.value.firstOrNull { it.id == skillId } ?: return
        val bumped = current.copy(useCount = current.useCount + 1.0)
        registry.bumpUseCount(skillId)
        var next = _skills.value.map { if (it.id == skillId) bumped else it }
        if (bumped.useCount > USE_COUNT_CEILING) {
            val maxCount = next.maxOfOrNull { it.useCount } ?: 0.0
            if (maxCount > 0.0) {
                next = next.map { it.copy(useCount = it.useCount / maxCount * 100.0) }
                registry.rewriteUseCounts(next)
            }
        }
        _skills.value = next
    }

    fun usageFrequency(skillId: String): UsageFrequency {
        val skill = _skills.value.firstOrNull { it.id == skillId } ?: return UsageFrequency.NEVER
        val maxCount = _skills.value.maxOfOrNull { it.useCount } ?: 0.0
        if (skill.useCount <= 0.0 || maxCount <= 0.0) return UsageFrequency.NEVER
        val ratio = skill.useCount / maxCount * 100.0
        return when {
            ratio < 20.0 -> UsageFrequency.LOW
            ratio < 60.0 -> UsageFrequency.REGULAR
            else -> UsageFrequency.HIGH
        }
    }

    /** 模型读了 `/var/minis/skills/<id>/SKILL.md` 才计入使用；子资源读取不算。 */
    fun skillIdFromPath(path: String): String? {
        val candidate = SkillMarkdown.skillIdOfVirtualPath(path) ?: return null
        return if (_skills.value.any { it.id == candidate }) candidate else null
    }

    // ── 导入：SKILL.md 文本 / zip / GitHub ─────────────────────────────

    /**
     * 解析并导入一份 SKILL.md 文本。同 id（同名 slug）已存在时原位替换，
     * 保留启用位与使用计数 —— URL 重复导入即「更新」，不产生副本。
     */
    fun importFromContent(
        content: String,
        source: ImportSource = ImportSource.FILE,
        sourceURL: String? = null,
    ): Skill? {
        val parsed = SkillMarkdown.parse(content) ?: return null
        val id = SkillMarkdown.slugOf(parsed.name)
        val existing = _skills.value.firstOrNull { it.id == id }
        if (existing != null) {
            val replaced = existing.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                importSource = source,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
                sourceURL = sourceURL ?: existing.sourceURL,
            )
            registry.updateWithOrigin(replaced)
            files.writeSkillMd(replaced)
            replaceInState(replaced)
            return replaced
        }
        return add(
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            version = parsed.version,
            source = source,
            sourceURL = sourceURL,
        )
    }

    /** UI 展示用的虚拟路径（模型侧读取入口）。 */
    fun skillMdPath(id: String): String = SkillMarkdown.virtualPath(id)

    /**
     * 从 zip 导入。SKILL.md 允许在包根或一层子目录；兄弟文件解到技能目录，
     * 带 `..` 的路径一律丢弃（zip-slip 防护）。
     */
    fun importFromArchive(input: InputStream): Skill? {
        val entries = try {
            SkillArchives.drain(input)
        } catch (e: Exception) {
            Log.w(tag, "skill archive unreadable: ${e.message}")
            return null
        }
        if (entries.isEmpty()) return null
        val skillMdEntry = SkillArchives.locateSkillMd(entries) ?: return null
        val text = try {
            String(skillMdEntry.bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (text.isBlank()) return null
        val skill = importFromContent(text, ImportSource.FILE) ?: return null
        unpackSiblings(entries, skillMdEntry.name, skill.id)
        return skill
    }

    private fun unpackSiblings(entries: List<ArchiveEntry>, skillMdName: String, skillId: String) {
        val prefix = if (skillMdName == SkillMarkdown.FILE_NAME) {
            ""
        } else {
            skillMdName.dropLast(SkillMarkdown.FILE_NAME.length)
        }
        val skillDir = files.dirOf(skillId)
        skillDir.mkdirs()
        for (entry in entries) {
            if (entry.isDirectory) continue
            var relative = entry.name
            if (prefix.isNotEmpty() && relative.startsWith(prefix)) relative = relative.drop(prefix.length)
            if (relative.isEmpty() || relative == SkillMarkdown.FILE_NAME || relative.startsWith(".")) continue
            if (relative.contains("..")) continue
            val dest = File(skillDir, relative)
            dest.parentFile?.mkdirs()
            try {
                dest.writeBytes(entry.bytes)
            } catch (e: Exception) {
                Log.w(tag, "sibling '$relative' not extracted: ${e.message}")
            }
        }
    }

    /**
     * 从 GitHub 地址导入：先取 SKILL.md 落库（同步，导入方需要结果），
     * 兄弟文件在后台镜像（异步，不随导入屏销毁而中断）。
     */
    suspend fun importFromGitHub(urlString: String): Skill? = withContext(Dispatchers.IO) {
        val text = GitHubLocator.rawSkillMdUrl(urlString)?.let { sync.fetchText(it) }
            ?: return@withContext null
        val skill = importFromContent(text, ImportSource.URL, sourceURL = urlString)
            ?: return@withContext null
        val target = GitHubLocator.parse(urlString) ?: return@withContext skill
        detachedScope.launch {
            val outcome = sync.mirrorDirectory(target, files.dirOf(skill.id), skipRootSkillMd = true)
            stampRefreshed(skill.id)
            if (!outcome.complete) {
                Log.w(
                    tag,
                    "github import incomplete for ${skill.id}: " +
                        "${outcome.filesFailed} file(s) failed; ${outcome.firstReason ?: ""}",
                )
            }
        }
        skill
    }

    /**
     * 对 URL 来源的既有技能做「检查更新」：重取 SKILL.md 原位更新，并同步
     * 重下兄弟文件。与导入不同，此处调用方盯着进度条，兄弟文件同步等待完成，
     * 结果以 [UpdateResult] 三态返回，PartialSuccess 说明「技能可用但资源
     * 可能不齐」。
     */
    suspend fun updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
        val existing = _skills.value.firstOrNull { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill not found")
        if (existing.importSource != ImportSource.URL) {
            return@withContext UpdateResult.Failure("Skill was not imported from a URL")
        }
        val sourceUrl = existing.sourceURL
        if (sourceUrl.isNullOrBlank()) {
            // 历史导入没存源地址：只能让用户重导一次（重导会补上地址）。
            return@withContext UpdateResult.Failure(
                "No source URL on file. Re-import this skill from Minis Skills to enable updates.",
            )
        }
        val rawUrl = GitHubLocator.rawSkillMdUrl(sourceUrl)
            ?: return@withContext UpdateResult.Failure("Could not build a raw download URL from $sourceUrl")
        val text = try {
            sync.fetchTextStrict(rawUrl)
        } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
        if (text == null) {
            return@withContext UpdateResult.Failure("Download failed (HTTP error) for $rawUrl")
        }
        val parsed = SkillMarkdown.parse(text)
            ?: return@withContext UpdateResult.Failure(
                "Downloaded SKILL.md is not valid (missing YAML frontmatter or 'name:' field)",
            )
        if (!update(skillId, name = parsed.name, description = parsed.description, body = parsed.body)) {
            return@withContext UpdateResult.Failure("Failed to write updated skill")
        }
        val target = GitHubLocator.parse(sourceUrl)
        var walk: SkillSyncClient.WalkOutcome? = null
        if (target != null) {
            walk = sync.mirrorDirectory(target, files.dirOf(skillId), skipRootSkillMd = true)
        }
        val fresh = _skills.value.firstOrNull { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        val outcome = walk
        if (outcome != null && !outcome.complete) {
            val reason = outcome.firstReason ?: "${outcome.filesFailed} sibling file(s) failed to download"
            return@withContext UpdateResult.PartialSuccess(fresh, reason)
        }
        UpdateResult.Success(fresh)
    }

    // ── 导出与文件访问 ─────────────────────────────────────────────────

    /**
     * 把技能目录打成 zip 供分享。三个保险（每个都对应过一个真实缺陷）：
     *  1. 每次导出用独立目录（share/skill-export-<uuid>/）：连续分享不能
     *     覆盖上一次还在被读取的包；
     *  2. 返回前校验非空：不产出谁也导不回的空包；
     *  3. 导出前清扫超过 TTL 的旧目录：分寸是「下次导出时」而非分享面板
     *     关闭时 —— 那时消费者早已读完。
     * 目录根必须是 FileProvider 已声明的 cacheDir/share。
     */
    fun exportSkillToZip(skillId: String): File? {
        val skillDir = files.dirOf(skillId)
        if (!skillDir.isDirectory) {
            Log.w(tag, "export: no directory for $skillId")
            return null
        }
        val relPaths = files.listRelativeFiles(skillId)
        if (relPaths.isEmpty()) {
            Log.w(tag, "export: nothing to export for $skillId")
            return null
        }
        sweepAgedExports()
        val exportDir = File(File(context.cacheDir, "share"), "skill-export-${UUID.randomUUID()}")
        if (!exportDir.mkdirs() && !exportDir.isDirectory) {
            Log.w(tag, "export: cannot create $exportDir")
            return null
        }
        // 文件系统安全的单段文件名（对齐 iOS 的清洗）。
        val safeLabel = (_skills.value.firstOrNull { it.id == skillId }?.name ?: skillId)
            .replace('/', '-').replace(':', '-').replace('\\', '-')
            .trim().ifEmpty { "skill" }
        val zipFile = File(exportDir, "$safeLabel.zip")
        val packed = SkillArchives.pack(relPaths.associateWith { File(skillDir, it) }, zipFile)
        if (!packed) {
            zipFile.delete()
            Log.w(tag, "export: zip for $skillId came out empty or failed")
            return null
        }
        Log.i(tag, "export: $skillId -> ${zipFile.name} (${zipFile.length()}B, ${relPaths.size} files)")
        return zipFile
    }

    private fun sweepAgedExports() {
        val shareRoot = File(context.cacheDir, "share")
        val now = System.currentTimeMillis()
        val stale = shareRoot.listFiles() ?: return
        for (dir in stale) {
            if (!dir.isDirectory || !dir.name.startsWith("skill-export-")) continue
            if (now - dir.lastModified() < EXPORT_TTL_MS) continue
            runCatching { dir.deleteRecursively() }
                .onFailure { Log.w(tag, "sweep: '${dir.name}' not removed") }
        }
    }

    fun listSkillFiles(skillId: String): List<String> = files.listRelativeFiles(skillId)

    fun skillFileHostPath(skillId: String, relativePath: String): String =
        files.hostPath(skillId, relativePath)

    fun readSkillFile(skillId: String, relativePath: String): String? =
        files.readText(skillId, relativePath)

    fun writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
        val written = files.writeText(skillId, relativePath, content)
        // SKILL.md 的手工改动必须回流注册表（名称/描述/版本/正文）。
        if (written && relativePath.equals(SkillMarkdown.FILE_NAME, true)) rescanFromDisk(skillId)
        return written
    }

    // ── 对账：磁盘 → 注册表 ────────────────────────────────────────────

    /**
     * 重读磁盘上的 SKILL.md 并回写注册表。文件已不存在时按孤儿处理：
     * 删行（BUNDLED 例外，其文件由种子种植器恢复）。返回刷新后的技能。
     */
    fun rescanFromDisk(skillId: String): Skill? {
        val current = _skills.value.firstOrNull { it.id == skillId } ?: return null
        val file = files.skillMdOf(skillId)
        if (!file.exists()) {
            if (current.importSource != ImportSource.BUNDLED) {
                delete(skillId)
                Log.i(tag, "rescan: SKILL.md missing, orphan row dropped: $skillId")
            }
            return null
        }
        val parsed = SkillMarkdown.parse(file.readText()) ?: return null
        val refreshed = current.copy(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            body = parsed.body,
            updatedAt = System.currentTimeMillis(),
        )
        registry.updateCoreFields(refreshed)
        replaceInState(refreshed)
        return refreshed
    }

    /**
     * 改名：只改 frontmatter 的 name 行、注册表与内存，id（即目录名与所有
     * 引用锚点）保持不动 —— 既有会话里的技能引用才不会断。
     */
    fun renameSkill(skillId: String, newName: String): Boolean {
        val current = _skills.value.firstOrNull { it.id == skillId } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val file = files.skillMdOf(skillId)
        if (file.exists()) {
            val original = file.readText()
            val rewritten = original.replaceFirst(Regex("(?m)^name:\\s*.*$"), "name: $trimmed")
            if (rewritten != original) file.writeText(rewritten)
        }
        val refreshed = current.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        registry.rename(skillId, trimmed, refreshed.updatedAt)
        replaceInState(refreshed)
        return true
    }

    /** 重扫 skills 目录与注册表并重新发布状态流（技能屏进入、agent 落盘新技能后调用）。 */
    fun reloadFromDisk() {
        reloadFromStorage()
    }

    /** 「从文件更新」入口复用的公开解析器；null 即不是合法 SKILL.md。 */
    fun parseSkillMdPublic(content: String): ParsedSkill? = SkillMarkdown.parse(content)

    // ── 内部：装载与状态维护 ───────────────────────────────────────────

    private fun reloadFromStorage() {
        val restored = ArrayList<Skill>()
        for (row in registry.selectAll()) {
            // 孤儿行修剪：agent 常用 rm -rf 删技能，注册表不知道。BUNDLED
            // 例外 —— 首启装载先于种子种植，捆绑行的文件此刻还不存在。
            if (row.importSource != ImportSource.BUNDLED && !files.skillMdOf(row.id).exists()) {
                registry.delete(row.id)
                Log.i(tag, "orphan skill row pruned (no SKILL.md on disk): ${row.id}")
                continue
            }
            var skill = Skill(
                id = row.id,
                name = row.name,
                description = row.description,
                version = row.version,
                importSource = row.importSource,
                isEnabled = row.isEnabled,
                installedAt = row.installedAt,
                updatedAt = row.updatedAt,
                sourceURL = row.sourceUrl,
                useCount = row.useCount,
                body = files.readBody(row.id),
            )
            // 陈旧描述自愈：占位标记（">"/"|"）与空串都算坏值。只允许
            // 「坏值 → 磁盘真值」的单向修补；解析失败/仍为空时不动行，
            // 防止把半写入状态的空值回写覆盖好数据（GH#215）。
            if (skill.description == ">" || skill.description == "|" || skill.description.isBlank()) {
                val onDisk = runCatching { files.skillMdOf(row.id).readText() }.getOrNull()
                val reparsed = onDisk?.let(SkillMarkdown::parse)
                if (reparsed != null && reparsed.description.isNotBlank()) {
                    skill = skill.copy(
                        description = reparsed.description,
                        name = skill.name.ifBlank { reparsed.name },
                        version = skill.version.ifBlank { reparsed.version },
                        updatedAt = System.currentTimeMillis(),
                    )
                    registry.updateCoreFields(skill)
                    Log.i(tag, "stale description self-healed for ${row.id}")
                }
            }
            restored.add(skill)
        }
        // 自动发现：磁盘有 SKILL.md、注册表无行 → 以 SESSION 来源登记。
        val knownIds = restored.mapTo(HashSet(restored.size)) { it.id }
        for (dirId in files.installedDirIds()) {
            if (dirId in knownIds) continue
            val parsed = runCatching { files.skillMdOf(dirId).readText() }.getOrNull()
                ?.let(SkillMarkdown::parse) ?: continue
            val discovered = Skill(
                id = dirId,
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                importSource = ImportSource.SESSION,
            )
            registry.insert(discovered)
            restored.add(discovered)
            Log.i(tag, "skill auto-discovered from disk: $dirId")
        }
        _skills.value = restored
    }

    private fun stampRefreshed(skillId: String) {
        val now = System.currentTimeMillis()
        registry.touch(skillId, now)
        mutateInState(skillId) { it.copy(updatedAt = now) }
    }

    internal fun replaceInState(skill: Skill) {
        _skills.value = _skills.value.map { if (it.id == skill.id) skill else it }
    }

    private fun mutateInState(skillId: String, change: (Skill) -> Skill) {
        _skills.value = _skills.value.map { if (it.id == skillId) change(it) else it }
    }

    private fun publishAppend(skill: Skill) {
        _skills.value = _skills.value + skill
    }

    private fun String.truncateWithEllipsis(limit: Int): String =
        if (length <= limit) this else take(limit) + "…"

    private companion object {
        const val PROMPT_SKILL_LIMIT = 20
        const val PROMPT_DESC_LIMIT = 200
        const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        const val RECENT_SLOTS = 10
        const val USE_COUNT_CEILING = 1000.0

        /** 导出包在磁盘上的保留期（对齐 iOS 的 24h）。 */
        const val EXPORT_TTL_MS = 24L * 3600 * 1000
    }
}
