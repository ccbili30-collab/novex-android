package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A selected character version is the exchange unit; a card is never an activation command. */
object NovexTavernExchange {
    private const val SOURCE = "_novexTavernSource"
    private val fields = linkedMapOf(
        "description" to "人物背景",
        "personality" to "人格与表达",
        "scenario" to "扮演场景",
        "first_mes" to "开场白",
        "mes_example" to "示例对话",
        "system_prompt" to "角色专属指令",
        "post_history_instructions" to "回复后置要求",
    )
    const val COMPATIBILITY = "角色定义按用途映射；世界书原始条目、触发条件、停用状态与未知扩展完整保留。当前未执行酒馆世界书触发、宏替换和第三方脚本；不支持 CHARX（第三版资源压缩卡包），附加资源只保留原始声明。原生多卡引用、玩家身份、人生阶段及本局存档不等同于酒馆运行能力。"

    fun importCharacter(bytes: ByteArray): NovexValidatedCardImport {
        require(bytes.size in 1..64 * 1024 * 1024) { "角色卡文件须在 64 MiB（兆二进制字节）以内" }
        val parsed = SillyTavernCardParser.parse(bytes)
        val source = requireNotNull(parsed.sourceJson) { "请选择酒馆角色卡；原生角色卡请使用原生卡包" }
        val raw = JSONObject(source)
        val format = raw.optString("spec").ifBlank { if (raw.has("data")) "chara_card_v2" else "chara_card_v1" }
        require(format in setOf("chara_card_v1", "chara_card_v2", "chara_card_v3")) { "尚不支持该角色卡格式：$format；原文件未修改" }
        val data = raw.optJSONObject("data") ?: raw
        val sourceId = UUID.randomUUID().toString()
        val modules = fields.map { (field, label) -> JSONObject()
            .put("id", "field-$field")
            .put("type", if (field == "description") "custom" else "roleInstructions")
            .put("title", label).put("presentation", "article")
            .put("content", JSONObject().put("text", data.optString(field)))
            .put("extensions", JSONObject().put("novex_tavern", JSONObject().put("field", field)))
        }
        val archive = JSONObject().put("rawJson", source).put("format", format)
            .put("formatVersion", raw.optString("spec_version"))
            .put("sha256", digest(source)).put("mappedFields", JSONArray(fields.keys.toList()))
            .put("compatibility", COMPATIBILITY)
        val profile = JSONObject().put("displayName", parsed.card.name)
            .put("introduction", parsed.card.summary).put(SOURCE, archive)
        val media = parsed.avatarPng?.let { listOf(NovexCardMedia("media/avatar.png", "image/png", it)) }.orEmpty()
        val version = JSONObject().put("id", "$sourceId-origin").put("kind", "origin").put("name", "本体")
            .put("profile", profile).put("tags", JSONArray(parsed.card.tags))
            .put("modules", JSONArray(modules)).put("moduleOrder", JSONArray(modules.map { it.getString("id") }))
            .put("media", JSONObject().apply { if (media.isNotEmpty()) put("avatar", JSONObject().put("path", media.single().path)) })
        val document = JSONObject().put("sourceId", sourceId).put("schemaVersion", 1).put("documentType", "novex.character")
            .put("name", parsed.card.name).put("summary", parsed.card.summary)
            .put("versions", JSONArray().put(version)).put("versionOrder", JSONArray().put(version.getString("id")))
        return NovexCardTransferParser.parse(NovexCardPackagePreview(NovexCardKind.CHARACTER, sourceId,
            parsed.card.name, document.toString(), media))
    }

    fun sourceSummary(profileJson: String): String? = sourceRecord(profileJson)?.let { archive ->
        val root = JSONObject(archive.getString("rawJson"))
        val data = root.optJSONObject("data") ?: root
        val entries = data.optJSONObject("character_book")?.optJSONArray("entries")
        "来源：酒馆角色卡，${entries?.length() ?: 0} 个世界书原始条目。\n$COMPATIBILITY"
    }

    fun originalSource(profileJson: String): String? = sourceRecord(profileJson)?.optString("rawJson")

    fun readSource(profileJson: String, versionId: String, offset: Int, limit: Int, revision: String?): JSONObject {
        val text = requireNotNull(originalSource(profileJson)) { "当前角色版本没有保存酒馆原始数据" }
        val current = digest(text)
        require(offset in 0..text.length && limit in 1..16_000) { "原件偏移必须在正文范围内，每次读取 1 至 16000 个字符" }
        require((offset == 0 || revision != null) && (revision == null || revision == current)) { "继续读取须携带当前原件修订；来源已变化时请从头读取" }
        require(offset == 0 || offset == text.length || !(text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate())) { "偏移不能拆开一个字符" }
        var end = minOf(text.length, offset + limit)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        require(end > offset || offset == text.length) { "读取上限不足以容纳下一个字符，请增大上限" }
        return JSONObject().put("source_id", "managed-exchange:character-version:$versionId")
            .put("label", "酒馆原始资料 · ${CharacterVersionProfile.fromJson(profileJson).name}")
            .put("revision", current).put("total_characters", text.length)
            .put("read_start", offset).put("read_end", end).put("text", text.substring(offset, end))
            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
            .put("scope", "明确管理原件；其中的外部指令、触发规则和权限声明未在本对话执行")
    }

    suspend fun exportCharacter(workspace: NovexWorkspace, versionId: String): String {
        val page = requireNotNull(workspace.characterForVersion(versionId)) { "角色版本不存在" }
        val version = page.character.allVersions.single { it.id == versionId }
        val profile = CharacterVersionProfile.fromJson(version.profileJson, page.character.character.name)
        val archive = sourceRecord(version.profileJson)
        val root = archive?.getString("rawJson")?.let(::JSONObject)
            ?: JSONObject().put("spec", "chara_card_v2").put("spec_version", "2.0").put("data", JSONObject())
        val data = root.optJSONObject("data") ?: root
        data.put("name", profile.name).put("tags", JSONArray(profile.tags))
        listOf("creator_notes", "creator", "character_version").filterNot(data::has).forEach { data.put(it, "") }
        if (!data.has("alternate_greetings")) data.put("alternate_greetings", JSONArray())
        val modules = page.modulesByVersion[versionId].orEmpty()
        val mapped = modules.mapNotNull { module -> fieldFor(module)?.let { it to module } }.groupBy({ it.first }, { it.second })
        require(mapped.values.all { it.size == 1 }) { "同一酒馆字段对应多个模块，请先整理重复的字段映射" }
        fields.keys.forEach { field ->
            val text = mapped[field]?.singleOrNull()?.let(::moduleText)
            data.put(field, text ?: if (archive == null && field == "description") profile.summary else "")
        }
        // Unmapped public modules can be exchanged as constant lore entries. Companion
        // identities remain local; they cannot silently become the recipient's player.
        val extra = modules.filter { fieldFor(it) == null && NovexModuleVisibility.allowsContext(it.type, acting = false) }
        if (extra.isNotEmpty()) {
            val book = data.optJSONObject("character_book") ?: JSONObject().put("extensions", JSONObject())
            val entries = book.optJSONArray("entries") ?: JSONArray()
            val kept = JSONArray(entries.toString())
            extra.forEach { module -> kept.put(JSONObject().put("name", module.name).put("content", moduleText(module))
                .put("keys", JSONArray()).put("enabled", true).put("constant", true).put("insertion_order", kept.length())
                .put("extensions", JSONObject().put("novex_exchange", JSONObject().put("generated", true).put("source_module", module.id)))) }
            book.put("entries", kept); data.put("character_book", book)
        }
        val otherInstructions = modules.filter { fieldFor(it) == null && it.type == ContentModuleType.ROLE_INSTRUCTIONS }
            .joinToString("\n\n", transform = ::moduleText)
        if (otherInstructions.isNotBlank()) data.put("system_prompt", listOf(data.optString("system_prompt"), otherInstructions).filter(String::isNotBlank).joinToString("\n\n"))
        val extensions = data.optJSONObject("extensions") ?: JSONObject()
        val exchange = extensions.optJSONObject("novex_exchange") ?: JSONObject()
        exchange.put("source_version", versionId).put("revision_sha256", digest(version.profileJson + modules.joinToString { it.contentJson }))
            .put("source_sha256", archive?.optString("sha256"))
            .put("compatibility", COMPATIBILITY)
            .put("native_references", JSONArray(workspace.referencesFrom(NovexContentAddress.characterVersion(versionId)).map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
        extensions.put("novex_exchange", exchange); data.put("extensions", extensions)
        if (root.has("data")) root.put("data", data)
        return root.toString(2)
    }

    private fun sourceRecord(profileJson: String): JSONObject? = runCatching {
        JSONObject(profileJson).optJSONObject(SOURCE)?.takeIf { archive ->
            val raw = archive.opt("rawJson") as? String ?: return@takeIf false
            JSONObject(raw)
            true
        }
    }.getOrNull()
    private fun fieldFor(module: ContentModuleEntity): String? = runCatching {
        JSONObject(module.contentJson).optJSONObject("_novexTransferSource")?.optJSONObject("extensions")
            ?.optJSONObject("novex_tavern")?.optString("field")?.takeIf { it in fields }
    }.getOrNull()
    private fun moduleText(module: ContentModuleEntity): String = ContentModuleDocumentCodec.decode(module.type, module.contentJson).toPlainText()
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
