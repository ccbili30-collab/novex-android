package com.openminis.app.novex.domain

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** An inspectable candidate. No production selector can return this as the request prompt. */
data class NovexTeachingSection(val key: String, val label: String, val text: String)
data class NovexTeachingAssembly(val sourceRevision: String, val identityKey: String, val sections: List<NovexTeachingSection>) {
    val prompt: String get() = sections.joinToString("\n\n") { "## ${it.label}\n${it.text}" }
    fun toJson() = JSONObject().put("version", "v6-candidate-app-1").put("accepted", false).put("sent", false)
        .put("sourceRevision", sourceRevision).put("identityKey", identityKey)
        .put("promptRevision", NovexFrozenContextCodec.digest(prompt)).put("prompt", prompt)
        .put("sections", JSONArray(sections.map { JSONObject().put("key", it.key).put("label", it.label)
            .put("text", it.text).put("revision", NovexFrozenContextCodec.digest(it.text)) }))
}

object NovexTeachingCandidate {
    fun build(source: String, identity: AnswerIdentity, tools: Set<String>, userStyle: String,
        imageStyle: String, runtime: String): NovexTeachingAssembly {
        fun between(start: String, end: String): String {
            require(source.contains(start) && source.contains(end)) { "候选教学章节缺失" }
            return source.substringAfter(start).substringBefore(end).trim()
        }
        val identityKey = when(identity) {
            AnswerIdentity.Nova -> "N"
            is AnswerIdentity.CharacterVersion -> "R"
            is AnswerIdentity.PersonaPreset -> if(identity.presetId == NovexPersonaPresets.gameHost.presetId) "G" else "X"
        }
        val common = between("## A. 共用教学正文", "## B. 身份教学正文")
        val identities = between("## B. 身份教学正文", "## C. 共用任务教学正文")
        val selected = requireNotNull(Regex("### $identityKey\\. .*?(?=\\n### [NGRX]\\. |\\z)", RegexOption.DOT_MATCHES_ALL).find(identities)) { "候选身份教学缺失" }.value
        val tasks = between("## C. 共用任务教学正文", "## D. 工具教学正文")
        val rawTools = between("## D. 工具教学正文", "## E. 用户风格")
        val toolPattern = Regex("\\b(?:novex_[a-z_]+|save_checkpoint|render_panel|present_choices|register_controls|update_playthrough_state|end_interactive_fiction|document_[a-z_]+|learning_[a-z_]+|workspace_[a-z_]+|read_image|generate_image|browser_use)\\b")
        // Same paragraph selection as the existing experiment; task teaching remains complete.
        val teaching = rawTools.split(Regex("(?=### U\\d{2}\\.)")).mapNotNull { chunk ->
            if(!chunk.startsWith("### U")) return@mapNotNull null
            val active = toolPattern.findAll(chunk).any { it.value in tools }
            val lines = chunk.lines().filter { it.startsWith("- ") }.filter { line ->
                val named = toolPattern.findAll(line).map { it.value }.toSet()
                if(named.isEmpty()) active else named.any { it in tools }
            }
            if(lines.isEmpty()) null else chunk.lineSequence().first() + "\n" + lines.joinToString("\n")
        }.joinToString("\n\n")
        val style = between("## E. 用户风格与当前人格资料槽位", "## F. 本轮运行资料槽位")
            .replace("{{本对话用户风格与补充要求}}", userStyle)
            .replace("{{当前采用的人格快照或角色专属教学}}", "见本轮已采用的回答身份资料。")
            .replace("{{本对话图片生成风格}}", imageStyle)
        return NovexTeachingAssembly(NovexFrozenContextCodec.digest(source), identityKey, listOf(
            NovexTeachingSection("common", "共用规则", common), NovexTeachingSection("identity", "当前一个身份", selected),
            NovexTeachingSection("methods", "共用任务方法", tasks), NovexTeachingSection("tools", "当前工具教学", teaching),
            NovexTeachingSection("style", "用户风格与补充要求", style), NovexTeachingSection("runtime", "真实运行资料", runtime),
        ))
    }
}

/** Native diagnostics only. Deliberately outside the model-readable workspace and source index. */
class FileNovexTeachingTraceStore(private val root: File) {
    fun save(conversationId: String, recordId: String, payload: JSONObject): String = synchronized(writeLock) {
        require(payload.getString("conversationId") == conversationId && payload.getString("recordId") == recordId) { "装配记录归属不一致" }
        val relative = "${NovexFrozenContextCodec.digest(conversationId)}/${NovexFrozenContextCodec.digest(recordId)}.json"
        val target = resolve(relative)
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 10 * 1024 * 1024) { "装配记录超过十兆字节，未保存，正文未截断" }
        if(target.exists()) {
            require(target.readBytes().contentEquals(body)) { "已保存的装配记录不能被不同内容覆盖" }
            return@synchronized relative
        }
        check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "无法创建装配记录目录" }
        val temporary = File.createTempFile("teaching-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(body); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        relative
    }
    fun read(reference: String): JSONObject = resolve(reference).let { file ->
        require(file.length() <= 10 * 1024 * 1024) { "装配记录大小异常" }
        JSONObject(file.readText(Charsets.UTF_8))
    }.also { record ->
        require(record.getString("formalRevision") == NovexFrozenContextCodec.digest(record.getString("formalPrompt"))) { "正式装配校验不一致" }
        record.optJSONObject("candidate")?.let { candidate ->
            require(!candidate.getBoolean("sent") && !candidate.getBoolean("accepted")) { "候选状态记录无效" }
            val sections = candidate.getJSONArray("sections")
            val reconstructed = (0 until sections.length()).joinToString("\n\n") { index ->
                val section = sections.getJSONObject(index)
                require(section.getString("revision") == NovexFrozenContextCodec.digest(section.getString("text"))) { "候选部分校验不一致" }
                "## ${section.getString("label")}\n${section.getString("text")}"
            }
            require(reconstructed == candidate.getString("prompt")) { "候选部分与完整装配不一致" }
            require(candidate.getString("promptRevision") == NovexFrozenContextCodec.digest(candidate.getString("prompt"))) { "候选装配校验不一致" }
        }
    }
    private companion object { val writeLock = Any() }
    private fun resolve(reference: String): File {
        require(reference.matches(Regex("[0-9a-f]{64}/[0-9a-f]{64}\\.json"))) { "装配记录引用无效" }
        val file = File(root, reference).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "装配记录路径越界" }
        return file
    }
}
