package com.openminis.app.novex.domain

import java.io.File

/**
 * Small file-backed store for derived document snapshots.
 *
 * Files are addressed only by a validated SHA-256 value, so model-provided references cannot
 * escape [directory]. A corrupt cache entry is ignored and can be rebuilt from the untouched
 * source document.
 */
class FileNovexDocumentSnapshotRepository(
    private val directory: File,
) : NovexDocumentSnapshotCache, NovexDocumentSnapshotStore {
    init {
        directory.mkdirs()
    }

    @Synchronized
    override fun find(key: NovexDocumentSnapshotCacheKey): NovexDocumentSnapshot? {
        requireSha256(key.sha256)
        return read(key.sha256)?.takeIf { snapshot ->
            snapshot.sha256.equals(key.sha256, ignoreCase = true) &&
                snapshot.parserVersion == key.parserVersion
        }
    }

    @Synchronized
    override fun find(ref: NovexResourceRef): NovexDocumentSnapshot? {
        val sha256 = ref.value.removePrefix(DOCUMENT_REF_PREFIX)
        if (ref.value != "$DOCUMENT_REF_PREFIX$sha256" || !isSha256(sha256)) return null
        return read(sha256)?.takeIf { it.ref == ref }
    }

    @Synchronized
    override fun store(key: NovexDocumentSnapshotCacheKey, snapshot: NovexDocumentSnapshot) {
        requireSha256(key.sha256)
        require(snapshot.sha256.equals(key.sha256, ignoreCase = true)) {
            "文档快照校验值与缓存键不一致"
        }
        require(snapshot.parserVersion == key.parserVersion) {
            "文档快照解析器版本与缓存键不一致"
        }
        directory.mkdirs()
        val target = fileFor(key.sha256)
        val temporary = File(directory, ".${key.sha256.lowercase()}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(NovexDocumentSnapshotJsonCodec.encode(snapshot), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                check(temporary.delete()) { "无法清理文档快照临时文件" }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun read(sha256: String): NovexDocumentSnapshot? = runCatching {
        NovexDocumentSnapshotJsonCodec.decode(fileFor(sha256).readText(Charsets.UTF_8))
    }.getOrNull()

    private fun fileFor(sha256: String) = File(directory, "${sha256.lowercase()}.json")

    private fun requireSha256(value: String) {
        require(isSha256(value)) { "文档缓存键必须是 SHA-256" }
    }

    private fun isSha256(value: String) = value.matches(SHA256_PATTERN)

    companion object {
        private const val DOCUMENT_REF_PREFIX = "novex://documents/"
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
