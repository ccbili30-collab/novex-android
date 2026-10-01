package com.openminis.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.imageLoader
import coil.compose.AsyncImage
import coil.size.Size
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

// 图片查看器共享支撑：沉浸窗口管理、可缩放画面、位图 IO 动作。
// FullscreenImageViewer / ImageGalleryViewer 都从这套件组出来。

// ── 沉浸模式 ─────────────────────────────────────────────────────────────────

/**
 * T169: 查看器存续期间隐藏系统栏，退出时按快照还原。
 *
 * T152 的第一版同时翻转了 Activity 窗口的 decorFitsSystemWindows——而
 * MainActivity 启动时已 enableEdgeToEdge()（decorFits=false），dispose
 * 路径反而把 Activity 退出了 edge-to-edge，首次关闭查看器后系统栏带着
 * 平台默认灰底回来且再也无法恢复。这里只动外观标志和栏色，快照前值、
 * dispose 还原；decorFits 是 MainActivity 的生命周期职责，不碰。
 */
@Composable
internal fun ImmerseActivityWindow() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
            ?: findWindowAbove(view)
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val prevStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val prevNavBarColor = window?.navigationBarColor

        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            prevLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevLightNav?.let { controller.isAppearanceLightNavigationBars = it }
            @Suppress("DEPRECATION")
            prevStatusBarColor?.let { window.statusBarColor = it }
            @Suppress("DEPRECATION")
            prevNavBarColor?.let { window.navigationBarColor = it }
        }
    }
}

/**
 * T152/T169: 查看器 Dialog 自己的窗口也要上沉浸标志（外观标志翻成
 * 深色栏浅色图标，避免瞬态露出的系统栏显示 Activity 浅色主题的深色
 * 图标）。对话框窗口随查看器销毁，无需还原——Activity 那侧由
 * [ImmerseActivityWindow] 负责。
 */
@Composable
internal fun ImmerseDialogWindow() {
    val dialogContainer = LocalView.current.parent as? ViewGroup
    DisposableEffect(dialogContainer) {
        dialogContainer?.let { findWindowAbove(it) }?.let { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            val ctrl = WindowInsetsControllerCompat(w, w.decorView)
            ctrl.isAppearanceLightStatusBars = false
            ctrl.isAppearanceLightNavigationBars = false
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { }
    }
}

/** 沿 parent 链向上找宿主 Activity 的 Window（dialog ViewRootImpl 一样走通）。 */
private fun findWindowAbove(view: View): Window? {
    var p: ViewParent? = view.parent
    while (p != null) {
        val v = p as? View
        val ctx = v?.context
        if (ctx is android.app.Activity) return ctx.window
        p = v?.parent
    }
    return null
}

// ── 可缩放画面 ───────────────────────────────────────────────────────────────

internal const val VIEWER_ZOOM_MAX = 8f
internal const val VIEWER_DOUBLE_TAP_ZOOM = 2.5f

/**
 * 自带捏合缩放/平移/双击缩放/单击回调的图片面。翻页器里每页一个实例，
 * 各自持有变换状态——切页离组即重置缩放。放大时 pointerInput 先吃掉
 * 横向手势，外层 Pager 不会在缩放中翻页（对齐 iOS UIScrollView 挡
 * TabView 滑动的行为）。
 */
@Composable
internal fun ZoomableViewerImage(model: Any, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = panX,
                translationY = panY,
            )
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, VIEWER_ZOOM_MAX)
                    if (scale > 1f) {
                        panX += pan.x
                        panY += pan.y
                    } else {
                        panX = 0f
                        panY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; panX = 0f; panY = 0f
                        } else {
                            scale = VIEWER_DOUBLE_TAP_ZOOM
                        }
                    },
                    onTap = { onTap() },
                )
            },
    )
}

// ── 动作按钮 ─────────────────────────────────────────────────────────────────

@Composable
internal fun ImageActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── 位图 IO ─────────────────────────────────────────────────────────────────

internal suspend fun loadBitmap(
    context: Context,
    model: Any,
    // [T-memory-cap-and-storage] 复制/分享默认 2048 边（下游还会再压）；
    // 存相册是导出产物本身，传 Size.ORIGINAL 保原图（净眼退回件）。
    size: Size = Size(2048, 2048),
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        // [T-memory-cap-and-storage] 每次新建 ImageLoader 各带独立缓存，
        // allowHardware(false)+toBitmap 会把全尺寸像素压进 Java heap
        //（4K 约 38MB）——用全局单例，常驻像素仍受 128MB 封顶。
        val req = ImageRequest.Builder(context)
            .data(model)
            .allowHardware(false)
            .size(size)
            .build()
        (context.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
    } catch (e: Exception) {
        null
    }
}

/** T207: 临时 PNG 落在 cache/share/ 下——FileProvider 只声明了
 *  <cache-path name="share">，cacheDir 根目录的文件会在 getUriForFile
 *  抛 IllegalArgumentException。 */
private fun newShareTempFile(context: Context, prefix: String): File {
    val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
    return File(shareDir, "${prefix}_${System.currentTimeMillis()}.png")
}

private fun Bitmap.writeTo(file: File) {
    file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun fileProviderUri(context: Context, file: File) =
    androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file)

/**
 * T139: 把当前图片复制进系统剪贴板。旧实现两个会在大图崩掉的问题：
 *  1) bitmap.compress() 跑在主线程（scope.launch 默认 Main），多 MB
 *     PNG 编码直接 ANR；
 *  2) 剪贴板 URI 没 grantUriPermission+FLAG_GRANT_READ_URI_PERMISSION，
 *     读取方（任何读剪贴板的进程）对我们的 FileProvider authority 抛
 *     SecurityException。
 * 修复版在 IO 线程解码+写临时文件、显式给所有包授读权、回 Main 落
 * 剪贴板+Toast。每条路径都有 Toast，用户看得到结果。
 */
internal fun copyBitmapToClipboard(
    context: Context,
    scope: CoroutineScope,
    model: Any,
) {
    scope.launch(Dispatchers.IO) {
        try {
            val bitmap = loadBitmap(context, model) ?: error("decode failed")
            val file = newShareTempFile(context, "clipboard_img")
            bitmap.writeTo(file)
            val uri = fileProviderUri(context, file)
            // 给所有读剪贴板的进程授读权——否则 Photos/Gboard/Files 读
            // openInputStream(uri) 时 SecurityException，部分 OEM 直接崩。
            context.grantUriPermission("*", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            withContext(Dispatchers.Main) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                Toast.makeText(
                    context, context.getString(R.string.image_copied_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.image_copy_failed_toast, e.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

internal suspend fun shareImage(context: Context, model: Any) {
    // T197: 解码失败曾经静默 return，用户以为按钮没反应；且 chooser 缺
    // FLAG_ACTIVITY_NEW_TASK 时 LocalContext 解析成非 Activity（Dialog
    // 内 ContextWrapper）会抛 AndroidRuntimeException。现在每条失败路径
    // 都有 Toast，内层 intent 和 chooser 都打上 NEW_TASK。
    try {
        val bmp = loadBitmap(context, model) ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context, context.getString(R.string.image_load_failed_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        withContext(Dispatchers.IO) {
            val file = newShareTempFile(context, "share_img")
            bmp.writeTo(file)
            val uri = fileProviderUri(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(
                intent, context.getString(R.string.image_share_chooser_title),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.image_share_failed_toast, e.message ?: ""),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

internal suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val filename = "minis_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Minis",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                ) ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES,
                )
                val target = File(File(pictures, "Minis").apply { mkdirs() }, filename)
                bitmap.writeTo(target)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
