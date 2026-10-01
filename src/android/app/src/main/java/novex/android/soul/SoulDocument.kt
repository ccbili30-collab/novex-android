package novex.android.soul

/**
 * SOUL.md 的文档模型与 frontmatter 编解码（P3.5c 自 agent/SoulStore.kt
 * 真重写，IO 与提示词装配拆去同包另两件）。
 *
 * 文件形态（冻结面）：YAML frontmatter（`---` 围栏）+ Markdown 正文。
 * 正文体是系统提示的第一层人设素材；name / emoji 驱动聊天气泡头部。
 *
 * 编解码器刻意不是完整 YAML：只认本文件用到的键，逐行 `key: "value"`。
 * 自家写出的文件满足 serialize(parse(s)) == s。
 */
data class SoulIdentity(
    val name: String,
    /**
     * 磁盘上原样的 `emoji` frontmatter 值。
     *
     * 仅用于回写盘时保真——用户可能在别的平台/旧版本写过自定义 emoji，
     * 本端设置页保存时不该悄悄抹掉。UI 一律读 [displayEmoji]（钉死
     * ✨），不读这个字段——对应 iOS 撤下 emoji 自定义字段的决定。
     */
    val emoji: String,
    val style: String,
    /** `"auto"`、`"zh"`、`"en"` 或任意自由标签。 */
    val lang: String,
) {
    /** 所有 UI 面展示的 emoji：无论盘上写什么，恒为 ✨。 */
    val displayEmoji: String get() = DISPLAY_EMOJI

    companion object {
        /** 锁定的身份 emoji，每个 UI 面都用它。 */
        const val DISPLAY_EMOJI = "✨"

        val DEFAULT = SoulIdentity(
            name = "Nova",
            // 缺省 emoji 留空——UI 用 [displayEmoji] 的固定 ✨，序列化也不再
            // 写 `emoji:` 行。字段保留只为旧用户文件里的该行能无损读入，
            // 下次保存时盘上也随之消失。
            emoji = "",
            style = "",
            lang = "auto",
        )
    }
}

/** 一份完整 SOUL.md：frontmatter 身份 + Markdown 正文。 */
data class SoulDocument(val metadata: SoulIdentity, val body: String)

/**
 * SOUL.md frontmatter 编解码（P3.5c 真重写）。
 *
 * 读：剥掉前导空行后必须以 `---` 开行、再遇 `---` 闭栏，栏内逐行认
 * name / emoji / style / lang 四键（值去引号）；任何不合规都整体按
 * 「缺省身份 + 原文当正文」处理。
 * 写：三键围栏（name / style / lang）+ 空行 + 正文——`emoji` 行刻意
 * 不写（emoji 定死 ✨，写盘会暗示存在不存在的自定义项）；旧文件里的
 * `emoji: "…"` 行读得进、下次保存自然淘汰。
 */
object SoulFrontmatterCodec {

    fun parse(source: String): SoulDocument {
        val leadTrimmed = source.dropWhile { it == '\n' || it == '\r' }
        val lines = leadTrimmed.split("\n")
        if (lines.firstOrNull()?.trim() != "---") return SoulDocument(SoulIdentity.DEFAULT, source)
        // 在第二行起找闭栏；找到的下标要换算回全表坐标。
        val relativeClose = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (relativeClose < 0) return SoulDocument(SoulIdentity.DEFAULT, source)
        val close = relativeClose + 1

        val frontmatter = lines.subList(1, close)
        val body = lines.subList(close + 1, lines.size)
            .joinToString("\n")
            .dropWhile { it == '\n' || it == '\r' }

        var name = SoulIdentity.DEFAULT.name
        var emoji = SoulIdentity.DEFAULT.emoji
        var style = SoulIdentity.DEFAULT.style
        var lang = SoulIdentity.DEFAULT.lang
        for (raw in frontmatter) {
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "name" -> if (value.isNotEmpty()) name = value
                "emoji" -> if (value.isNotEmpty()) emoji = value
                "style" -> style = value
                "lang" -> if (value.isNotEmpty()) lang = value
                else -> Unit
            }
        }
        return SoulDocument(SoulIdentity(name, emoji, style, lang), body)
    }

    fun serialize(document: SoulDocument): String = buildString {
        append("---\n")
        append("name: \"").append(escape(document.metadata.name)).append("\"\n")
        append("style: \"").append(escape(document.metadata.style)).append("\"\n")
        append("lang: \"").append(escape(document.metadata.lang)).append("\"\n")
        append("---\n\n")
        append(document.body)
        if (!endsWith("\n")) append("\n")
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * 历史助手名迁移（冻结面：正则与替换目标逐字）——把 frontmatter 里
 * `"Minis"` / `"Novex"` 的 name 行一次性改成 `"Nova"`，其他名字不动。
 * 幂等；改没改由调用方比对返回值判断。
 */
fun migrateLegacyAssistantName(source: String): String =
    Regex("(?m)^name:\\s*\"(?:Minis|Novex)\"\\s*$")
        .replaceFirst(source, "name: \"Nova\"")
