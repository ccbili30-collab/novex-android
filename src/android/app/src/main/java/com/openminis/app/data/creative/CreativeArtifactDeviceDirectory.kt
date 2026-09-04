package com.openminis.app.data.creative

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CreativeArtifactDeviceDirectorySettings(
    val treeUri: String? = null,
    val autoCopyEnabled: Boolean = false,
) {
    val configured: Boolean get() = !treeUri.isNullOrBlank()
}

/**
 * Android Storage Access Framework adapter for a user-owned creative directory.
 * Novex remains the source of truth; this directory is an optional one-way export mirror.
 */
class CreativeArtifactDeviceDirectory(context: Context) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun settings(): CreativeArtifactDeviceDirectorySettings = CreativeArtifactDeviceDirectorySettings(
        treeUri = preferences.getString(KEY_TREE_URI, null),
        autoCopyEnabled = preferences.getBoolean(KEY_AUTO_COPY, false),
    )

    fun select(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val previous = settings().treeUri?.let(Uri::parse)
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        if (previous != null && previous != uri) release(previous)
    }

    fun clear() {
        settings().treeUri?.let(Uri::parse)?.let(::release)
        preferences.edit().remove(KEY_TREE_URI).putBoolean(KEY_AUTO_COPY, false).apply()
    }

    fun setAutoCopyEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_COPY, enabled && settings().configured).apply()
    }

    fun displayName(): String? = settings().treeUri?.let(Uri::parse)?.let { uri ->
        runCatching {
            DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "已选择文件夹" }
        }.getOrDefault("已选择文件夹")
    }

    suspend fun export(record: CreativeArtifactRecord, bytes: ByteArray): Uri = withContext(Dispatchers.IO) {
        val treeUri = settings().treeUri?.let(Uri::parse) ?: error("请先选择创作目录")
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val existingNames = childNames(treeUri, treeDocumentId)
        val displayName = nextAvailableCreativeArtifactName(
            creativeArtifactExportName(record),
            existingNames,
        )
        val documentUri = DocumentsContract.createDocument(
            resolver,
            parentUri,
            creativeArtifactMimeType(record),
            displayName,
        ) ?: error("无法在创作目录创建文件")
        resolver.openOutputStream(documentUri, "w")?.use { output -> output.write(bytes) }
            ?: error("无法写入创作目录")
        documentUri
    }

    suspend fun autoCopy(record: CreativeArtifactRecord, bytes: ByteArray): Uri? {
        if (!settings().autoCopyEnabled) return null
        return export(record, bytes)
    }

    private fun childNames(treeUri: Uri, treeDocumentId: String): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        return buildSet {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (nameColumn >= 0) cursor.getString(nameColumn)?.let(::add)
                }
            }
        }
    }

    private fun release(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private companion object {
        const val PREFERENCES = "novex_creative_device_directory"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_AUTO_COPY = "auto_copy"
    }
}
