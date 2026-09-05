package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-independent decoder for the two public document tools.
 * Provider and Android adapters only pass a tool name and its JSON arguments through this seam.
 */
class NovexDocumentToolRouter(
    private val tools: NovexDocumentTools,
) {
    fun execute(name: String, argumentsJson: String): NovexToolResult {
        if (name !in TOOL_NAMES) {
            return NovexToolResult.failure(
                code = "tool.unknown",
                summary = "当前文档工具不存在",
                allowedValues = TOOL_NAMES,
            )
        }
        return runCatching {
            val arguments = JSONObject(argumentsJson.ifBlank { "{}" })
            when (name) {
                DOCUMENT_INSPECT -> tools.documentInspect(arguments.inspectRequest())
                DOCUMENT_READ -> tools.documentRead(arguments.readRequest())
                else -> error("unreachable")
            }
        }.getOrElse { failure ->
            NovexToolResult.failure(
                code = "tool.invalid_arguments",
                summary = failure.message?.takeIf(String::isNotBlank) ?: "文档工具参数无效",
                allowedValues = TOOL_NAMES,
            )
        }
    }

    private fun JSONObject.documentRef(): NovexResourceRef {
        val value = optString("document_ref").trim()
        require(value.isNotEmpty()) { "缺少 document_ref（文档引用）" }
        require(value.startsWith("novex://documents/")) { "document_ref 必须是 Novex 文档引用" }
        return NovexResourceRef(value)
    }

    private fun JSONObject.inspectRequest() = NovexDocumentInspectRequest(
        documentRef = documentRef(),
        includeOutline = optBoolean("include_outline", true),
        maxDepth = optInt("max_depth", 6),
        maxOutlineItems = optInt("max_outline_items", 100),
    )

    private fun JSONObject.readRequest(): NovexDocumentReadRequest {
        val page = optJSONObject("page_range")
        return NovexDocumentReadRequest(
            documentRef = documentRef(),
            blockIds = optJSONArray("block_ids").stringValues(),
            headingPath = optJSONArray("heading_path").stringValues(),
            query = optionalString("query"),
            pageRange = page?.let {
                NovexDocumentPageRange(
                    first = it.getInt("first"),
                    last = it.getInt("last"),
                )
            },
            cursor = optionalString("cursor"),
            maxBlocks = optInt("max_blocks", 20),
            maxChars = optInt("max_chars", 12_000),
        )
    }

    private fun JSONObject.optionalString(name: String): String? =
        optString(name).trim().ifBlank { null }

    private fun JSONArray?.stringValues(): List<String> = this?.let { array ->
        (0 until array.length()).map { index -> array.getString(index) }
    }.orEmpty()

    companion object {
        const val DOCUMENT_INSPECT = "document_inspect"
        const val DOCUMENT_READ = "document_read"
        val TOOL_NAMES = listOf(DOCUMENT_INSPECT, DOCUMENT_READ)
    }
}

/** Stable disk representation for derived snapshots. Original documents remain untouched. */
object NovexDocumentSnapshotJsonCodec {
    private const val VERSION = 2

    fun encode(snapshot: NovexDocumentSnapshot): String = JSONObject()
        .put("version", VERSION)
        .put("ref", snapshot.ref.value)
        .put("sha256", snapshot.sha256)
        .put("parser_version", snapshot.parserVersion)
        .put("title", snapshot.title)
        .put("format", snapshot.format.wireName)
        .put("status", snapshot.status.wireName)
        .put("blocks", JSONArray(snapshot.blocks.map(::encodeBlock)))
        .put("warnings", JSONArray(snapshot.warnings.map(::encodeWarning)))
        .put("provenance", snapshot.provenance?.let(::encodeProvenance))
        .toString()

    fun decode(encoded: String): NovexDocumentSnapshot {
        val json = JSONObject(encoded)
        val version = json.getInt("version")
        require(version in 1..VERSION) { "不支持的文档快照版本" }
        return NovexDocumentSnapshot(
            ref = NovexResourceRef(json.getString("ref")),
            sha256 = json.getString("sha256"),
            parserVersion = json.getString("parser_version"),
            title = json.getString("title"),
            format = enumValue(json.getString("format"), NovexDocumentFormat.entries) { it.wireName },
            status = enumValue(json.getString("status"), NovexDocumentStatus.entries) { it.wireName },
            blocks = json.getJSONArray("blocks").objects().map(::decodeBlock),
            warnings = json.optJSONArray("warnings").objects().map(::decodeWarning),
            provenance = json.optJSONObject("provenance")?.let(::decodeProvenance),
        )
    }

    private fun encodeBlock(block: NovexDocumentBlock) = JSONObject()
        .put("id", block.id)
        .put("kind", block.kind.wireName)
        .put("order", block.order)
        .put("text", block.text)
        .put("heading_path", JSONArray(block.headingPath))
        .put("heading_level", block.headingLevel)
        .put("source", JSONObject()
            .put("part", block.source.part)
            .put("ordinal", block.source.ordinal)
            .put("page", block.source.page)
            .put("detail", block.source.detail))
        .put("media_ref", block.mediaRef?.value)

    private fun decodeBlock(json: JSONObject): NovexDocumentBlock {
        val source = json.getJSONObject("source")
        return NovexDocumentBlock(
            id = json.getString("id"),
            kind = enumValue(json.getString("kind"), NovexDocumentBlockKind.entries) { it.wireName },
            order = json.getInt("order"),
            text = json.getString("text"),
            headingPath = json.optJSONArray("heading_path").stringValues(),
            headingLevel = json.optionalInt("heading_level"),
            source = NovexDocumentSourceAnchor(
                part = source.getString("part"),
                ordinal = source.getInt("ordinal"),
                page = source.optionalInt("page"),
                detail = source.optionalString("detail"),
            ),
            mediaRef = json.optionalString("media_ref")?.let(::NovexResourceRef),
        )
    }

    private fun encodeWarning(warning: NovexDocumentWarning) = JSONObject()
        .put("code", warning.code)
        .put("message", warning.message)
        .put("block_id", warning.blockId)

    private fun decodeWarning(json: JSONObject) = NovexDocumentWarning(
        code = json.getString("code"),
        message = json.getString("message"),
        blockId = json.optionalString("block_id"),
    )

    private fun encodeProvenance(provenance: NovexDocumentProvenance) = JSONObject()
        .put("source_kind", provenance.sourceKind)
        .put("source_url", provenance.sourceUrl)
        .put("site_name", provenance.siteName)
        .put("page_id", provenance.pageId)
        .put("revision_id", provenance.revisionId)
        .put("revision_timestamp", provenance.revisionTimestamp)
        .put("license_title", provenance.licenseTitle)
        .put("license_url", provenance.licenseUrl)
        .put("retrieved_at_millis", provenance.retrievedAtMillis)

    private fun decodeProvenance(json: JSONObject) = NovexDocumentProvenance(
        sourceKind = json.getString("source_kind"),
        sourceUrl = json.getString("source_url"),
        siteName = json.optionalString("site_name"),
        pageId = json.optionalString("page_id"),
        revisionId = json.optionalString("revision_id"),
        revisionTimestamp = json.optionalString("revision_timestamp"),
        licenseTitle = json.optionalString("license_title"),
        licenseUrl = json.optionalString("license_url"),
        retrievedAtMillis = json.getLong("retrieved_at_millis"),
    )

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { array ->
        (0 until array.length()).map(array::getJSONObject)
    }.orEmpty()

    private fun JSONArray?.stringValues(): List<String> = this?.let { array ->
        (0 until array.length()).map(array::getString)
    }.orEmpty()

    private fun <T> enumValue(value: String, values: List<T>, wireName: (T) -> String): T =
        values.firstOrNull { wireName(it) == value }
            ?: throw IllegalArgumentException("文档快照包含不支持的枚举值")
}
