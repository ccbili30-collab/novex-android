package com.openminis.app.novex.domain

enum class NovexSourceStatus(val wireName: String) {
    READY("ready"),
    EMPTY("empty"),
    OCR_REQUIRED("ocr_required"),
    PASSWORD_REQUIRED("password_required"),
    UNSUPPORTED("unsupported"),
    FAILED("failed"),
    EXACT_DUPLICATE("exact_duplicate"),
}

data class NovexSourceImportResult(
    val ref: NovexResourceRef,
    val title: String,
    val sha256: String,
    val document: NovexDocumentSnapshot? = null,
    val failureCode: String? = null,
) {
    init {
        require(ref.value.startsWith("novex://sources/")) { "资料来源必须使用来源引用" }
        require(title.isNotBlank()) { "资料标题不能为空" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "资料校验值必须是 SHA-256" }
        require(document != null || !failureCode.isNullOrBlank()) { "资料导入必须包含文档或失败原因" }
        require(document == null || document.sha256.equals(sha256, ignoreCase = true)) {
            "资料与文档快照校验值不一致"
        }
    }
}

data class NovexCollectionSource(
    val ref: NovexResourceRef,
    val title: String,
    val sha256: String,
    val status: NovexSourceStatus,
    val documentRef: NovexResourceRef? = null,
    val blockIds: List<String> = emptyList(),
    val duplicateOf: NovexResourceRef? = null,
    val possibleVersionOf: NovexResourceRef? = null,
    val similarityPercent: Int? = null,
    val failureCode: String? = null,
) {
    init {
        require(status != NovexSourceStatus.EXACT_DUPLICATE || duplicateOf != null) {
            "重复资料必须指向原始来源"
        }
        require(status == NovexSourceStatus.EXACT_DUPLICATE || duplicateOf == null) {
            "非重复资料不能声明重复来源"
        }
        require((possibleVersionOf == null) == (similarityPercent == null)) {
            "可能版本关系必须同时包含来源和相似度"
        }
        require(similarityPercent == null || similarityPercent in 0..100) { "资料相似度必须在零到一百之间" }
        require(status != NovexSourceStatus.EXACT_DUPLICATE || possibleVersionOf == null) {
            "完全重复资料不能同时标记为可能版本"
        }
        require(blockIds.distinct().size == blockIds.size) { "资料内容块编号必须唯一" }
    }
}

data class NovexSourceCollection(
    val ref: NovexResourceRef,
    val scopeRef: NovexResourceRef,
    val title: String,
    val sources: List<NovexCollectionSource>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    init {
        require(ref.value.startsWith("novex://source-collections/")) { "资料集引用无效" }
        require(scopeRef.value.startsWith("novex://conversation-branches/")) { "资料集必须属于对话分支" }
        require(title.isNotBlank()) { "资料集标题不能为空" }
        require(sources.isNotEmpty()) { "资料集至少需要一项来源" }
        require(sources.map { it.ref }.distinct().size == sources.size) { "资料来源引用不能重复" }
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis) { "资料集时间无效" }
    }

    val uniqueDocumentRefs: List<NovexResourceRef>
        get() = sources.mapNotNull { source ->
            source.documentRef.takeIf { source.status != NovexSourceStatus.EXACT_DUPLICATE }
        }.distinct()
}

/** Builds a collection from independent import outcomes without modifying any original source. */
object NovexSourceCollectionBuilder {
    fun create(
        ref: NovexResourceRef,
        scopeRef: NovexResourceRef,
        title: String,
        imports: List<NovexSourceImportResult>,
        nowMillis: Long,
    ): NovexSourceCollection {
        require(imports.isNotEmpty()) { "资料集至少需要一项导入结果" }
        val canonicalBySha = linkedMapOf<String, NovexResourceRef>()
        val versionSignatures = mutableListOf<VersionSignature>()
        val sources = imports.map { imported ->
            val sha = imported.sha256.lowercase()
            val canonical = canonicalBySha[sha]
            if (canonical != null) {
                NovexCollectionSource(
                    ref = imported.ref,
                    title = imported.title,
                    sha256 = sha,
                    status = NovexSourceStatus.EXACT_DUPLICATE,
                    documentRef = imported.document?.ref,
                    duplicateOf = canonical,
                )
            } else {
                canonicalBySha[sha] = imported.ref
                val document = imported.document
                val signature = document?.versionSignature()
                val possibleVersion = signature?.let { candidate ->
                    versionSignatures
                        .map { existing -> existing.ref to similarityPercent(existing.parts, candidate) }
                        .filter { (_, similarity) -> similarity >= MIN_VERSION_SIMILARITY_PERCENT }
                        .maxByOrNull { (_, similarity) -> similarity }
                }
                if (signature != null) versionSignatures += VersionSignature(imported.ref, signature)
                NovexCollectionSource(
                    ref = imported.ref,
                    title = imported.title,
                    sha256 = sha,
                    status = document?.status?.toSourceStatus() ?: NovexSourceStatus.FAILED,
                    documentRef = document?.ref,
                    blockIds = document?.blocks?.map { it.id }.orEmpty(),
                    possibleVersionOf = possibleVersion?.first,
                    similarityPercent = possibleVersion?.second,
                    failureCode = imported.failureCode ?: document?.warnings?.firstOrNull()?.code,
                )
            }
        }
        return NovexSourceCollection(
            ref = ref,
            scopeRef = scopeRef,
            title = title,
            sources = sources,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
    }

    private fun NovexDocumentStatus.toSourceStatus(): NovexSourceStatus = when (this) {
        NovexDocumentStatus.READY,
        NovexDocumentStatus.TRUNCATED,
        -> NovexSourceStatus.READY
        NovexDocumentStatus.EMPTY -> NovexSourceStatus.EMPTY
        NovexDocumentStatus.OCR_REQUIRED -> NovexSourceStatus.OCR_REQUIRED
        NovexDocumentStatus.PASSWORD_REQUIRED -> NovexSourceStatus.PASSWORD_REQUIRED
        NovexDocumentStatus.UNSUPPORTED -> NovexSourceStatus.UNSUPPORTED
        NovexDocumentStatus.DAMAGED -> NovexSourceStatus.FAILED
    }

    private fun NovexDocumentSnapshot.versionSignature(): Set<String>? {
        if (status != NovexDocumentStatus.READY && status != NovexDocumentStatus.TRUNCATED) return null
        val normalized = blocks.joinToString("\n") { it.text }
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(MAX_VERSION_SIGNATURE_CHARS)
        if (normalized.length < MIN_VERSION_SIGNATURE_CHARS) return null
        return normalized.windowed(
            size = VERSION_SHINGLE_CHARS,
            step = VERSION_SHINGLE_STEP,
            partialWindows = false,
        ).toSet()
    }

    private fun similarityPercent(first: Set<String>, second: Set<String>): Int {
        if (first.isEmpty() || second.isEmpty()) return 0
        val intersection = first.count { it in second }
        val union = first.size + second.size - intersection
        return ((intersection * 100.0) / union).toInt()
    }

    private data class VersionSignature(val ref: NovexResourceRef, val parts: Set<String>)

    private const val MIN_VERSION_SIMILARITY_PERCENT = 80
    private const val MIN_VERSION_SIGNATURE_CHARS = 80
    private const val MAX_VERSION_SIGNATURE_CHARS = 80_000
    private const val VERSION_SHINGLE_CHARS = 16
    private const val VERSION_SHINGLE_STEP = 8
}

enum class NovexDocumentReadMode {
    RETRIEVAL,
    FULL_REVIEW,
}

/**
 * Application-owned proof of source coverage.
 * Retrieval is intentionally excluded: a search hit is not evidence that the full source was read.
 */
class NovexReviewLedger private constructor(
    val collectionRef: NovexResourceRef,
    val readableBlocksByDocument: Map<NovexResourceRef, List<String>>,
    val reviewedBlocksByDocument: Map<NovexResourceRef, Set<String>>,
    val unreadableSourceRefs: List<NovexResourceRef>,
    val status: NovexLearningTaskStatus,
) {
    val totalReadableBlocks: Int
        get() = readableBlocksByDocument.values.sumOf { it.size }

    val reviewedBlocks: Int
        get() = reviewedBlocksByDocument.values.sumOf { it.size }

    val isComplete: Boolean
        get() = status == NovexLearningTaskStatus.COMPLETE ||
            status == NovexLearningTaskStatus.PARTIAL_FAILURE

    fun recordRead(
        documentRef: NovexResourceRef,
        blockIds: List<String>,
        mode: NovexDocumentReadMode,
    ): NovexReviewLedger {
        require(documentRef in readableBlocksByDocument) { "文档不属于资料集的可读范围" }
        require(blockIds.isNotEmpty()) { "通读记录至少需要一个内容块" }
        val readable = readableBlocksByDocument.getValue(documentRef).toSet()
        require(blockIds.all { it in readable }) { "通读记录包含不属于文档的内容块" }
        if (mode == NovexDocumentReadMode.RETRIEVAL || isComplete) return this

        val reviewed = reviewedBlocksByDocument.toMutableMap()
        reviewed[documentRef] = reviewed.getValue(documentRef) + blockIds
        val allCovered = readableBlocksByDocument.all { (ref, ids) ->
            ids.all { it in reviewed.getValue(ref) }
        }
        return NovexReviewLedger(
            collectionRef = collectionRef,
            readableBlocksByDocument = readableBlocksByDocument,
            reviewedBlocksByDocument = reviewed,
            unreadableSourceRefs = unreadableSourceRefs,
            status = when {
                !allCovered -> NovexLearningTaskStatus.REVIEWING
                unreadableSourceRefs.isNotEmpty() -> NovexLearningTaskStatus.PARTIAL_FAILURE
                else -> NovexLearningTaskStatus.COMPLETE
            },
        )
    }

    companion object {
        fun start(collection: NovexSourceCollection): NovexReviewLedger {
            val readable = collection.sources
                .filter { it.status == NovexSourceStatus.READY && it.documentRef != null }
                .associate { source -> requireNotNull(source.documentRef) to source.blockIds }
            val unreadable = collection.sources
                .filter { source ->
                    source.status != NovexSourceStatus.READY &&
                        source.status != NovexSourceStatus.EXACT_DUPLICATE
                }
                .map { it.ref }
            val reviewed = readable.keys.associateWith { emptySet<String>() }
            return NovexReviewLedger(
                collectionRef = collection.ref,
                readableBlocksByDocument = readable,
                reviewedBlocksByDocument = reviewed,
                unreadableSourceRefs = unreadable,
                status = when {
                    readable.isNotEmpty() -> NovexLearningTaskStatus.REVIEWING
                    unreadable.isNotEmpty() -> NovexLearningTaskStatus.PARTIAL_FAILURE
                    else -> NovexLearningTaskStatus.COMPLETE
                },
            )
        }

        internal fun restore(
            collectionRef: NovexResourceRef,
            readableBlocksByDocument: Map<NovexResourceRef, List<String>>,
            reviewedBlocksByDocument: Map<NovexResourceRef, Set<String>>,
            unreadableSourceRefs: List<NovexResourceRef>,
            status: NovexLearningTaskStatus,
        ): NovexReviewLedger {
            require(readableBlocksByDocument.keys == reviewedBlocksByDocument.keys) {
                "通读账本的文档范围不一致"
            }
            readableBlocksByDocument.forEach { (ref, readable) ->
                require(reviewedBlocksByDocument.getValue(ref).all { it in readable }) {
                    "通读账本包含未知内容块"
                }
            }
            return NovexReviewLedger(
                collectionRef = collectionRef,
                readableBlocksByDocument = readableBlocksByDocument,
                reviewedBlocksByDocument = reviewedBlocksByDocument,
                unreadableSourceRefs = unreadableSourceRefs,
                status = status,
            )
        }
    }
}

enum class NovexLearningNoteLevel(val wireName: String) {
    BLOCK("block"),
    SECTION("section"),
    FILE("file"),
    COLLECTION("collection"),
}

data class NovexLearningNote(
    val ref: NovexResourceRef,
    val level: NovexLearningNoteLevel,
    val title: String,
    val body: String,
    val sourceDocumentRefs: List<NovexResourceRef>,
    val sourceBlockIds: List<String> = emptyList(),
) {
    init {
        require(ref.value.startsWith("novex://learning-notes/")) { "学习笔记引用无效" }
        require(title.isNotBlank()) { "学习笔记标题不能为空" }
        require(body.isNotBlank()) { "学习笔记正文不能为空" }
        require(sourceDocumentRefs.isNotEmpty()) { "学习笔记必须保留来源文档" }
        require(sourceDocumentRefs.distinct().size == sourceDocumentRefs.size) { "学习笔记来源文档不能重复" }
        require(sourceBlockIds.distinct().size == sourceBlockIds.size) { "学习笔记来源内容块不能重复" }
        if (level == NovexLearningNoteLevel.BLOCK) {
            require(sourceBlockIds.isNotEmpty()) { "内容块笔记必须保留来源内容块" }
        }
    }
}

data class NovexLearningState(
    val collection: NovexSourceCollection,
    val reviewLedger: NovexReviewLedger,
    val notes: List<NovexLearningNote> = emptyList(),
    val task: NovexLearningTaskState? = null,
) {
    init {
        require(reviewLedger.collectionRef == collection.ref) { "通读账本与资料集不一致" }
        require(notes.map { it.ref }.distinct().size == notes.size) { "学习笔记引用不能重复" }
        val documents = collection.uniqueDocumentRefs.toSet()
        require(notes.all { note -> note.sourceDocumentRefs.all { it in documents } }) {
            "学习笔记不能引用资料集以外的文档"
        }
        require(task == null || task.collectionRef == collection.ref) { "学习任务与资料集不一致" }
    }
}
