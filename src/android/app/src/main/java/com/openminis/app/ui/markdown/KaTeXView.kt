package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger

private const val TAG = "KaTeXView"

/**
 * [GH#206] 公式位图捕获的硬顶，**物理像素**口径。与 KatexWebViewPool 的
 * 常量对齐——Android 8+ 位图像素在 NATIVE 堆，而这些位置此前完全没有上
 * 限：一条宽体展示式能吃掉几 MB。超限公式按比例缩**小**（绝不裁剪），
 * 用清晰度换内存。
 */
private const val MAX_BITMAP_EDGE_PX = 2048
private const val MAX_BITMAP_PIXELS = 4_000_000

/**
 * 用离屏 WebView 里的 KaTeX 渲染 LaTeX 串并捕获成位图；LRU 缓存避免重
 * 复渲染同一表达式（血统清剿 P3.7 就地真重写；注入协议——页面加载完成
 * 后求值 `renderMath('<escaped>', displayMode, fontSize, isDark)`，JS 侧
 * 经 `AndroidBridge.onRendered(width, height, error)` 回桥——与缓存键格
 * 式、位图钳制常量为契约冻结面）。
 */
object KaTeXRendererCache {
    /**
     * [width]/[height] 是位图的物理像素尺寸（CSS px × 设备密度），保高清
     * 屏上的清晰度；[cssWidth]/[cssHeight] 是 KaTeX 上报的 CSS 像素尺寸，
     * 作 `Image` 的 dp 尺寸——公式以与周围文本相同的视觉比例显示，而不是
     * 位图像素的原始比例（T206）。
     */
    data class CacheEntry(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val cssWidth: Int,
        val cssHeight: Int,
    )

    /**
     * [GH#206] 字节预算，对齐 KatexWebViewPool。
     *
     * 这里曾是 `LruCache(200)`——容量按**条目数**计、无 `sizeOf`，存的是
     * ARGB_8888 位图（Android 8+ 像素在 NATIVE 堆）。200 条大公式能钉住
     * 数百 MB 且无人释放：这是进程生命周期的 `object`，缓存还引用着它们
     * 时 Java GC 不会回收 native 位图像素。
     *
     * 注意：逐出**不** recycle。位图已交给 Compose（`asImageBitmap()`），
     * 屏上的公式可能活得比缓存条目久；recycle 会让组合崩溃。丢掉引用，
     * 等没人再画它时 GC 自然收走。KatexWebViewPool 有同款注记。
     */
    private val cacheBudgetBytes: Int = run {
        val maxHeap = Runtime.getRuntime().maxMemory()
        (maxHeap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    val cache = object : LruCache<String, CacheEntry>(cacheBudgetBytes) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    /** [GH#206] 清空全部公式缓存。任何时刻安全——见上注。 */
    fun evictAll() {
        val before = cache.size()
        cache.evictAll()
        android.util.Log.i(
            "KaTeXRendererCache",
            "evictAll: released ~${before / 1024}KB of cached formula bitmaps",
        )
    }

    fun cacheKey(latex: String, displayMode: Boolean): String =
        (if (displayMode) "D:" else "I:") + latex
}

/**
 * 展示模式 LaTeX 数学块的可组合渲染。
 */
@Composable
fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        KaTeXRenderView(latex = latex, displayMode = true)
    }
}

/**
 * KaTeX 核心渲染组合件：隐藏 WebView 渲 LaTeX → 捕获成 Bitmap → Image 呈
 * 现；出错回落等宽文本。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXRenderView(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val darkTheme = isSystemInDarkTheme()
    val fontSize = 16f
    val key = remember(latex, displayMode) {
        KaTeXRendererCache.cacheKey(latex, displayMode)
    }

    // 命中缓存直接出图。
    val hit = remember(key) { KaTeXRendererCache.cache.get(key) }
    if (hit != null) {
        // T206：位图按密度 × CSS px 渲染保清晰，但按 CSS 像素 dp 显示——
        // 公式与周围 16sp 文本视觉同阶（否则 Image 把物理像素 1:1 映射，
        // 公式放大 ~密度 倍）。
        Image(
            bitmap = hit.bitmap.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(hit.cssWidth.dp, hit.cssHeight.dp),
        )
        return
    }

    // 渲染产物状态（随 key 重置）。
    var capturedBitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    // T206：位图旁边记 CSS 尺寸——渲染完成后的 Image 与缓存命中路径同
    // 法自定尺寸。
    var capturedCssWidth by remember(key) { mutableStateOf(0) }
    var capturedCssHeight by remember(key) { mutableStateOf(0) }
    var failureReason by remember(key) { mutableStateOf<String?>(null) }

    when {
        capturedBitmap != null -> Image(
            bitmap = capturedBitmap!!.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(capturedCssWidth.dp, capturedCssHeight.dp),
        )
        failureReason != null -> Text(
            // 回落：等宽字体显示原始 LaTeX。
            text = latex,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        else -> {
            // 渲染期间占位。
            Box(
                modifier = modifier.then(
                    if (displayMode) Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                    else Modifier
                        .widthIn(min = 20.dp)
                        .height(20.dp)
                ),
            )

            // 离屏 WebView 渲染。
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply wiring@{
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        // T208 A 层：WebView 文字缩放恒锁 100%，不理会用
                        // 户的无障碍字体设置。否则 KaTeX 渲出的位图被系统
                        // 字体倍率放大，而桥上报的是缩放前 CSS 尺寸——
                        // Image 把（超渲的）位图塞进偏小的框，
                        // ContentScale.Fit 让公式看起来比周围 16sp 文本物
                        // 理上更大。
                        settings.textZoom = 100
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        addJavascriptInterface(object {
                            /** [GH#206] 钳制因子：边长顶与像素总量顶取更紧者。 */
                            private fun captureScale(bitmapW: Int, bitmapH: Int): Float {
                                val edgeScale = minOf(
                                    1f,
                                    MAX_BITMAP_EDGE_PX.toFloat() / bitmapW,
                                    MAX_BITMAP_EDGE_PX.toFloat() / bitmapH,
                                )
                                val totalPx = bitmapW.toLong() * bitmapH
                                val pixelScale =
                                    if (totalPx > MAX_BITMAP_PIXELS) {
                                        kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / totalPx).toFloat()
                                    } else 1f
                                return minOf(edgeScale, pixelScale)
                            }

                            /** 按钳制因子截屏并写缓存/组合态。 */
                            private fun captureNow(
                                cssW: Int,
                                cssH: Int,
                                bitmapW: Int,
                                bitmapH: Int,
                            ) {
                                // [GH#206] 钳制捕获位图。bitmapW/H 此前无
                                // 界——宽体展示式一条就分配数 MB NATIVE 堆。
                                // WebView 保持完整布局尺寸（外层已设）所以
                                // 公式布局不变；只有捕获位图按比例缩小、绝
                                // 不裁剪，用清晰度换内存。
                                val factor = captureScale(bitmapW, bitmapH)
                                val capW = (bitmapW * factor).toInt().coerceAtLeast(1)
                                val capH = (bitmapH * factor).toInt().coerceAtLeast(1)
                                if (factor < 1f) {
                                    AppLogger.info(
                                        TAG,
                                        "bitmap clamped ${bitmapW}x$bitmapH -> ${capW}x$capH " +
                                            "(scale=$factor)",
                                    )
                                }
                                val shot = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(shot)
                                if (factor < 1f) canvas.scale(factor, factor)
                                draw(canvas)
                                KaTeXRendererCache.cache.put(
                                    key,
                                    KaTeXRendererCache.CacheEntry(
                                        // 记**实际**位图尺寸而非钳前请求
                                        // ——`width`/`height` 描述位图物理像
                                        // 素。显示尺寸用 cssWidth/cssHeight
                                        // （未变），被钳的公式布局完全一致。
                                        bitmap = shot,
                                        width = capW,
                                        height = capH,
                                        cssWidth = cssW,
                                        cssHeight = cssH,
                                    ),
                                )
                                capturedCssWidth = cssW
                                capturedCssHeight = cssH
                                capturedBitmap = shot
                            }

                            @JavascriptInterface
                            fun onRendered(width: Int, height: Int, error: String) {
                                if (error.isNotEmpty()) {
                                    AppLogger.warning(TAG, "KaTeX render failed: $error · latex=${latex.take(80)}")
                                    failureReason = error
                                    return
                                }
                                if (width <= 0 || height <= 0) {
                                    AppLogger.warning(TAG, "KaTeX render produced zero dimensions · latex=${latex.take(80)}")
                                    failureReason = "zero dimensions"
                                    return
                                }
                                // 乘设备密度。
                                val densityScale = ctx.resources.displayMetrics.density
                                val bitmapW = (width * densityScale).toInt()
                                val bitmapH = (height * densityScale).toInt()

                                // 先把 WebView 调到内容尺寸再捕获。
                                post {
                                    layoutParams = ViewGroup.LayoutParams(bitmapW, bitmapH)
                                    requestLayout()
                                    postDelayed(
                                        { captureNow(width, height, bitmapW, bitmapH) },
                                        100,
                                    )
                                }
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val escaped = latex
                                    .replace("\\", "\\\\")
                                    .replace("'", "\\'")
                                    .replace("\n", "\\n")
                                    .replace("\r", "")
                                evaluateJavascript(
                                    "renderMath('$escaped', $displayMode, $fontSize, $darkTheme)",
                                    null,
                                )
                            }
                        }

                        loadUrl("file:///android_asset/katex/katex-render.html")
                    }
                },
                modifier = Modifier.height(0.dp), // 隐藏
            )
        }
    }
}
