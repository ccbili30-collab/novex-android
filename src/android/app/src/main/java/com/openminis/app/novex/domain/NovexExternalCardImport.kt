package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Deterministic import only: a model is never required to make readable external cards usable. */
object NovexExternalCardImport {
    const val SOURCE = "_novexExternalSource"

    fun decode(kind: NovexCardKind, bytes: ByteArray, fileName: String): NovexValidatedCardImport {
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "请选择非空、64 MiB（兆二进制字节）以内的卡片文件" }
        if (bytes.startsWith(0x50, 0x4b) || fileName.substringAfterLast('.').lowercase() in NovexCardKind.entries.map { it.extension }) {
            val preview = NovexCardPackageCodec.decode(bytes)
            require(preview.kind == kind) { "卡片类型与当前库不同，请在对应卡片库导入" }
            return NovexCardTransferParser.parse(preview)
        }
        require(kind != NovexCardKind.GAME) { "文游卡请选择原生文游卡包" }
        if (bytes.startsWith(0x89, 0x50, 0x4e, 0x47)) {
            require(kind == NovexCardKind.CHARACTER) { "图片中的角色卡请在角色库导入" }
            return NovexTavernExchange.importCharacter(bytes)
        }
        val text = decodeText(bytes)
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (kind == NovexCardKind.CHARACTER && root != null && isKnownCharacter(root)) {
            return NovexTavernExchange.importCharacter(text.toByteArray(Charsets.UTF_8))
        }
        // Unknown keys remain literal text. They are not interpreted as native fields or permissions.
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', fileName)
            .replace(Regex("[-_][0-9a-fA-F]{24,}$"), "").trim().ifBlank { if (kind == NovexCardKind.WORLD) "导入的世界" else "导入的角色" }
        val id = UUID.randomUUID().toString()
        val module = JSONObject().put("id", "$id-source").put("title", "原始设定")
            .put("type", if (kind == NovexCardKind.CHARACTER) "roleInstructions" else "custom")
            .put("presentation", "article").put("content", JSONObject().put("text", text))
            .put("extensions", JSONObject().put("novex_verbatim", true))
        val document = JSONObject().put("schemaVersion", 1).put("sourceId", id).put("name", name)
            .put("documentType", if (kind == NovexCardKind.WORLD) "novex.world" else "novex.character")
            .put(SOURCE, JSONObject().put("fileName", fileName.substringAfterLast('/').substringAfterLast('\\'))
                .put("bytesBase64", Base64.getEncoder().encodeToString(bytes)).put("mode", "verbatim"))
        if (kind == NovexCardKind.WORLD) {
            document.put("overview", "").put("modules", JSONArray().put(module))
                .put("moduleOrder", JSONArray().put(module.getString("id")))
        } else {
            val version = JSONObject().put("id", "$id-origin").put("kind", "origin").put("name", "本体")
                .put("profile", JSONObject().put("displayName", name))
                .put("modules", JSONArray().put(module)).put("moduleOrder", JSONArray().put(module.getString("id")))
            document.put("summary", "").put("versions", JSONArray().put(version))
                .put("versionOrder", JSONArray().put(version.getString("id")))
        }
        return NovexCardTransferParser.parse(NovexCardPackagePreview(kind, id, name, document.toString(), emptyList()))
    }

    fun original(document: JSONObject): Pair<String, ByteArray>? = document.optJSONObject(SOURCE)?.let {
        it.getString("fileName") to Base64.getDecoder().decode(it.getString("bytesBase64"))
    }

    fun isVerbatimModule(contentJson: String): Boolean = runCatching {
        JSONObject(contentJson).optJSONObject("_novexTransferSource")?.optJSONObject("extensions")?.optBoolean("novex_verbatim") == true
    }.getOrDefault(false)

    private fun isKnownCharacter(root: JSONObject): Boolean {
        if (root.optString("spec") in setOf("chara_card_v2", "chara_card_v3")) return true
        // A random object with a name is not a recognized character schema.
        return root.has("name") && root.has("first_mes") && root.has("mes_example") &&
            listOf("description", "personality", "scenario").any(root::has)
    }

    private fun decodeText(bytes: ByteArray): String {
        val candidates = when {
            bytes.startsWith(0xff, 0xfe) -> listOf(Charsets.UTF_16LE to 2)
            bytes.startsWith(0xfe, 0xff) -> listOf(Charsets.UTF_16BE to 2)
            bytes.startsWith(0xef, 0xbb, 0xbf) -> listOf(Charsets.UTF_8 to 3)
            else -> listOf(Charsets.UTF_8 to 0, Charset.forName("GB18030") to 0)
        }
        for ((charset, skip) in candidates) {
            val decoded = runCatching {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
            }.getOrNull() ?: continue
            if (decoded.isNotBlank() && decoded.none { it == '\u0000' || (it.isISOControl() && it !in "\n\r\t") }) return decoded
        }
        error("无法提取可读设定，未导入卡片。请提供文本资料或包含角色数据的图片卡；原文件未修改")
    }

    private fun ByteArray.startsWith(vararg values: Int) = size >= values.size && values.indices.all { this[it].toInt() and 255 == values[it] }
}
