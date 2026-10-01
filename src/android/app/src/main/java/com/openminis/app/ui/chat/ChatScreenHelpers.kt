package com.openminis.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

// 聊天输入栏的文件/相机辅助。

/**
 * 在 filesDir/camera-photos/ 下建空 JPEG 并返回 (FileProvider URI, File)，
 * 相机把成片直接写进这个 URI。放 filesDir 而非 cacheDir：MIUI 会在相机
 * 还在前台时清 cacheDir，把暂存文件弄丢。
 */
internal fun createCameraOutputUri(context: Context): Pair<Uri, File> {
    val dir = File(context.filesDir, "camera-photos").apply { mkdirs() }
    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg").apply { createNewFile() }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return uri to file
}

/** 读 content:// URI 的 DISPLAY_NAME；查不到返回 null。 */
internal fun getFileName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
