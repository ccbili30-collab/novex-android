package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * [ProviderListScreen] 的 zip 格式导入流程助手（血统清剿 P3.7 就地真重写；
 * zip-slip 守卫、跳过规则与回调契约为行为冻结面）。
 *
 * 把用户选的 .zip 经 [ZipInputStream] 流式解开，抽进
 * [Context.getCacheDir] 下的唯一子目录，再把每个受支持的叶子文件
 * （.json / .txt）喂给既有的单文件导入处理器。
 *
 * 对「zip slip」路径穿越家族加硬：每条 entry 解析成规范路径，逃出暂存根
 * 的一律拒绝。跳过 `__MACOSX/` 噪声、点文件与 `.DS_Store`。每条退出路径都
 * 清理暂存目录。
 */
internal object ProviderImportZip {

    private const val TAG = "ProviderImportZip"
    private val SUPPORTED_EXTS = setOf("json", "txt")

    /**
     * 读 [Uri] 的内容提供者报告的显示名。失败返回 null——调用方回落 MIME
     * 嗅探。
     */
    fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 解开 [uri] 背后的 zip，把每个 .json/.txt 条目过 [onImportSingle]，经
     * 回调分发摘要/失败 toast。返回前必拆暂存目录。
     *
     * @param onImportSingle 成功返回非空标签，"无效文件"返回 null——对齐
     *   既有供应商导入契约。
     */
    fun importFromZip(
        context: Context,
        uri: Uri,
        onImportSingle: (String) -> String?,
        onExtractFailed: () -> Unit,
        onNoSupported: () -> Unit,
        onSummary: (ok: Int, total: Int) -> Unit,
    ) {
        val stagingDir = createStagingDir(context)
            ?: run {
                onExtractFailed()
                return
            }
        val rootCanonical = stagingDir.canonicalPath
        val rootPrefix = rootCanonical + File.separator

        try {
            if (!extractAllEntries(context, uri, stagingDir, rootCanonical, rootPrefix)) {
                onExtractFailed()
                return
            }

            val leaves = mutableListOf<File>()
            collectSupportedFiles(stagingDir, leaves)
            if (leaves.isEmpty()) {
                onNoSupported()
                return
            }

            var okCount = 0
            for (file in leaves) {
                try {
                    if (onImportSingle(file.readText()) != null) okCount += 1
                } catch (e: Exception) {
                    Log.w(TAG, "skip import for ${file.name}: ${e.message}")
                }
            }
            onSummary(okCount, leaves.size)
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    /** 暂存子目录：cacheDir/import-staging/<uuid>；建不出返回 null。 */
    private fun createStagingDir(context: Context): File? {
        val stagingRoot = File(context.cacheDir, "import-staging")
        if (!stagingRoot.exists()) stagingRoot.mkdirs()
        val stagingDir = File(stagingRoot, UUID.randomUUID().toString())
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) return null
        return stagingDir
    }

    /** 流式解包全部条目；坏 zip / IO 异常返回 false。 */
    private fun extractAllEntries(
        context: Context,
        uri: Uri,
        stagingDir: File,
        rootCanonical: String,
        rootPrefix: String,
    ): Boolean {
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) return false
        try {
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    try {
                        if (entry.isDirectory) continue
                        if (shouldSkipEntry(entry.name)) continue
                        extractOneEntry(zin, entry.name, stagingDir, rootCanonical, rootPrefix)
                    } finally {
                        zin.closeEntry()
                    }
                }
            }
        } catch (e: ZipException) {
            Log.w(TAG, "zip extract failed", e)
            return false
        } catch (e: IOException) {
            Log.w(TAG, "io while extracting zip", e)
            return false
        }
        return true
    }

    private fun extractOneEntry(
        zin: ZipInputStream,
        rawName: String,
        stagingDir: File,
        rootCanonical: String,
        rootPrefix: String,
    ) {
        val target = File(stagingDir, rawName)
        val canonical = target.canonicalPath
        // ── zip-slip 守卫 ───────────────────────────────────────
        if (canonical != rootCanonical && !canonical.startsWith(rootPrefix)) {
            Log.w(TAG, "rejected zip-slip entry: $rawName")
            return
        }
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> zin.copyTo(out) }
    }

    private fun shouldSkipEntry(name: String): Boolean {
        if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) return true
        val leaf = name.substringAfterLast('/')
        if (leaf.isEmpty()) return true
        if (leaf == ".DS_Store") return true
        if (leaf.startsWith(".")) return true
        return false
    }

    private fun collectSupportedFiles(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectSupportedFiles(child, out)
            } else if (child.extension.lowercase() in SUPPORTED_EXTS) {
                out += child
            }
        }
    }
}
