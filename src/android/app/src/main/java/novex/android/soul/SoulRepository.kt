package novex.android.soul

import android.content.Context
import com.openminis.app.agent.SoulBodyLimitCheck
import novex.android.logkit.RunLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SOUL.md 持久化仓库（P3.5c 自 agent/SoulStore 真重写）。
 *
 * 落点与迁移账本（冻结面）：
 *  - 文件 `<filesDir>/minis-global/memory/SOUL.md`，与 GLOBAL.md、每日
 *    记忆日志同目录；
 *  - 一次性改名迁移的账本记在 prefs `soul_migrations` 的
 *    `assistant_name_nova_v1` 键上（跑过一次后用户可自由改名，含改回）；
 *  - 缺省内容 [DEFAULT_CONTENT] 逐字冻结（首跑播种与设置页“恢复缺省”
 *    共用）；[LEGACY_NOVEX_DEFAULT_BODY] 识别旧出厂正文以便把其风格
 *    映射到现行缺省——只认出厂投影，用户改过的一字不动。
 *
 * 方法按 Context 收参而非挂全局单例：依赖面显式，测试与预览也能调。
 */
object SoulRepository {

    private const val CATEGORY = "SoulRepository"
    private const val FILE_NAME = "SOUL.md"
    private const val MEMORY_SUBDIR = "minis-global/memory"
    private const val MIGRATION_PREFS = "soul_migrations"
    private const val NOVA_NAME_MIGRATION_KEY = "assistant_name_nova_v1"

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    // ── 正文长度规则（按语言分轴，冻结面）────────────────────────────
    //
    // 写入面与装配面共用同一套硬上限：CJK 按字符计（信息密度高），
    // 拉丁/混合按词计。CJK 占比超 30% 判入 CJK 轴——这个水位高到能忽略
    // 英文文档里的零星中文引号与专有名词，低到多数中文段夹几个英文词
    // 仍算中文。（两轴现值 1,000,000 = 实际放开；UI 报数仍读这两枚常量。）

    const val CJK_RATIO_THRESHOLD: Double = 0.3
    const val CHINESE_CHAR_LIMIT: Int = 1_000_000
    const val ENGLISH_WORD_LIMIT: Int = 1_000_000

    /**
     * 判定 [body] 落在哪根轴上：空/纯空白恒为 Ok。CJK 轴按码点计（对
     * 关注的区间而言最接近 iOS 的字素计数——组合符与旗帜 emoji 在
     * 汉字/假名/谚文里不出现；按字节或 UTF-16 单元会把代理对扩展区
     * 算重）。拉丁轴按空白分词。判定类型正典在旧路径
     * （com.openminis.app.agent.SoulBodyLimitCheck，UI 钉形）。
     */
    fun isOverLimit(body: String): SoulBodyLimitCheck {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return SoulBodyLimitCheck.Ok

        val cjkCount = trimmed.codePoints().filter(::isCJKCodePoint).count()
        val ratio = cjkCount.toDouble() / trimmed.codePointCount(0, trimmed.length)
        return if (ratio > CJK_RATIO_THRESHOLD) {
            val chars = trimmed.codePointCount(0, trimmed.length)
            if (chars > CHINESE_CHAR_LIMIT) {
                SoulBodyLimitCheck.OverLimitChinese(chars, CHINESE_CHAR_LIMIT)
            } else {
                SoulBodyLimitCheck.Ok
            }
        } else {
            val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words > ENGLISH_WORD_LIMIT) {
                SoulBodyLimitCheck.OverLimitEnglish(words, ENGLISH_WORD_LIMIT)
            } else {
                SoulBodyLimitCheck.Ok
            }
        }
    }

    /** 汉字（含扩展 A–H）、假名、谚文各区间——比只查 U+4E00–U+9FFF 宽，
     *  扩展区与谚文都计入占比。 */
    private fun isCJKCodePoint(cp: Int): Boolean = when (cp) {
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF,        // CJK 统一表意 + 扩 A
        in 0x20000..0x2A6DF, in 0x2A700..0x2EBEF,    // 扩 B..F, G/H
        in 0x30000..0x323AF,                          // 扩兼容区
        in 0x3040..0x309F,                            // 平假名
        in 0x30A0..0x30FF, in 0x31F0..0x31FF,        // 片假名 + 语音扩展
        in 0xAC00..0xD7AF,                            // 谚文音节
        in 0x1100..0x11FF, in 0x3130..0x318F,        // 谚文字母 + 兼容字母
        -> true
        else -> false
    }

    // ── 缺省内容（逐字冻结）──────────────────────────────────────────

    /** 旧出厂正文（识别用）。保存过的用户原文永不匹配、永不改写。 */
    internal val LEGACY_NOVEX_DEFAULT_BODY = """
直接服务当前文游，不说“好的”“当然可以”之类的空话。

保持具体判断，不把用户的独特设定改写成平均化、可互换的套路。

尊重用户指定的文风。用户可以在这里补充例如：“你是一位文笔细腻、善于写人物关系的作家。”
""".trimIndent()

    /** 仅把「一字未改的出厂旧正文」映射到现行缺省正文；其余原样返回。 */
    internal fun currentDefaultStyle(body: String): String =
        if (body.trim() == LEGACY_NOVEX_DEFAULT_BODY) {
            SoulFrontmatterCodec.parse(DEFAULT_CONTENT).body.trim()
        } else {
            body
        }

    val DEFAULT_CONTENT: String = """---
name: "Nova"
style: ""
lang: "auto"
---

直接回应用户当前的请求，不说“好的”“当然可以”之类的空话。

保持具体判断，不把用户的独特设定改写成平均化、可互换的套路。

尊重用户指定的文风。用户可以在这里补充例如：“你是一位文笔细腻、善于写人物关系的作家。”
"""

    // ── 读写与迁移 ─────────────────────────────────────────────────────

    /**
     * SOUL.md 不存在则以缺省内容播种；已存在则先跑一次性的 Nova 改名
     * 迁移。每次启动调用都安全——绝不覆盖用户编辑。
     */
    fun ensureExists(context: Context) {
        val file = fileLocation(context)
        if (file.exists()) {
            runLegacyNameMigration(context, file)
            return
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONTENT)
            RunLog.info(CATEGORY, "seeded SOUL.md at ${file.absolutePath}")
        } catch (t: Throwable) {
            RunLog.warning(CATEGORY, "ensureExists failed: ${t.message}")
        }
    }

    private fun runLegacyNameMigration(context: Context, file: File) {
        val ledger = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (ledger.getBoolean(NOVA_NAME_MIGRATION_KEY, false)) return

        runCatching {
            val existing = file.readText()
            val migrated = migrateLegacyAssistantName(existing)
            if (migrated != existing) file.writeText(migrated)
        }.onFailure {
            RunLog.warning(CATEGORY, "SOUL.md Nova name migration failed: ${it.message}")
        }.onSuccess {
            // 只跑一次：此后用户可自由改名（含改回历史名）。
            ledger.edit().putBoolean(NOVA_NAME_MIGRATION_KEY, true).apply()
        }
    }

    /** 读 + 解析；文件缺失或读失败返回 null（调用方自行回落缺省）。 */
    fun load(context: Context): SoulDocument? {
        val file = fileLocation(context)
        if (!file.exists()) return null
        return try {
            SoulFrontmatterCodec.parse(file.readText())
        } catch (t: Throwable) {
            RunLog.warning(CATEGORY, "SOUL.md load failed: ${t.message}")
            null
        }
    }

    /** 原子写：先写 `.tmp` 同目录兄弟文件再改名；改名被拒则复制兜底。 */
    fun save(context: Context, document: SoulDocument) {
        val target = fileLocation(context)
        target.parentFile?.mkdirs()
        val text = SoulFrontmatterCodec.serialize(document)
        val staging = File(target.parentFile, "${target.name}.tmp")
        staging.writeText(text)
        if (!staging.renameTo(target)) {
            // 个别文件系统拒绝跨 inode 改名（filesDir 内不应发生，防御性
            // 兜底——避免留下陈旧 .tmp）。
            target.writeText(text)
            staging.delete()
        }
        cachedIdentity.value = document.metadata
    }

    // ── 身份缓存 ───────────────────────────────────────────────────────

    /**
     * 供无法每次重组都重读文件的同步调用面（聊天气泡头部）用的缓存。
     * [refreshCache] 与 [save] 维护；缺省值即文件缺失时设置页显示的回落。
     */
    private val cachedIdentity = MutableStateFlow(SoulIdentity.DEFAULT)
    val cachedMetadata: StateFlow<SoulIdentity> = cachedIdentity.asStateFlow()

    /** 重读 SOUL.md 刷新缓存。启动时（ensureExists 之后）与外部改写后调。 */
    fun refreshCache(context: Context) {
        cachedIdentity.value = load(context)?.metadata ?: SoulIdentity.DEFAULT
    }
}
