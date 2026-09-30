package novex.android.repo

import com.openminis.app.data.repository.SkillRepository

/**
 * SKILL.md 编解码 —— 纯函数集合，无状态、无 IO，可独立单测。
 *
 * 文件格式是跨端磁盘事实（iOS SkillStore 与本端共用），不可漂移：
 *
 *   ---
 *   name: <技能名>
 *   description: <单行，或 | / > 块标量多行>
 *   version: <版本号>
 *   ---
 *   <正文 markdown>
 *
 * 解析容忍度对齐 iOS：
 *   - 正文不以 `---` 开头、或找不到独占一行的闭合 `---` → 整份文件按
 *     「不是 SKILL.md」处理（返回 null），调用方据此拒绝导入；
 *   - name 为空同样返回 null，导入路径可回退（例如从 GitHub 目录名取名）；
 *   - description 支持 YAML 块标量：`|` 保留换行、`>` 折叠为空格，并接受
 *     chomping / 缩进指示变体（`>-`、`|+`、`>2`…）。真实技能文件大量使用
 *     这些写法，若按字面量存储，列表里会把标记本身当描述展示出来。
 */
internal object SkillMarkdown {

    const val FILE_NAME = "SKILL.md"

    /** 模型侧读取技能时使用的虚拟挂载根（与 ContentPaths 的全局挂载一一对应）。 */
    const val VIRTUAL_ROOT = "/var/minis/skills"

    fun virtualPath(skillId: String): String = "$VIRTUAL_ROOT/$skillId/$FILE_NAME"

    /**
     * 从虚拟路径还原技能 id。仅接受「恰好指向某技能 SKILL.md」的形状；
     * scripts/ 等子资源路径不认，避免把子资源读取计入技能使用。
     */
    fun skillIdOfVirtualPath(path: String): String? {
        if (!path.startsWith("$VIRTUAL_ROOT/")) return null
        if (!path.endsWith("/$FILE_NAME")) return null
        val middle = path.removePrefix("$VIRTUAL_ROOT/").removeSuffix("/$FILE_NAME")
        if (middle.isEmpty() || middle.contains('/')) return null
        return middle
    }

    /** 目录名 = 小写 slug：非字母数字段折叠为 `-`，首尾 `-` 去掉。已落盘的 id 依赖此算法稳定。 */
    fun slugOf(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** 组装一份可直接落盘的 SKILL.md（frontmatter 三字段 + 正文）。 */
    fun serialize(name: String, description: String, version: String, body: String): String = buildString {
        append("---")
        appendLine()
        append("name: ").append(name)
        appendLine()
        append("description: ").append(description)
        appendLine()
        append("version: ").append(version)
        appendLine()
        append("---")
        appendLine()
        append(body)
    }

    /**
     * 解析入口。见文件头注释的容忍度说明；返回 null 即「不是合法 SKILL.md」。
     */
    fun parse(text: String): SkillRepository.ParsedSkill? {
        val lines = text.trimStart().lines()
        val opener = lines.firstOrNull() ?: return null
        if (!opener.startsWith("---")) return null
        // 闭合线必须独占一行：避免误吞正文里的水平分割线。
        val fence = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
        val header = parseHeader(lines.subList(1, fence))
        if (header.name.isBlank()) return null
        val body = lines.subList(fence + 1, lines.size).joinToString("\n").trim('\n')
        return SkillRepository.ParsedSkill(
            name = header.name,
            description = header.description,
            version = header.version ?: DEFAULT_VERSION,
            body = body,
        )
    }

    private const val DEFAULT_VERSION = "1.0.0"

    private class Header(val name: String, val description: String, val version: String?)

    private fun parseHeader(head: List<String>): Header {
        var name = ""
        var description = ""
        var version: String? = null
        var cursor = 0
        while (cursor < head.size) {
            val line = head[cursor]
            val colonAt = line.indexOf(':')
            if (colonAt < 0) {
                cursor += 1
                continue
            }
            val key = line.substring(0, colonAt).trim().lowercase()
            val inlineValue = line.substring(colonAt + 1).trim()
            // 块标量：`|` / `>` 及其变体。取值来自后续缩进行的集合；
            // 若块标记后紧跟的是非缩进行（或没有后续行），则按字面量处理
            // （与 iOS 的边界行为一致：空块折叠为空串）。
            val blockLines: List<String>? =
                if (inlineValue.startsWith("|") || inlineValue.startsWith(">")) blockBodyAfter(head, cursor + 1) else null
            val value = when {
                blockLines == null -> inlineValue
                inlineValue.startsWith(">") -> blockLines.joinToString(" ").trim()
                else -> blockLines.joinToString("\n").trim('\n')
            }
            cursor = if (blockLines == null) cursor + 1 else cursor + 1 + blockLines.size
            when (key) {
                "name" -> name = value
                "description" -> description = value
                "version" -> version = value
            }
        }
        return Header(name, description, version)
    }

    /** 收集 [from] 起连续的「空行或缩进行」；一行都不是则返回 null（走字面量分支）。 */
    private fun blockBodyAfter(head: List<String>, from: Int): List<String>? {
        if (from >= head.size) return null
        val collected = ArrayList<String>()
        var scan = from
        while (scan < head.size) {
            val candidate = head[scan]
            if (candidate.isNotEmpty() && !candidate[0].isWhitespace()) break
            collected.add(candidate.trim())
            scan += 1
        }
        return collected
    }

    /** 系统提示片段里仅需要三个字符的转义。 */
    fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
