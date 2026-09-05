package com.openminis.app.novex.domain

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** The six stable areas exposed by a conversation workspace. */
enum class NovexWorkspaceArea(
    val wireName: String,
    val modelWritable: Boolean,
) {
    SOURCES("sources", false),
    NOTES("notes", true),
    DRAFTS("drafts", true),
    OUTPUTS("outputs", true),
    SAVES("saves", true),
    DERIVED("derived", false),
    ;

    companion object {
        fun fromWireName(value: String): NovexWorkspaceArea =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("不支持的工作区目录：$value")
    }
}

/**
 * One model execution sees its ancestor path plus its own pending reply branch.
 * Writes land on [writeBranchId], so retrying a reply never overwrites its sibling.
 */
data class NovexConversationWorkspaceScope(
    val conversationId: String,
    val visibleBranchIds: List<String>,
    val writeBranchId: String,
) {
    init {
        validateWorkspaceIdentifier(conversationId, "对话")
        visibleBranchIds.forEach { validateWorkspaceIdentifier(it, "分支") }
        validateWorkspaceIdentifier(writeBranchId, "写入分支")
        require(visibleBranchIds.distinct().size == visibleBranchIds.size) { "可见分支不能重复" }
    }

    internal fun canSee(branchId: String): Boolean =
        branchId == ROOT_BRANCH || branchId == writeBranchId || branchId in visibleBranchIds

    internal fun orderedBranches(): List<String> =
        (listOf(ROOT_BRANCH) + visibleBranchIds + writeBranchId).distinct()

    companion object {
        const val ROOT_BRANCH = "root"
    }
}

/** A readable, stable reference; its parser is the only path decoder used by storage. */
class NovexWorkspaceFileRef private constructor(
    val value: String,
    val conversationId: String,
    val branchId: String,
    val area: NovexWorkspaceArea,
    val relativePath: String,
) {
    fun asResourceRef() = NovexResourceRef(value)

    override fun equals(other: Any?): Boolean = other is NovexWorkspaceFileRef && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private const val PREFIX = "novex://workspaces/"

        fun create(
            scope: NovexConversationWorkspaceScope,
            area: NovexWorkspaceArea,
            relativePath: String,
        ): NovexWorkspaceFileRef = create(
            conversationId = scope.conversationId,
            branchId = scope.writeBranchId,
            area = area,
            relativePath = relativePath,
        )

        internal fun create(
            conversationId: String,
            branchId: String,
            area: NovexWorkspaceArea,
            relativePath: String,
        ): NovexWorkspaceFileRef {
            validateWorkspaceIdentifier(conversationId, "对话")
            validateWorkspaceIdentifier(branchId, "分支")
            val normalizedPath = normalizeWorkspacePath(relativePath)
            val value = buildString {
                append(PREFIX)
                append(encodeRefSegment(conversationId))
                append("/branches/")
                append(encodeRefSegment(branchId))
                append('/')
                append(area.wireName)
                append('/')
                append(normalizedPath.split('/').joinToString("/") { encodeRefSegment(it) })
            }
            return NovexWorkspaceFileRef(value, conversationId, branchId, area, normalizedPath)
        }

        fun parse(value: String): NovexWorkspaceFileRef {
            NovexResourceRef(value)
            require(value.startsWith(PREFIX)) { "不是 Novex 会话工作区引用" }
            val pieces = value.removePrefix(PREFIX).split('/')
            require(pieces.size >= 5 && pieces[1] == "branches") { "工作区引用结构无效" }
            val conversationId = decodeRefSegment(pieces[0])
            val branchId = decodeRefSegment(pieces[2])
            val area = NovexWorkspaceArea.fromWireName(pieces[3])
            val relativePath = pieces.drop(4).joinToString("/") { decodeRefSegment(it) }
            validateWorkspaceIdentifier(conversationId, "对话")
            validateWorkspaceIdentifier(branchId, "分支")
            return create(conversationId, branchId, area, relativePath)
        }
    }
}

data class NovexWorkspaceProvenance(
    val conversationId: String,
    val branchId: String,
    val messageId: String? = null,
    val toolCallId: String? = null,
    val sourceRefs: List<NovexResourceRef> = emptyList(),
) {
    init {
        validateWorkspaceIdentifier(conversationId, "来源对话")
        validateWorkspaceIdentifier(branchId, "来源分支")
        messageId?.let { validateWorkspaceIdentifier(it, "来源消息") }
        toolCallId?.let { validateWorkspaceIdentifier(it, "工具调用") }
        require(sourceRefs.distinct().size == sourceRefs.size) { "来源引用不能重复" }
    }
}

data class NovexWorkspaceEntry(
    val workspaceRef: NovexWorkspaceFileRef,
    val mimeType: String,
    val byteCount: Long,
    val sha256: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val artifactRef: NovexResourceRef? = null,
    val provenance: NovexWorkspaceProvenance,
) {
    init {
        require(mimeType.isNotBlank()) { "媒体类型不能为空" }
        require(byteCount >= 0) { "文件大小不能为负数" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "文件校验值必须是小写 SHA-256" }
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis) { "文件时间无效" }
        require(provenance.conversationId == workspaceRef.conversationId) { "来源对话与文件引用不一致" }
        require(provenance.branchId == workspaceRef.branchId) { "来源分支与文件引用不一致" }
    }
}

data class NovexWorkspaceSnapshot(
    val scope: NovexConversationWorkspaceScope,
    val entries: List<NovexWorkspaceEntry>,
)

/** Storage seam used by tools and the future Creation library UI. */
interface NovexConversationWorkspaceStore {
    fun inspect(scope: NovexConversationWorkspaceScope): NovexWorkspaceSnapshot
    fun find(scope: NovexConversationWorkspaceScope, ref: NovexWorkspaceFileRef): NovexWorkspaceEntry?
    fun readBytes(scope: NovexConversationWorkspaceScope, ref: NovexWorkspaceFileRef): ByteArray
    fun writeText(
        scope: NovexConversationWorkspaceScope,
        area: NovexWorkspaceArea,
        relativePath: String,
        content: String,
        mimeType: String,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry

    fun importArtifact(
        scope: NovexConversationWorkspaceScope,
        area: NovexWorkspaceArea,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry
}

/** Native export boundary. The Android layer supplies a user-selected destination implementation. */
fun interface NovexWorkspaceExportSink {
    fun write(fileName: String, mimeType: String, bytes: ByteArray)
}

class NovexWorkspaceArtifactExporter(
    private val store: NovexConversationWorkspaceStore,
) {
    fun export(
        scope: NovexConversationWorkspaceScope,
        workspaceRef: NovexWorkspaceFileRef,
        sink: NovexWorkspaceExportSink,
    ): NovexWorkspaceEntry {
        val entry = store.find(scope, workspaceRef)
            ?: throw NoSuchElementException("当前对话分支看不到指定工作区文件")
        sink.write(
            fileName = entry.workspaceRef.relativePath.substringAfterLast('/'),
            mimeType = entry.mimeType,
            bytes = store.readBytes(scope, entry.workspaceRef),
        )
        return entry
    }
}

/**
 * File-backed adapter. Absolute paths stay private; branch indices and content-addressed blobs
 * make restart, deduplication and copy-on-write behavior deterministic.
 */
class FileNovexConversationWorkspaceStore(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NovexConversationWorkspaceStore {
    init {
        require(root.exists() || root.mkdirs()) { "无法创建 Novex 会话工作区" }
        require(root.isDirectory) { "Novex 会话工作区根目录无效" }
    }

    @Synchronized
    override fun inspect(scope: NovexConversationWorkspaceScope): NovexWorkspaceSnapshot {
        val visible = linkedMapOf<String, NovexWorkspaceEntry>()
        scope.orderedBranches().forEach { branchId ->
            readIndex(scope.conversationId, branchId).forEach { entry ->
                visible[entry.workspaceRef.area.wireName + "/" + entry.workspaceRef.relativePath] = entry
            }
        }
        return NovexWorkspaceSnapshot(
            scope = scope,
            entries = visible.values.sortedWith(
                compareBy<NovexWorkspaceEntry> { it.workspaceRef.area.ordinal }
                    .thenBy { it.workspaceRef.relativePath },
            ),
        )
    }

    @Synchronized
    override fun find(
        scope: NovexConversationWorkspaceScope,
        ref: NovexWorkspaceFileRef,
    ): NovexWorkspaceEntry? {
        if (ref.conversationId != scope.conversationId || !scope.canSee(ref.branchId)) return null
        return readIndex(ref.conversationId, ref.branchId)
            .firstOrNull { it.workspaceRef == ref }
    }

    @Synchronized
    override fun readBytes(
        scope: NovexConversationWorkspaceScope,
        ref: NovexWorkspaceFileRef,
    ): ByteArray {
        val entry = find(scope, ref) ?: throw NoSuchElementException("找不到工作区文件")
        val file = entry.artifactRef?.let { artifactFile(entry.sha256) }
            ?: workspaceFile(ref)
        require(file.isFile) { "工作区文件内容不存在" }
        require(file.length() <= MAX_ARTIFACT_BYTES) { "工作区文件超过读取上限" }
        return file.readBytes()
    }

    @Synchronized
    override fun writeText(
        scope: NovexConversationWorkspaceScope,
        area: NovexWorkspaceArea,
        relativePath: String,
        content: String,
        mimeType: String,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry {
        require(area.modelWritable) { "工作区目录只读" }
        require(isTextMimeType(mimeType)) { "工作区文本写入不接受二进制媒体类型" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MODEL_TEXT_BYTES) { "工作区文本超过单文件上限" }
        return writeEntry(scope, area, relativePath, bytes, mimeType, provenance, forceArtifact = area == NovexWorkspaceArea.OUTPUTS)
    }

    @Synchronized
    override fun importArtifact(
        scope: NovexConversationWorkspaceScope,
        area: NovexWorkspaceArea,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry {
        require(area in setOf(NovexWorkspaceArea.SOURCES, NovexWorkspaceArea.OUTPUTS, NovexWorkspaceArea.DERIVED)) {
            "只有来源、成果和派生目录可以接收成果文件"
        }
        require(bytes.size <= MAX_ARTIFACT_BYTES) { "成果文件超过单文件上限" }
        return writeEntry(scope, area, relativePath, bytes, mimeType, provenance, forceArtifact = true)
    }

    private fun writeEntry(
        scope: NovexConversationWorkspaceScope,
        area: NovexWorkspaceArea,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String,
        provenance: NovexWorkspaceProvenance,
        forceArtifact: Boolean,
    ): NovexWorkspaceEntry {
        require(mimeType.isNotBlank()) { "媒体类型不能为空" }
        require(provenance.conversationId == scope.conversationId) { "写入来源对话与当前工作区不一致" }
        require(provenance.branchId == scope.writeBranchId) { "写入来源分支与当前工作区不一致" }
        val ref = NovexWorkspaceFileRef.create(scope, area, relativePath)
        val sha = sha256(bytes)
        val currentIndex = readIndex(scope.conversationId, scope.writeBranchId).toMutableList()
        val previous = currentIndex.firstOrNull { it.workspaceRef == ref }
        val now = nowMillis()
        val artifactRef = if (forceArtifact) {
            val target = artifactFile(sha)
            if (!target.exists()) atomicWrite(target, bytes)
            NovexResourceRef("novex://artifacts/sha256-$sha")
        } else {
            atomicWrite(workspaceFile(ref), bytes)
            null
        }
        val entry = NovexWorkspaceEntry(
            workspaceRef = ref,
            mimeType = mimeType,
            byteCount = bytes.size.toLong(),
            sha256 = sha,
            createdAtMillis = previous?.createdAtMillis ?: now,
            updatedAtMillis = now,
            artifactRef = artifactRef,
            provenance = provenance,
        )
        currentIndex.removeAll { it.workspaceRef == ref }
        currentIndex += entry
        writeIndex(scope.conversationId, scope.writeBranchId, currentIndex)
        return entry
    }

    private fun readIndex(conversationId: String, branchId: String): List<NovexWorkspaceEntry> {
        val index = indexFile(conversationId, branchId)
        if (!index.isFile) return emptyList()
        return runCatching {
            val json = JSONObject(index.readText(Charsets.UTF_8))
            require(json.getInt("version") == INDEX_VERSION) { "不支持的工作区索引版本" }
            val array = json.getJSONArray("entries")
            (0 until array.length()).map { decodeEntry(array.getJSONObject(it)) }.also { entries ->
                require(entries.all {
                    it.workspaceRef.conversationId == conversationId &&
                        it.workspaceRef.branchId == branchId
                }) { "工作区索引包含越界引用" }
            }
        }.getOrElse { failure ->
            throw IllegalStateException("Novex 会话工作区索引损坏", failure)
        }
    }

    private fun writeIndex(conversationId: String, branchId: String, entries: List<NovexWorkspaceEntry>) {
        val json = JSONObject()
            .put("version", INDEX_VERSION)
            .put("entries", JSONArray(entries.sortedBy { it.workspaceRef.value }.map(::encodeEntry)))
        atomicWrite(indexFile(conversationId, branchId), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun encodeEntry(entry: NovexWorkspaceEntry): JSONObject = JSONObject()
        .put("workspace_ref", entry.workspaceRef.value)
        .put("mime_type", entry.mimeType)
        .put("byte_count", entry.byteCount)
        .put("sha256", entry.sha256)
        .put("created_at_millis", entry.createdAtMillis)
        .put("updated_at_millis", entry.updatedAtMillis)
        .put("artifact_ref", entry.artifactRef?.value)
        .put("provenance", JSONObject()
            .put("conversation_id", entry.provenance.conversationId)
            .put("branch_id", entry.provenance.branchId)
            .put("message_id", entry.provenance.messageId)
            .put("tool_call_id", entry.provenance.toolCallId)
            .put("source_refs", JSONArray(entry.provenance.sourceRefs.map { it.value })))

    private fun decodeEntry(json: JSONObject): NovexWorkspaceEntry {
        val provenance = json.getJSONObject("provenance")
        return NovexWorkspaceEntry(
            workspaceRef = NovexWorkspaceFileRef.parse(json.getString("workspace_ref")),
            mimeType = json.getString("mime_type"),
            byteCount = json.getLong("byte_count"),
            sha256 = json.getString("sha256"),
            createdAtMillis = json.getLong("created_at_millis"),
            updatedAtMillis = json.getLong("updated_at_millis"),
            artifactRef = json.optionalString("artifact_ref")?.let(::NovexResourceRef),
            provenance = NovexWorkspaceProvenance(
                conversationId = provenance.getString("conversation_id"),
                branchId = provenance.getString("branch_id"),
                messageId = provenance.optionalString("message_id"),
                toolCallId = provenance.optionalString("tool_call_id"),
                sourceRefs = provenance.getJSONArray("source_refs").let { refs ->
                    (0 until refs.length()).map { NovexResourceRef(refs.getString(it)) }
                },
            ),
        )
    }

    private fun workspaceFile(ref: NovexWorkspaceFileRef): File {
        val areaRoot = File(branchRoot(ref.conversationId, ref.branchId), ref.area.wireName)
        val result = File(areaRoot, ref.relativePath).canonicalFile
        val canonicalArea = areaRoot.canonicalFile
        require(result.path.startsWith(canonicalArea.path + File.separator)) { "工作区路径越界" }
        return result
    }

    private fun conversationRoot(conversationId: String): File =
        File(File(root, "conversations"), stableDirectoryName(conversationId))

    private fun branchRoot(conversationId: String, branchId: String): File =
        File(File(conversationRoot(conversationId), "branches"), stableDirectoryName(branchId))

    private fun indexFile(conversationId: String, branchId: String): File =
        File(branchRoot(conversationId, branchId), ".novex-index.json")

    private fun artifactFile(sha256: String): File =
        File(File(File(root, "artifacts"), sha256.take(2)), sha256)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        require(target.parentFile.exists() || target.parentFile.mkdirs()) { "无法创建工作区目录" }
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(bytes)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    companion object {
        private const val INDEX_VERSION = 1
        const val MAX_MODEL_TEXT_BYTES = 1_048_576
        const val MAX_ARTIFACT_BYTES = 64 * 1_048_576
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

internal fun isTextMimeType(mimeType: String): Boolean =
    mimeType.startsWith("text/") || mimeType in setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
    )

private fun validateWorkspaceIdentifier(value: String, label: String) {
    require(value.isNotBlank()) { "$label 编号不能为空" }
    require(value.length <= 256) { "$label 编号过长" }
    require(value.none { it.isISOControl() }) { "$label 编号包含控制字符" }
}

private fun normalizeWorkspacePath(value: String): String {
    require(value.isNotBlank() && value.length <= 1024) { "工作区相对路径无效" }
    require(!value.startsWith('/')) { "工作区路径必须是相对路径" }
    require('\\' !in value && '\u0000' !in value) { "工作区路径包含不安全字符" }
    val segments = value.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." || ".." in it }) {
        "工作区路径包含路径逃逸"
    }
    return segments.joinToString("/")
}

private fun stableDirectoryName(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

private fun encodeRefSegment(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        if (
            unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned in setOf('-'.code, '_'.code, '~'.code)
        ) {
            append(unsigned.toChar())
        } else {
            append('%')
            append("%02X".format(unsigned))
        }
    }
}

private fun decodeRefSegment(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length) { "资源引用转义不完整" }
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                ?: throw IllegalArgumentException("资源引用转义无效")
            bytes += decoded.toByte()
            index += 3
        } else {
            require(value[index].code < 128) { "资源引用必须转义非 ASCII 字符" }
            bytes += value[index].code.toByte()
            index += 1
        }
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
}
