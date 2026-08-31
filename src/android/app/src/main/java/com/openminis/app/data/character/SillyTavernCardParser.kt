package com.openminis.app.data.character

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.InflaterInputStream
import org.json.JSONArray
import org.json.JSONObject

data class CharacterCardImportPreview(
    val card: CharacterCard,
    val avatarPng: ByteArray? = null,
    val sourceLabel: String,
    val knowledgeEntryCount: Int = 0,
)

/** Parses Novex JSON plus SillyTavern Character Card V1/V2/V3 JSON and PNG cards. */
object SillyTavernCardParser {
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private const val MAX_CHUNK_BYTES = 16 * 1024 * 1024

    fun parse(bytes: ByteArray, mimeType: String? = null, fileName: String? = null): CharacterCardImportPreview {
        require(bytes.isNotEmpty()) { "角色卡文件为空" }
        val looksPng = bytes.size >= pngSignature.size &&
            bytes.copyOfRange(0, pngSignature.size).contentEquals(pngSignature)
        return if (looksPng || mimeType == "image/png" || fileName?.endsWith(".png", true) == true) {
            val json = extractPngCardJson(bytes)
                ?: throw IllegalArgumentException("这张 PNG 图片没有检测到酒馆角色卡数据（chara/ccv3）")
            parseJson(json, avatarPng = bytes, sourceHint = "酒馆 PNG")
        } else {
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
            require(text.startsWith("{")) { "只支持酒馆 PNG 或 JSON 角色卡" }
            parseJson(text, avatarPng = null, sourceHint = "JSON")
        }
    }

    fun parseJson(
        source: String,
        avatarPng: ByteArray? = null,
        sourceHint: String = "JSON",
    ): CharacterCardImportPreview {
        val root = JSONObject(source)
        if (root.optString("schema").startsWith("novex-character-card")) {
            val parsed = CharacterCard.fromJson(root)
            require(parsed.name.isNotBlank()) { "角色卡缺少名称" }
            return CharacterCardImportPreview(
                card = parsed.copy(
                    id = UUID.randomUUID().toString(),
                    sourceFormat = "Novex JSON",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
                avatarPng = avatarPng,
                sourceLabel = "Novex JSON",
            )
        }

        val data = root.optJSONObject("data") ?: root
        val name = data.optString("name").ifBlank { root.optString("name") }.trim()
        require(name.isNotBlank()) { "酒馆角色卡缺少 name（角色名称）字段" }
        val description = data.optString("description")
        val novexExtension = data.optJSONObject("extensions")?.optJSONObject("novex")
        val knowledgeResult = extractKnowledge(data.optJSONObject("character_book") ?: root.optJSONObject("character_book"))
        val spec = root.optString("spec").ifBlank {
            when {
                root.has("data") -> "chara_card_v2"
                else -> "chara_card_v1"
            }
        }
        val sourceLabel = "$sourceHint · $spec"
        val now = System.currentTimeMillis()
        return CharacterCardImportPreview(
            card = CharacterCard(
                id = UUID.randomUUID().toString(),
                name = name,
                summary = novexExtension?.optString("summary").orEmpty().ifBlank {
                    description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()
                },
                personality = data.optString("personality"),
                background = description,
                scenario = data.optString("scenario"),
                greeting = data.optString("first_mes"),
                exampleDialogue = data.optString("mes_example"),
                systemPrompt = data.optString("system_prompt"),
                postHistoryInstructions = data.optString("post_history_instructions"),
                alternateGreetings = data.optJSONArray("alternate_greetings").toStringList(),
                creatorNotes = data.optString("creator_notes"),
                tags = data.optJSONArray("tags").toStringList(),
                knowledge = knowledgeResult.first,
                allowedTools = novexExtension?.optJSONArray("allowed_tools").toStringList()
                    .filter { it in setOf("present_choices", "generate_image") },
                contentBoundary = novexExtension?.optString("content_boundary").orEmpty(),
                sourceFormat = sourceLabel,
                createdAt = now,
                updatedAt = now,
            ),
            avatarPng = avatarPng,
            sourceLabel = sourceLabel,
            knowledgeEntryCount = knowledgeResult.second,
        )
    }

    private fun extractKnowledge(book: JSONObject?): Pair<String, Int> {
        val entries = book?.optJSONArray("entries") ?: return "" to 0
        val rendered = mutableListOf<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.has("enabled") && !entry.optBoolean("enabled", true)) continue
            val content = entry.optString("content").trim()
            if (content.isEmpty()) continue
            val label = entry.optString("name").ifBlank { entry.optString("comment") }.trim()
            val keys = entry.optJSONArray("keys").toStringList()
            rendered += buildString {
                append("## ").append(label.ifBlank { "知识条目 ${index + 1}" }).append('\n')
                if (keys.isNotEmpty()) append("关键词：").append(keys.joinToString("、")).append('\n')
                append(content)
            }
        }
        return rendered.joinToString("\n\n") to rendered.size
    }

    private fun extractPngCardJson(png: ByteArray): String? {
        DataInputStream(ByteArrayInputStream(png)).use { input ->
            val signature = ByteArray(8)
            input.readFully(signature)
            require(signature.contentEquals(pngSignature)) { "PNG 文件签名无效" }
            while (input.available() >= 12) {
                val length = input.readInt()
                require(length in 0..MAX_CHUNK_BYTES && length <= input.available() - 8) { "PNG 数据块长度异常" }
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.ISO_8859_1)
                val data = ByteArray(length).also(input::readFully)
                input.readInt() // CRC is left to Android's image decoder; metadata parsing stays bounded above.
                val payload = when (type) {
                    "tEXt" -> parseTextChunk(data)
                    "zTXt" -> parseCompressedTextChunk(data)
                    "iTXt" -> parseInternationalTextChunk(data)
                    else -> null
                }
                if (payload != null) return decodeMetadataPayload(payload)
                if (type == "IEND") break
            }
        }
        return null
    }

    private fun parseTextChunk(data: ByteArray): String? {
        val separator = data.indexOf(0)
        if (separator <= 0) return null
        val keyword = data.copyOfRange(0, separator).toString(Charsets.ISO_8859_1)
        if (keyword != "chara" && keyword != "ccv3") return null
        return data.copyOfRange(separator + 1, data.size).toString(Charsets.UTF_8)
    }

    private fun parseCompressedTextChunk(data: ByteArray): String? {
        val separator = data.indexOf(0)
        if (separator <= 0 || separator + 2 > data.size) return null
        val keyword = data.copyOfRange(0, separator).toString(Charsets.ISO_8859_1)
        if (keyword != "chara" && keyword != "ccv3") return null
        if (data[separator + 1].toInt() != 0) return null
        return inflate(data.copyOfRange(separator + 2, data.size)).toString(Charsets.UTF_8)
    }

    private fun parseInternationalTextChunk(data: ByteArray): String? {
        val separator = data.indexOf(0)
        if (separator <= 0 || separator + 3 > data.size) return null
        val keyword = data.copyOfRange(0, separator).toString(Charsets.ISO_8859_1)
        if (keyword != "chara" && keyword != "ccv3") return null
        val compressed = data[separator + 1].toInt() == 1
        var cursor = separator + 3 // skip compression flag and compression method
        cursor = data.indexOf(0, cursor).takeIf { it >= 0 }?.plus(1) ?: return null // language
        cursor = data.indexOf(0, cursor).takeIf { it >= 0 }?.plus(1) ?: return null // translated keyword
        val content = data.copyOfRange(cursor, data.size)
        return (if (compressed) inflate(content) else content).toString(Charsets.UTF_8)
    }

    private fun decodeMetadataPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val decoded = sequenceOf(
            { Base64.getDecoder().decode(trimmed) },
            { Base64.getMimeDecoder().decode(trimmed) },
            { Base64.getUrlDecoder().decode(trimmed) },
        ).mapNotNull { decoder -> runCatching(decoder).getOrNull() }
            .map { it.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim() }
            .firstOrNull { it.startsWith("{") }
        return decoded ?: throw IllegalArgumentException("酒馆角色卡元数据不是有效的 JSON")
    }

    private fun inflate(bytes: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CHUNK_BYTES) { "压缩角色卡元数据过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun ByteArray.indexOf(value: Int, start: Int = 0): Int {
        for (index in start until size) if ((this[index].toInt() and 0xFF) == value) return index
        return -1
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}
