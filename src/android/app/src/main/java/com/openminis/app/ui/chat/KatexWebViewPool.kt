package com.openminis.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 单次渲染产物：位图 + CSS 尺寸（供气泡布局）。 */
internal class KatexRenderResult(val bitmap: Bitmap, val size: IntSize)

/**
 * T155：单个离屏 WebView，经 KaTeX 渲 LaTeX（含 mhchem 化学），返回位图
 * 供 markdown 行内气泡显示（血统清剿 P3.7 就地真重写；注入协议——
 * `renderMath('<escaped>', displayMode, fontSizePx, isDark)` 求值调用 +
 * `AndroidBridge.onRendered(w, h, err)` 单槽回桥、ASSET_HTML、缓存键格式
 * 与全部常量为契约冻结面）。
 *
 * 对齐 iOS KaTeXRenderer：WebView 从 assets 装载一次；每次渲染求值
 * `renderMath(...)`；KaTeX 排版完成后桥发 `AndroidBridge.onRendered(w,h,
 * err)`；按量得的 CSS 尺寸快照 `view.draw(Canvas)`。结果按
 * (latex, displayMode, fontSize, isDark) 缓存，重组期重渲免费。
 *
 * 并发：单 Mutex 串行化渲染请求——JS 桥只有一个挂起回调槽。共享同一
 * WebView 的请求队列足够：公式小、KaTeX 热身后每条几十毫秒。
 */
internal object KatexWebViewPool {

    private const val TAG = "KatexWebViewPool"
    private const val ASSET_HTML = "file:///android_asset/katex/katex-render.html"
    private const val DEFAULT_FONT_SIZE_PX = 17
    private const val RENDER_TIMEOUT_MS = 4_000L

    /**
     * 离屏 WebView 的布局视口，**物理像素**。KaTeX 要这么多像素宽才能把
     * 公式排开、再经 getBoundingClientRect() 上报量宽。密度 2.625 的设备
     * 上 8192 px ≈ 3120 CSS px——任何理智的单行展示式或宽矩阵都够。更小
     * 的值曾让 `I_c = W_c^{\text{non-private}} \cdot \alpha_c + …`（约
     * 1000 CSS px 宽）这类宽式在排版期被切，位图只剩最左边一截。
     */
    private const val LAYOUT_PIXELS = 8192

    /**
     * [GH#206] 单张快照的硬顶，**物理像素**。
     *
     * [LAYOUT_PIXELS] 是 WebView 的布局视口，快照曾直接取
     * `measuredCssPx * density` 且无上限——密度 2.75 设备上的宽展示式可要
     * 出逼近 8192 px 宽的位图。ARGB_8888 每像素 4 字节，一条公式 ~5 MB，
     * 而 Android 8+ 位图像素住在 NATIVE 堆。
     *
     * 超宽公式缩**小**而非裁剪：极端公式上失点清晰度，严格好过为它花
     * 5 MB native 堆；裁剪则是直接丢内容。
     */
    private const val MAX_BITMAP_EDGE_PX = 2048
    private const val MAX_BITMAP_PIXELS = 4_000_000 // ARGB_8888 下 ~16 MB

    /**
     * [GH#206] 渲染缓存的字节预算。
     *
     * 曾是 `LruCache(150)`——容量按**条目**计、无 `sizeOf` 覆写，150 条微
     * 小行内公式与 150 条数 MB 展示式被视为同等负载。叠加上面无界的快照
     * 尺寸，公式密集的会话能钉住数百 MB 没人释放的 native 堆（本 object
     * 是进程级单例，Java GC 不回收 native 位图像素）。
     *
     * 按应用自己的 Java 堆预算定容——纯粹当「这台设备多大」的比例信号；
     * 位图本体在 native。
     */
    private val cacheBudgetBytes: Int = run {
        val maxHeap = Runtime.getRuntime().maxMemory()
        (maxHeap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderGate = Mutex()

    /**
     * 关于回收：逐出时刻意**不** `recycle()`。`render()` 把位图交给
     * Compose（`produceState` → `asImageBitmap()`），组合可以在条目被逐出
     * 之后很久还在画屏上的公式。逐出即回收等于把活组合喂给一张废位图，
     * 直接 "trying to use a recycled bitmap" 崩溃。丢引用就够：没有组合
     * 持有它之后，位图的 native 像素由 GC/终结器释放。真正要紧的修复是
     * 给进缓存的东西定量——上面的预算干的正是这个。
     */
    private val cache = object : LruCache<String, KatexRenderResult>(cacheBudgetBytes) {
        override fun sizeOf(key: String, value: KatexRenderResult): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageReady = false

    @Volatile
    private var pageReadyLatch: CompletableDeferred<Unit>? = null

    /** JS 桥单回调槽：当前在途渲染的应答位。 */
    @Volatile
    private var pendingReply: CompletableDeferred<Triple<Int, Int, String>>? = null

    /** [GH#206] 清空全部公式缓存。任何时刻安全——见上面的回收注记。 */
    fun evictAll() {
        val before = cache.size()
        cache.evictAll()
        android.util.Log.i(TAG, "evictAll: released ~${before / 1024}KB of cached formula bitmaps")
    }

    /**
     * [GH#206] 连离屏 WebView 一起拆。只给 `TRIM_MEMORY_COMPLETE` 级别的
     * 压力用：下次渲染会重建（并重载 KaTeX 资产），代价几百毫秒。
     */
    fun releaseWebView() {
        runOnMain {
            webView?.let {
                runCatching { it.destroy() }
                android.util.Log.i(TAG, "releaseWebView: offscreen KaTeX WebView destroyed")
            }
            webView = null
            pageReady = false
        }
    }

    /**
     * 经 KaTeX 渲 [latex]。错误/超时返回 null——调用方应回落显示原始
     * LaTeX 文本。
     */
    suspend fun render(
        context: Context,
        latex: String,
        displayMode: Boolean,
        isDark: Boolean,
        fontSizePx: Int = DEFAULT_FONT_SIZE_PX,
    ): KatexRenderResult? {
        val key = cacheKey(latex, displayMode, isDark, fontSizePx)
        cache.get(key)?.let { return it }
        return renderGate.withLock {
            // 双检：排队期间别人可能已把同键渲进缓存。
            cache.get(key)?.let { return@withLock it }
            val outcome = renderOnce(context.applicationContext, latex, displayMode, isDark, fontSizePx)
            if (outcome != null) cache.put(key, outcome)
            outcome
        }
    }

    private suspend fun renderOnce(
        appContext: Context,
        latex: String,
        displayMode: Boolean,
        isDark: Boolean,
        fontSizePx: Int,
    ): KatexRenderResult? {
        val wv = obtainWebView(appContext) ?: return null
        if (!awaitPageReady()) return null

        val reply = CompletableDeferred<Triple<Int, Int, String>>()
        pendingReply = reply

        val js = buildRenderMathCall(latex, displayMode, fontSizePx, isDark)
        runOnMain { wv.evaluateJavascript(js, null) }

        val (width, height, err) = withTimeoutOrNull(RENDER_TIMEOUT_MS) { reply.await() }
            ?: Triple(0, 0, "timeout")
        pendingReply = null
        if (err.isNotEmpty() || width <= 0 || height <= 0) {
            android.util.Log.w(TAG, "render failed latex='${latex.take(40)}' err=$err w=$width h=$height")
            return null
        }
        return runOnMainSync { snapshotFormula(wv, width, height) }
    }

    /** 注入协议调用串（冻结面）：renderMath('<escaped>', D, size, dark)。
     *  internal 供 KaTeXInjectionProtocolTest 钉协议形状。 */
    internal fun buildRenderMathCall(latex: String, displayMode: Boolean, fontSizePx: Int, isDark: Boolean): String =
        "renderMath(" +
            "'${latex.escapeForJs()}', " +
            "$displayMode, " +
            "$fontSizePx, " +
            "$isDark" +
            ")"

    private suspend fun obtainWebView(appContext: Context): WebView? {
        webView?.let { return it }
        return runOnMainSync { createWebView(appContext) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(appContext: Context): WebView? {
        webView?.let { return it }
        val wv = WebView(appContext)
        configureOffscreen(wv)
        webView = wv
        return wv
    }

    /** 离屏布局 + 软件层 + JS 桥 + 就绪回调 + 装载 KaTeX 资产页。 */
    private fun configureOffscreen(wv: WebView) {
        // 离屏布局——KaTeX 量公式需要真实的一帧。快照前按量得尺寸重排。
        val exactly = android.view.View.MeasureSpec.makeMeasureSpec(LAYOUT_PIXELS, android.view.View.MeasureSpec.EXACTLY)
        wv.measure(exactly, exactly)
        wv.layout(0, 0, LAYOUT_PIXELS, LAYOUT_PIXELS)
        // T208-6：强制软件层——硬件加速的 WebView 被 wv.draw() 画到软件
        // Canvas 上会返回陈旧或全空像素：GPU 层的帧缓冲对软件回读不透
        // 明。LAYER_TYPE_SOFTWARE 下 draw() 走真实显示列表、把像素画
        // 进目标位图。（治的症状：每张捕获位图尺寸对、像素却是**上一
        // 次**渲染的公式——积分格里冒 Σ、矩阵格的 P=[…] 丢了开头的
        // mathbf 之类。）
        wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        wv.addJavascriptInterface(
            JsBridge { w, h, err -> pendingReply?.complete(Triple(w, h, err)) },
            "AndroidBridge",
        )
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                pageReadyLatch?.complete(Unit)
            }
        }
        wv.loadUrl(ASSET_HTML)
    }

    private fun snapshotFormula(wv: WebView, w: Int, h: Int): KatexRenderResult? {
        return try {
            val density = wv.context.resources.displayMetrics.density
            // KaTeX 上报的 `w`/`h` 是 CSS 像素（initial-scale=1.0 → 1 CSS px
            // = 1 dp）。位图存物理像素（= CSS px × 密度）。T208-5。
            //
            // 要紧（T208-6）：绘制前**不要**改 WebView 尺寸。改 body 尺寸
            // 会变其 CSS 宽度，触发已排好的 KaTeX HTML 重排——重排挪 span
            // 左缘/引发折行。旧代码先 resize 到 pxW × pxH 再 draw，捕获的
            // 是重排后的布局（有时首字形被切，如 `\sum_{i=1}^{n} i^2` 的
            // `Σ`）。改为保持 WebView 完整 LAYOUT_PIXELS 画布，让 canvas
            // 裁剪只取公式矩形 (0,0)–(pxW,pxH)——等价于恰好 (pxW,pxH) 物
            // 理像素的 CSS overflow-hidden 视口。
            val rawW = (w * density).toInt().coerceAtLeast(1)
            val rawH = (h * density).toInt().coerceAtLeast(1)
            val (pxW, pxH, scale) = clampedCaptureSize(rawW, rawH)
            if (scale < 1f) {
                val savedKb = (rawW.toLong() * rawH - pxW.toLong() * pxH) * 4 / 1024
                android.util.Log.i(TAG, "snapshot clamped ${rawW}x$rawH -> ${pxW}x$pxH (scale=$scale) saved=${savedKb}KB")
            }
            val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (scale < 1f) canvas.scale(scale, scale)
            wv.draw(canvas)
            KatexRenderResult(bitmap, IntSize(w, h))
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "snapshot failed: ${e.message}")
            null
        }
    }

    /**
     * [GH#206] 钳制快照尺寸。此前 rawW/rawH 直进 createBitmap、无上限——
     * 宽展示式一条就能为数 MB NATIVE 堆。缩**小**（绝不裁剪）：canvas 先
     * 按同因子缩放再画，整条公式仍在，只是分辨率低了。`size` 照报 CSS 尺
     * 寸，调用方两种情况下布局完全一致，变的只有清晰度。
     */
    private fun clampedCaptureSize(rawW: Int, rawH: Int): Triple<Int, Int, Float> {
        val edgeScale = minOf(
            1f,
            MAX_BITMAP_EDGE_PX.toFloat() / rawW,
            MAX_BITMAP_EDGE_PX.toFloat() / rawH,
        )
        val totalPx = rawW.toLong() * rawH
        val pixelScale =
            if (totalPx > MAX_BITMAP_PIXELS) kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / totalPx).toFloat()
            else 1f
        val factor = minOf(edgeScale, pixelScale)
        return Triple(
            (rawW * factor).toInt().coerceAtLeast(1),
            (rawH * factor).toInt().coerceAtLeast(1),
            factor,
        )
    }

    private suspend fun awaitPageReady(): Boolean {
        if (pageReady) return true
        val latch = pageReadyLatch ?: CompletableDeferred<Unit>().also { pageReadyLatch = it }
        val ready = withTimeoutOrNull(RENDER_TIMEOUT_MS) { latch.await() } != null
        if (!ready) android.util.Log.w(TAG, "WebView never reached ready state")
        return ready
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private suspend fun <T> runOnMainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val outcome = CompletableDeferred<T>()
        mainHandler.post {
            try {
                outcome.complete(block())
            } catch (t: Throwable) {
                outcome.completeExceptionally(t)
            }
        }
        return outcome.await()
    }

    /** internal 供注入协议测试钉键格式。 */
    internal fun cacheKeyForTest(latex: String, displayMode: Boolean, isDark: Boolean, fontSizePx: Int): String =
        cacheKey(latex, displayMode, isDark, fontSizePx)

    private fun cacheKey(latex: String, displayMode: Boolean, isDark: Boolean, fontSizePx: Int): String {
        val modeTag = if (displayMode) 'D' else 'I'
        val themeTag = if (isDark) 'k' else 'l'
        return "$modeTag:$themeTag:$fontSizePx:$latex"
    }

    private fun String.escapeForJs(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

    private class JsBridge(private val onResult: (Int, Int, String) -> Unit) {
        @JavascriptInterface
        fun onRendered(width: Int, height: Int, error: String) {
            onResult(width, height, error)
        }
    }
}
