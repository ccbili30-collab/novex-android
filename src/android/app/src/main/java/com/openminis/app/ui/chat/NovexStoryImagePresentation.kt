package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.novex.domain.NovexSnapshotMedia
import com.openminis.app.novex.domain.NovexSnapshotMediaCodec
import java.io.File
import org.json.JSONObject

internal const val NOVEX_STORY_IMAGE = "novexStoryImage"
internal fun storyImageBlock(image: NovexSnapshotMedia, messageId: String) = AssistantBlock(
    id = "story-image:$messageId", kind = "info", toolName = NOVEX_STORY_IMAGE,
    content = image.label.ifBlank { "剧情插图" }, toolArgs = NovexSnapshotMediaCodec.encode(image).toString(), imageFilePath = image.asset.path)
internal fun readStoryImage(block: AssistantBlock): NovexSnapshotMedia? = if (block.toolName != NOVEX_STORY_IMAGE) null
    else runCatching { NovexSnapshotMediaCodec.decode(JSONObject(block.toolArgs)) }.getOrNull()

@Composable
internal fun NovexStoryImage(block: AssistantBlock) {
    val image = remember(block.toolArgs) { readStoryImage(block) }
    var failed by remember(image?.asset?.path) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val file = image?.asset?.path?.let(::File)
    val verified by produceState<Boolean?>(null, image?.asset?.path, image?.asset?.sha256, retry) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                require(file != null && file.isFile && file.length() in 1..64L * 1024 * 1024)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == image?.asset?.sha256
            }.getOrDefault(false)
        }
    }
    if (verified == null) Text("正在读取${block.content}")
    else if (verified != true || failed) {
        Column {
            Text("${block.content}暂时无法读取；正文已保留，请恢复原图片附件。")
            com.openminis.app.ui.novex.TextButton(onClick = { failed = false; retry++ }) { Text("重试") }
        }
    } else {
        AsyncImage(model = file, contentDescription = block.content, contentScale = ContentScale.Fit,
            onError = { failed = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 420.dp).clickable { expanded = true })
        if (expanded) com.openminis.app.ui.components.FullscreenImageViewer(model = requireNotNull(file), onDismiss = { expanded = false })
    }
}
