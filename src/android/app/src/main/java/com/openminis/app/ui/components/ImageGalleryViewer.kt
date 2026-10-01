package com.openminis.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.size.Size
import kotlinx.coroutines.launch
import novex.android.ui.NovexIcons

/**
 * One image in an [ImageGalleryViewer]. [model] is anything Coil's
 * `AsyncImage` accepts (Uri, File, String URL, etc.); [caption] is the
 * filename/alt-text shown in the bottom capsule, hidden when blank.
 */
data class ImageGalleryItem(
    val model: Any,
    val caption: String? = null,
)

/**
 * 全屏可滑动画廊（对齐 iOS MessageImageGallery）：HorizontalPager 承载
 * 每页独立的缩放/平移面，沉浸 Dialog，点按切换 chrome，底部字幕胶囊 +
 * 复制/分享/保存动作绑定当前页。
 *
 * 边角：`items.size == 1` 正常渲染（单页 Pager 无横向滑动副作用）；
 * `startIndex` 会 clamp 进界；页面放大时页内手势先吃掉横向滑动，Pager
 * 不会在缩放中翻页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGalleryViewer(
    items: List<ImageGalleryItem>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        // 防御：空列表不组 Pager，直接关。
        DisposableEffect(Unit) {
            onDismiss()
            onDispose { }
        }
        return
    }

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

        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, items.size - 1),
            pageCount = { items.size },
        )
        var showChrome by remember { mutableStateOf(true) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Pager 垫底：页内指针输入（捏合/平移/双击）优先于外层对话框
            // 的点按关闭。
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize()) {
                    ZoomableViewerImage(
                        model = items[page].model,
                        onTap = { showChrome = !showChrome },
                    )
                }
            }

            ViewerCloseButton(visible = showChrome, onDismiss = onDismiss)

            val current = items.getOrNull(pagerState.currentPage) ?: items[0]
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                ) {
                    // 字幕胶囊——对齐 iOS GalleryPage；无 caption（裸 markdown
                    // 图无 alt）时隐藏。
                    if (!current.caption.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .background(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = current.caption,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    ViewerActionPill {
                        ImageActionButton(
                            icon = NovexIcons.ContentCopy,
                            label = stringResource(R.string.image_action_copy),
                            onClick = { copyBitmapToClipboard(context, scope, current.model) },
                        )
                        var sharing by remember { mutableStateOf(false) }
                        ImageActionButton(
                            icon = NovexIcons.Share,
                            label = stringResource(R.string.image_action_share),
                            onClick = onClick@{
                                if (sharing) return@onClick
                                sharing = true
                                scope.launch {
                                    try {
                                        shareImage(context, current.model)
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
                                    val bmp = loadBitmap(context, current.model, Size.ORIGINAL)
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
