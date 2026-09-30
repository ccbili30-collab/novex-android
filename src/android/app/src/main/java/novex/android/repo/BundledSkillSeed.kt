package novex.android.repo

import com.openminis.app.data.repository.SkillRepository

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 捆绑技能种子 —— 首启安装与后续升级的唯一种植口。
 *
 * 两类来源，一条纪律：
 *  - 内嵌的 skill-creator（正文常量在文件尾，内容与 iOS 版逐字一致，属
 *    冻结数据面，不随重构改动）；
 *  - assets/skills/ 下的目录技能（SKILL.md + 可选兄弟文件，FILES.txt 清单
 *    优先，缺清单时回退递归列举——部分运行时的 AssetManager.list() 对目录
 *    返回空数组，清单让拷贝在任何环境都确定）。
 *
 * 种植纪律（每条都对应过一个真实缺陷，勿简化）：
 *  1. 版本门槛：本地版本 ≥ 捆绑版本且技能目录非空 → 整体跳过。目录非空
 *     这一条件让「行在、文件丢」的半装状态在下次启动自愈而不必改版本号。
 *  2. 升级不覆盖用户数据：只改 description/version/body 与时间戳，不改
 *     启用位与使用计数；同名用户编辑过的兄弟文件不会被重写（见第 1 条
 *     的跳过条件）。
 *  3. 单技能失败不外溢：任何一个捆绑技能种植失败只记日志，绝不把异常
 *     抛进应用启动路径（GH#147 崩溃循环的教训）。
 */
internal class BundledSkillSeed(
    private val context: Context,
    private val repo: SkillRepository,
) {

    private val logTag = "NovexSkillSeed"

    /** assets 目录（相对 assets 根），与 app 打包内容一一对应。 */
    private val assetSkillDirs = listOf(
        "skills/wenyou-maker",
        "skills/card-organizer",
        "skills/humanizer-zh",
        "skills/human-writing",
    )

    fun plantAll() {
        plantEmbeddedCreator()
        for (dir in assetSkillDirs) {
            runCatching { plantAssetSkill(dir) }
                .onFailure { Log.w(logTag, "bundled skill '$dir' not planted: ${it.message}", it) }
        }
    }

    // ── 内嵌 skill-creator ──────────────────────────────────────────────

    private fun plantEmbeddedCreator() {
        val creatorId = "skill-creator"
        val creatorVersion = "2.0.0"
        val existing = repo.skills.value.firstOrNull { it.id == creatorId }
        if (existing != null && existing.version >= creatorVersion) {
            return // 本地已够新；assets 技能照常走 plantAll。
        }
        val parsed = SkillMarkdown.parse(SKILL_CREATOR_CONTENT) ?: return
        if (existing == null) {
            repo.add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                source = SkillRepository.ImportSource.BUNDLED,
            ) ?: return
            Log.i(logTag, "seeded bundled '$creatorId' v$creatorVersion")
            return
        }
        val upgraded = existing.copy(
            description = parsed.description,
            version = creatorVersion,
            body = parsed.body,
            updatedAt = System.currentTimeMillis(),
        )
        repo.registry.updateCoreFields(upgraded)
        repo.files.writeSkillMd(upgraded)
        repo.replaceInState(upgraded)
        Log.i(logTag, "upgraded bundled '$creatorId' to v$creatorVersion")
    }

    // ── assets 目录技能 ────────────────────────────────────────────────

    private fun plantAssetSkill(assetDir: String) {
        val text = context.assets.open("$assetDir/${SkillMarkdown.FILE_NAME}")
            .bufferedReader().use { it.readText() }
        val parsed = SkillMarkdown.parse(text) ?: run {
            Log.w(logTag, "bundled '$assetDir' has invalid ${SkillMarkdown.FILE_NAME}; skipped")
            return
        }
        val id = SkillMarkdown.slugOf(parsed.name)
        if (id.isBlank()) return
        val existing = repo.skills.value.firstOrNull { it.id == id }
        if (existing != null && existing.version >= parsed.version && repo.files.isPopulated(id)) {
            return // 已装且完好：不碰用户可能改过的文件。
        }
        if (existing == null) {
            repo.add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                version = parsed.version,
                source = SkillRepository.ImportSource.BUNDLED,
            ) ?: return
            Log.i(logTag, "seeded bundled '$id' v${parsed.version}")
        } else {
            val refreshed = existing.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
            )
            repo.registry.updateCoreFields(refreshed)
            repo.files.writeSkillMd(refreshed)
            repo.replaceInState(refreshed)
            Log.i(logTag, "upgraded bundled '$id' to v${parsed.version}")
        }
        copyAssetTree(assetDir, repo.files.dirOf(id))
    }

    /**
     * assets 目录 → 技能目录。FILES.txt（每行一个相对路径，# 注释）优先；
     * SKILL.md 由注册表路径统一写，清单与列举都跳过它。
     */
    private fun copyAssetTree(assetPath: String, dest: File) {
        dest.mkdirs()
        val manifest = runCatching {
            context.assets.open("$assetPath/FILES.txt").bufferedReader().use { reader ->
                reader.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        }.getOrNull()
        if (manifest != null) {
            for (relative in manifest) {
                if (relative.contains("..") || relative.startsWith("/")) continue
                if (relative.equals(SkillMarkdown.FILE_NAME, true) || relative.equals("FILES.txt", true)) continue
                copyAssetFile("$assetPath/$relative", File(dest, relative))
            }
            return
        }
        val children = context.assets.list(assetPath).orEmpty()
        for (child in children) {
            val childPath = "$assetPath/$child"
            val isFolder = context.assets.list(childPath).orEmpty().isNotEmpty()
            if (isFolder) {
                copyAssetTree(childPath, File(dest, child))
            } else if (!child.equals(SkillMarkdown.FILE_NAME, true)) {
                copyAssetFile(childPath, File(dest, child))
            }
        }
    }

    private fun copyAssetFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(logTag, "bundled file copy failed for $assetPath: ${e.message}")
        }
    }
}

/** 冻结数据面：与 iOS SkillStore.skillCreatorContent 逐字一致，勿改。 */
private val SKILL_CREATOR_CONTENT = """
---
name: skill-creator
version: 2.0.0
description: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Claude's capabilities with specialized knowledge, workflows, or tool integrations.
---

# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained packages that extend Claude's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks—they transform Claude from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Claude needs: system prompt, conversation history, other Skills' metadata, and the actual user request.

**Default assumption: Claude is already very smart.** Only add context Claude doesn't already have. Challenge each piece of information: "Does Claude really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile, consistency is critical, or a specific sequence must be followed.

### Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/       - Executable code
    ├── references/    - Documentation loaded as needed
    └── assets/        - Files used in output (templates, icons, etc.)
```

#### SKILL.md Frontmatter

- `name` (required): The skill name
- `description` (required): What the skill does and when to trigger it. Be comprehensive—this is the primary triggering mechanism.

#### SKILL.md Body

Instructions and guidance, loaded after the skill triggers. Keep under 500 lines; split into reference files when approaching this limit.

### Progressive Disclosure

Skills use three loading levels:
1. **Metadata** - Always in context (~100 words)
2. **SKILL.md body** - When skill triggers (<5k words)
3. **Bundled resources** - As needed (unlimited)

## Skill Creation Process

1. **Understand** the skill with concrete examples from the user
2. **Plan** reusable contents (scripts, references, assets)
3. **Create** the SKILL.md with proper frontmatter and instructions
4. **Test** by using the skill on real tasks
5. **Iterate** based on actual usage

### Writing the SKILL.md

- Use imperative/infinitive form
- `description` field should include all "when to use" triggers (body is loaded after triggering)
- Only add context Claude doesn't already have
- Prefer concise examples over verbose explanations
- Keep essential workflow in SKILL.md; move detailed reference material to separate files

### What NOT to Include

Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md, etc. The skill should only contain what an AI agent needs to do the job.
""".trimIndent()
