package com.openminis.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.size.Size
import kotlinx.coroutines.launch
import novex.android.ui.NovexIcons

/**
 * 全屏沉浸图片预览：捏合缩放、拖动平移、点按切换 chrome、
 * 复制 / 分享 / 保存三动作。沉浸与缩放的具体机制在 ImageViewerSupport。
 */
@Composable
fun FullscreenImageViewer(
    model: Any,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ImmerseActivityWindow()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ImmerseDialogWindow()

        var showChrome by remember { mutableStateOf(true) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableViewerImage(model = model, onTap = { showChrome = !showChrome })

            ViewerCloseButton(visible = showChrome, onDismiss = onDismiss)

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
                    ViewerActionPill {
                    // T139: 复制是自包含协程——IO 线程解码/写文件、授权
                    // URI 读权限、回主线程 Toast。
                    ImageActionButton(
                        icon = NovexIcons.ContentCopy,
                        label = stringResource(R.string.image_action_copy),
                        onClick = { copyBitmapToClipboard(context, scope, model) },
                    )
                    // T197: in-flight 去重——连点不起 N 个 loadBitmap
                    // 协程（大 PNG 上直接 OOM）。
                    var sharing by remember { mutableStateOf(false) }
                    ImageActionButton(
                        icon = NovexIcons.Share,
                        label = stringResource(R.string.image_action_share),
                        onClick = onClick@{
                            if (sharing) return@onClick
                            sharing = true
                            scope.launch {
                                try {
                                    shareImage(context, model)
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                    )
                    val savedMsg = stringResource(R.string.image_saved_to_album_toast)
                    val failedMsg = stringResource(R.string.image_save_failed_toast)
                    ImageActionButton(
                        icon = NovexIcons.Download,
                        label = stringResource(R.string.image_action_save),
                        onClick = {
                            scope.launch {
                                val bmp = loadBitmap(context, model, Size.ORIGINAL)
                                if (bmp != null) {
                                    val ok = saveToGallery(context, bmp)
                                    Toast.makeText(
                                        context,
                                        if (ok) savedMsg else failedMsg,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                    }
                }
            }
        }
    }
}

/** 右上角关闭钮，随 chrome 淡入淡出。 */
@Composable
internal fun ViewerCloseButton(visible: Boolean, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.statusBarsPadding().padding(8.dp),
            ) {
                Icon(
                    NovexIcons.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * T170: 底部动作胶囊。查看器画布恒为纯黑，原来的 0xCC1C1C1E 半透明
 * 胶囊会融进画布看不见边缘——提到 iOS Photos 的 #3A3A3C（比
 * systemGray6 深一档）并加 1dp 白@15% 描边，不透明底色也让文字对比
 * 更稳。
 */
@Composable
internal fun ViewerActionPill(content: @Composable () -> Unit) {
    val pillShape = RoundedCornerShape(32.dp)
    Row(
        modifier = Modifier
            .background(color = Color(0xFF3A3A3C), shape = pillShape)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.15f), shape = pillShape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
