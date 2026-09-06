package com.openminis.app.novex.domain

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

enum class NovexDocumentFormat(val wireName: String) {
    DOC("doc"),
    DOCX("docx"),
    PDF("pdf"),
    XLSX("xlsx"),
    PPTX("pptx"),
    EPUB("epub"),
    RTF("rtf"),
    CSV("csv"),
    JSON("json"),
    XML("xml"),
    YAML("yaml"),
    TEXT("text"),
    MARKDOWN("markdown"),
    HTML("html"),
    WIKITEXT("wikitext"),
    UNKNOWN("unknown"),
}

enum class NovexDocumentStatus(val wireName: String) {
    READY("ready"),
    EMPTY("empty"),
    OCR_REQUIRED("ocr_required"),
    PASSWORD_REQUIRED("password_required"),
    UNSUPPORTED("unsupported"),
    DAMAGED("damaged"),
    TRUNCATED("truncated"),
}

enum class NovexDocumentBlockKind(val wireName: String) {
    HEADING("heading"),
    PARAGRAPH("paragraph"),
    LIST_ITEM("list_item"),
    TABLE("table"),
    IMAGE("image"),
    NOTE("note"),
    PAGE_BREAK("page_break"),
    UNKNOWN("unknown"),
}

data class NovexDocumentSourceAnchor(
    val part: String,
    val ordinal: Int,
    val page: Int? = null,
    val detail: String? = null,
) {
    init {
        require(part.isNotBlank()) { "文档来源部件不能为空" }
        require(ordinal >= 0) { "文档来源顺序不能为负数" }
        require(page == null || page > 0) { "文档页码必须大于零" }
    }
}

object NovexDocumentBlockId {
    fun from(documentSha256: String, source: NovexDocumentSourceAnchor): String {
        require(documentSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "文档校验值必须是 SHA-256" }
        val material = listOf(
            documentSha256.lowercase(),
            source.part,
            source.ordinal.toString(),
            source.page?.toString().orEmpty(),
            source.detail.orEmpty(),
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return "block_" + digest.take(12).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

data class NovexDocumentBlock(
    val id: String,
    val kind: NovexDocumentBlockKind,
    val order: Int,
    val text: String,
    val headingPath: List<String> = emptyList(),
    val headingLevel: Int? = null,
    val source: NovexDocumentSourceAnchor,
    val mediaRef: NovexResourceRef? = null,
) {
    init {
        require(id.startsWith("block_") && id.length > "block_".length) { "文档内容块编号无效" }
        require(order >= 0) { "文档内容块顺序不能为负数" }
        require(headingPath.none(String::isBlank)) { "标题路径不能包含空标题" }
        require(headingLevel == null || headingLevel in 1..6) { "标题层级必须在一到六级之间" }
        if (kind == NovexDocumentBlockKind.HEADING) require(headingLevel != null) {
            "标题内容块必须包含标题层级"
        }
    }
}

data class NovexDocumentWarning(
    val code: String,
    val message: String,
    val blockId: String? = null,
)

data class NovexDocumentProvenance(
    val sourceKind: String,
    val sourceUrl: String,
    val siteName: String? = null,
    val pageId: String? = null,
    val revisionId: String? = null,
    val revisionTimestamp: String? = null,
    val licenseTitle: String? = null,
    val licenseUrl: String? = null,
    val retrievedAtMillis: Long,
) {
    init {
        require(sourceKind.isNotBlank()) { "文档来源类型不能为空" }
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "文档来源网址必须使用 HTTP 或 HTTPS"
        }
        require(retrievedAtMillis >= 0) { "文档获取时间不能为负数" }
        require(siteName == null || siteName.isNotBlank()) { "来源站点名称不能为空" }
        require(pageId == null || pageId.isNotBlank()) { "来源页面编号不能为空" }
        require(revisionId == null || revisionId.isNotBlank()) { "来源修订编号不能为空" }
        require(licenseTitle == null || licenseTitle.isNotBlank()) { "来源许可名称不能为空" }
        require(licenseUrl == null || licenseUrl.startsWith("https://") || licenseUrl.startsWith("http://")) {
            "来源许可网址必须使用 HTTP 或 HTTPS"
        }
    }
}

data class NovexDocumentSnapshot(
    val ref: NovexResourceRef,
    val sha256: String,
    val parserVersion: String,
    val title: String,
    val format: NovexDocumentFormat,
    val status: NovexDocumentStatus,
    val blocks: List<NovexDocumentBlock>,
    val warnings: List<NovexDocumentWarning> = emptyList(),
    val provenance: NovexDocumentProvenance? = null,
) {
    init {
        require(ref.value.startsWith("novex://documents/")) { "文档快照必须使用文档引用" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "文档校验值必须是 SHA-256" }
        require(parserVersion.isNotBlank()) { "解析器版本不能为空" }
        require(title.isNotBlank()) { "文档标题不能为空" }
        require(blocks.map { it.id }.distinct().size == blocks.size) { "文档内容块编号必须唯一" }
        require(blocks.map { it.order } == blocks.map { it.order }.sorted()) { "文档内容块必须按原文顺序排列" }
        require(blocks.map { it.order }.distinct().size == blocks.size) { "文档内容块顺序必须唯一" }
    }
}

fun interface NovexDocumentSnapshotStore {
    fun find(ref: NovexResourceRef): NovexDocumentSnapshot?
}

data class NovexDocumentInspectRequest(
    val documentRef: NovexResourceRef,
    val includeOutline: Boolean = true,
    val maxDepth: Int = 6,
    val maxOutlineItems: Int = 100,
) {
    init {
        require(maxDepth in 1..6) { "目录层级必须在一到六级之间" }
        require(maxOutlineItems in 1..500) { "目录条目上限必须在一到五百之间" }
    }
}

data class NovexDocumentPageRange(val first: Int, val last: Int) {
    init {
        require(first > 0 && last >= first) { "文档页码范围无效" }
    }
}

data class NovexDocumentReadRequest(
    val documentRef: NovexResourceRef,
    val blockIds: List<String> = emptyList(),
    val headingPath: List<String> = emptyList(),
    val query: String? = null,
    val pageRange: NovexDocumentPageRange? = null,
    val cursor: String? = null,
    val maxBlocks: Int = 20,
    val maxChars: Int = 12_000,
    val firstBlock: Int? = null,
    val lastBlock: Int? = null,
    val compact: Boolean = false,
) {
    init {
        require(maxBlocks in 1..5_000) { "单次读取来源块数量必须在一到五千之间；优先使用字符预算控制通读" }
        require(maxChars in 1..48_000) { "单次读取字符预算必须在一到四万八千之间" }
        require(firstBlock == null || firstBlock > 0) { "first_block 从 1 开始" }
        require(lastBlock == null || (firstBlock != null && lastBlock >= firstBlock)) {
            "last_block 必须与 first_block 一起提供且不小于它"
        }
        require(blockIds.none(String::isBlank)) { "内容块编号不能为空" }
        require(headingPath.none(String::isBlank)) { "标题路径不能为空" }
        val locators = listOf(
            blockIds.isNotEmpty(),
            headingPath.isNotEmpty(),
            !query.isNullOrBlank(),
            pageRange != null,
            !cursor.isNullOrBlank(),
            firstBlock != null,
        ).count { it }
        require(locators <= 1) { "定位方式互斥：cursor、query、block_ids、heading_path、page_range、first_block/last_block 每次只用一组。续读只传原样 next_cursor，不再附带 query 等定位参数；换位置请移除 cursor。" }
    }
}

/** Small external seam for model-facing document access. Parsing and storage stay behind it. */
class NovexDocumentTools(
    private val snapshots: NovexDocumentSnapshotStore,
) {
    fun documentInspect(request: NovexDocumentInspectRequest): NovexToolResult {
        val snapshot = snapshots.find(request.documentRef) ?: return notFound(request.documentRef)
        val outlineBlocks = NovexDocumentOutline.entries(snapshot).filter { it.level <= request.maxDepth }
        val outline = if (request.includeOutline) {
            outlineBlocks.take(request.maxOutlineItems).map { block ->
                mapOf(
                    "block_id" to block.blockId,
                    "level" to block.level,
                    "title" to block.title,
                    "heading_path" to block.headingPath,
                    "first_block" to block.firstBlock,
                    "last_block" to block.lastBlock,
                    "inferred" to block.inferred,
                )
            }
        } else {
            emptyList()
        }
        return NovexToolResult.success(
            code = "document.ready",
            summary = "已检查《${snapshot.title}》，共 ${snapshot.blocks.size} 个内容块",
            data = mapOf(
                "document_ref" to snapshot.ref.value,
                "title" to snapshot.title,
                "format" to snapshot.format.wireName,
                "status" to snapshot.status.wireName,
                "block_count" to snapshot.blocks.size,
                "table_count" to snapshot.blocks.count { it.kind == NovexDocumentBlockKind.TABLE },
                "image_count" to snapshot.blocks.count { it.kind == NovexDocumentBlockKind.IMAGE },
                "read_observations" to NovexSourceReadEvidence.document(snapshot, emptyList(), "PREVIEW"),
                "outline" to outline,
                "outline_truncated" to (
                    request.includeOutline && outlineBlocks.size > request.maxOutlineItems
                ),
            ),
            warnings = snapshot.warnings.map { NovexToolWarning(it.code, it.message) },
            affectedRefs = listOf(snapshot.ref),
        )
    }

    fun documentRead(request: NovexDocumentReadRequest): NovexToolResult {
        val snapshot = snapshots.find(request.documentRef) ?: return notFound(request.documentRef)
        if (request.firstBlock != null && request.firstBlock > snapshot.blocks.size) {
            return NovexToolResult.failure(code = "document.invalid_position",
                summary = "first_block 超出文档范围，当前共 ${snapshot.blocks.size} 块，从 1 开始计数", affectedRefs = listOf(snapshot.ref))
        }
        val cursor = if (request.cursor == null) {
            CursorPosition(
                selector = selectorFrom(request),
                blockIndex = 0,
                charOffset = 0,
            )
        } else {
            decodeCursor(request.cursor, snapshot) ?: return NovexToolResult.failure(
                code = "document.invalid_cursor",
                summary = "读取游标无效或来源已变化。请原样使用上次返回的 next_cursor，不手工编辑；也可移除 cursor，用 first_block 从已知块位置重读。位置从 1 开始，可用 document_inspect 查看目录。",
                affectedRefs = listOf(snapshot.ref),
            )
        }

        val selected = selectBlocks(snapshot, cursor)
        val returned = mutableListOf<Map<String, Any?>>()
        var chars = 0
        var next: CursorPosition? = null
        for ((sourceIndex, block, initialOffset) in selected) {
            if (returned.size >= request.maxBlocks) {
                next = cursor.copy(blockIndex = sourceIndex, charOffset = initialOffset)
                break
            }
            val separatorChars = if (request.compact && returned.isNotEmpty()) 1 else 0
            val available = request.maxChars - chars - separatorChars
            if (available <= 0) {
                next = cursor.copy(blockIndex = sourceIndex, charOffset = initialOffset)
                break
            }
            val remainingText = block.text.drop(initialOffset)
            val piece = remainingText.take(available)
            returned += blockPayload(block, piece, initialOffset, piece.length < remainingText.length) +
                ("position" to sourceIndex + 1)
            chars += piece.length + separatorChars
            if (piece.length < remainingText.length) {
                next = cursor.copy(blockIndex = sourceIndex, charOffset = initialOffset + piece.length)
                break
            }
        }

        val data = linkedMapOf<String, Any?>(
            "document_ref" to snapshot.ref.value,
            "read_observations" to NovexSourceReadEvidence.document(snapshot, returned,
                if (cursor.selector is ReadSelector.Query) "SEARCH" else "READ"),
            "coverage_scope" to "仅统计已解析文本；图片、无法识别及尚未提取的部分不在全文覆盖范围内",
            (if (request.compact) "passages" else "blocks") to
                (if (request.compact) compactPassages(returned) else returned),
            "source_blocks_read" to returned.size,
            "position_base" to 1,
            "truncated" to (next != null),
        )
        next?.let {
            data["next_cursor"] = encodeCursor(snapshot, it)
            data["next_position"] = mapOf("block" to it.blockIndex + 1, "char_offset" to it.charOffset)
        }
        return NovexToolResult.success(
            code = "document.read",
            summary = "已读取《${snapshot.title}》的 ${returned.size} 个内容块",
            data = data,
            warnings = snapshot.warnings.map { NovexToolWarning(it.code, it.message) },
            affectedRefs = listOf(snapshot.ref),
        )
    }

    private fun selectBlocks(
        snapshot: NovexDocumentSnapshot,
        cursor: CursorPosition,
    ): List<SelectedBlock> {
        val inferredRanges = (cursor.selector as? ReadSelector.Heading)?.let { selector ->
            NovexDocumentOutline.entries(snapshot).filter {
                it.inferred && it.headingPath.take(selector.path.size) == selector.path
            }.map { it.firstBlock..it.lastBlock }
        }.orEmpty()
        return snapshot.blocks.mapIndexedNotNull { index, block ->
            when {
                index < cursor.blockIndex -> null
                !cursor.selector.matches(block, index) && inferredRanges.none { index + 1 in it } -> null
                index == cursor.blockIndex -> SelectedBlock(index, block, cursor.charOffset)
                else -> SelectedBlock(index, block, 0)
            }
        }
    }

    /** Compact only the response, never rewrite the stored blocks or their source anchors. */
    private fun compactPassages(blocks: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val groups = mutableListOf<MutableList<Map<String, Any?>>>()
        var groupChars = 0
        for (block in blocks) {
            val previous = groups.lastOrNull()?.lastOrNull()
            val text = block["text"] as String
            val join = previous != null &&
                (previous["position"] as Int) + 1 == block["position"] &&
                previous["heading_path"] == block["heading_path"] &&
                previous["media_ref"] == null && block["media_ref"] == null &&
                previous["kind"] in setOf("paragraph", "list_item") &&
                block["kind"] in setOf("paragraph", "list_item") &&
                groupChars + text.length + 1 <= 3_000
            if (!join) {
                groups += mutableListOf<Map<String, Any?>>()
                groupChars = 0
            }
            groups.last().add(block)
            groupChars += text.length + 1
        }
        return groups.map { group ->
            val first = group.first()
            val last = group.last()
            mapOf(
                "first_block" to first["position"], "last_block" to last["position"],
                "first_block_id" to first["id"], "last_block_id" to last["id"],
                "text" to group.joinToString("\n") { it["text"] as String },
                "heading_path" to first["heading_path"], "source" to first["source"],
                "kind" to first["kind"], "media_ref" to first["media_ref"],
                "truncated" to last["truncated"],
            )
        }
    }

    private fun blockPayload(
        block: NovexDocumentBlock,
        text: String,
        charOffset: Int,
        truncated: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "id" to block.id,
        "kind" to block.kind.wireName,
        "text" to text,
        "heading_path" to block.headingPath,
        "source" to linkedMapOf(
            "part" to block.source.part,
            "ordinal" to block.source.ordinal,
            "page" to block.source.page,
            "detail" to block.source.detail,
            "char_offset" to charOffset,
        ),
        "media_ref" to block.mediaRef?.value,
        "truncated" to truncated,
    )

    private fun notFound(ref: NovexResourceRef) = NovexToolResult.failure(
        code = "document.not_found",
        summary = "找不到指定文档",
        affectedRefs = listOf(ref),
    )

    private data class CursorPosition(
        val selector: ReadSelector,
        val blockIndex: Int,
        val charOffset: Int,
    )
    private data class SelectedBlock(
        val sourceIndex: Int,
        val block: NovexDocumentBlock,
        val initialOffset: Int,
    )

    private sealed interface ReadSelector {
        val kind: String
        fun matches(block: NovexDocumentBlock, index: Int): Boolean

        data object All : ReadSelector {
            override val kind = "all"
            override fun matches(block: NovexDocumentBlock, index: Int) = true
        }

        data class Blocks(val ids: Set<String>) : ReadSelector {
            override val kind = "blocks"
            override fun matches(block: NovexDocumentBlock, index: Int) = block.id in ids
        }

        data class Heading(val path: List<String>) : ReadSelector {
            override val kind = "heading"
            override fun matches(block: NovexDocumentBlock, index: Int) =
                block.headingPath.take(path.size) == path
        }

        data class Query(val value: String) : ReadSelector {
            override val kind = "query"
            override fun matches(block: NovexDocumentBlock, index: Int) = block.text.contains(value, ignoreCase = true)
        }

        data class Pages(val first: Int, val last: Int) : ReadSelector {
            override val kind = "pages"
            override fun matches(block: NovexDocumentBlock, index: Int) =
                block.source.page?.let { it in first..last } == true
        }

        data class Positions(val first: Int, val last: Int) : ReadSelector {
            override val kind = "positions"
            override fun matches(block: NovexDocumentBlock, index: Int) = index + 1 in first..last
        }
    }

    private fun selectorFrom(request: NovexDocumentReadRequest): ReadSelector = when {
        request.blockIds.isNotEmpty() -> ReadSelector.Blocks(request.blockIds.toSet())
        request.headingPath.isNotEmpty() -> ReadSelector.Heading(request.headingPath)
        !request.query.isNullOrBlank() -> ReadSelector.Query(request.query)
        request.pageRange != null -> ReadSelector.Pages(request.pageRange.first, request.pageRange.last)
        request.firstBlock != null -> ReadSelector.Positions(request.firstBlock, request.lastBlock ?: Int.MAX_VALUE)
        else -> ReadSelector.All
    }

    private fun encodeCursor(snapshot: NovexDocumentSnapshot, position: CursorPosition): String {
        val selector = JSONObject().put("kind", position.selector.kind)
        when (val value = position.selector) {
            ReadSelector.All -> Unit
            is ReadSelector.Blocks -> selector.put("ids", JSONArray(value.ids.toList().sorted()))
            is ReadSelector.Heading -> selector.put("path", JSONArray(value.path))
            is ReadSelector.Query -> selector.put("value", value.value)
            is ReadSelector.Pages -> selector.put("first", value.first).put("last", value.last)
            is ReadSelector.Positions -> selector.put("first", value.first).put("last", value.last)
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("document", snapshot.sha256.take(12).lowercase())
            .put("block", position.blockIndex)
            .put("offset", position.charOffset)
            .put("selector", selector)
            .toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    private fun decodeCursor(value: String, snapshot: NovexDocumentSnapshot): CursorPosition? {
        return runCatching {
            val payload = JSONObject(String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8))
            if (payload.getInt("version") != 1) return null
            if (payload.getString("document") != snapshot.sha256.take(12).lowercase()) return null
            val blockIndex = payload.getInt("block")
            val charOffset = payload.getInt("offset")
            val block = snapshot.blocks.getOrNull(blockIndex) ?: return null
            if (charOffset !in 0..block.text.length) return null
            val selectorJson = payload.getJSONObject("selector")
            val selector = when (selectorJson.getString("kind")) {
                "all" -> ReadSelector.All
                "blocks" -> ReadSelector.Blocks(selectorJson.getJSONArray("ids").stringValues().toSet())
                "heading" -> ReadSelector.Heading(selectorJson.getJSONArray("path").stringValues())
                "query" -> ReadSelector.Query(selectorJson.getString("value"))
                "pages" -> ReadSelector.Pages(selectorJson.getInt("first"), selectorJson.getInt("last"))
                "positions" -> ReadSelector.Positions(selectorJson.getInt("first"), selectorJson.getInt("last"))
                else -> return null
            }
            CursorPosition(selector, blockIndex, charOffset)
        }.getOrNull()
    }

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)
}
