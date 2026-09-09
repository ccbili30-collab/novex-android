package com.openminis.app.share

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Writes the same completed package used by sharing, without repacking or modifying its contents. */
object NovexConversationBundleFileSaver {
    suspend fun save(resolver: ContentResolver, source: File, destination: Uri): Long = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0) { "对话包已失效，请重新导出" }
        val expected = source.length()
        var written = 0L
        val coroutine = currentCoroutineContext()
        source.inputStream().use { input ->
            requireNotNull(resolver.openOutputStream(destination, "wt")) { "无法打开所选保存位置" }.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    written += count
                }
                output.flush()
            }
        }
        check(written == expected && source.length() == expected) { "对话包未完整写入，请重新保存" }
        written
    }
}
