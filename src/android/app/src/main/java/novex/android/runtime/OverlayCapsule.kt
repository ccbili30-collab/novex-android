package novex.android.runtime

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.service.ToolOutcome
import kotlin.math.abs

/**
 * 后台工具进度的悬浮胶囊（SYSTEM_ALERT_WINDOW）。
 *
 * 职责边界：本类只管"窗"——建窗、贴内容、拖动/点按手势、转屏后归位、
 * 拆窗；何时该出现、出现成什么内容（忙碌转圈 or 完成对勾 + 回复节
 * 选）由 [AgentKeepAlive] 的观察者裁决后打包成 [Spec] 送来。
 *
 * 窗口参数是冻结面：TYPE_APPLICATION_OVERLAY（旧系统 TYPE_PHONE）、
 * NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_NO_LIMITS、半透明格式、
 * 定宽半屏胶囊（0.50 屏宽，下限 180dp，上限 min(0.70 屏宽, 400dp)）、
 * 定高 44dp。位置拖动后经 BackgroundSettingsRepository 记忆，跨进程
 * 重启还原；无记忆时默认落左下角（左缘 10dp、导航区上方）。
 */
class OverlayCapsule(private val context: Context) {

    /** 一次呈现的全部内容；[AgentKeepAlive] 每次流更新送一帧。 */
    data class Spec(
        val toolKind: String?,
        val toolHeadline: String?,
        val statusLine: String,
        val running: Boolean,
        val outcome: ToolOutcome,
        val replyExcerpt: String?,
        val targetSessionId: String?,
    )

    /** 用户主动划掉（× 或点按进会话）时回调；宿主借此清掉停留态。 */
    var onDismissedByUser: (() -> Unit)? = null

    @Volatile
    var attached: Boolean = false
        private set

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    // 惰性取仓库：服务在安全模式下不会走到任何用它的路径，构造期就
    // 碰 MinisApp 的 lateinit 反而会在崩溃风暴里再补一刀。取不到时
    // 位置退默认值且不做记忆（正常产线由 subsystemsReady 闸门保证
    // 一定取得到）。
    private val overlayPrefs by lazy {
        (context.applicationContext as? MinisApp)?.backgroundSettingsRepository
    }

    // 已挂窗件的引用（attach 时填、takeDown 时清）。
    private var surface: View? = null
    private var ring: SpinnerRingView? = null
    private var ringSpin: ObjectAnimator? = null
    private var headline: TextView? = null
    private var status: TextView? = null
    private var reply: TextView? = null
    private var mark: OutcomeMarkView? = null
    private var cross: DismissCrossView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var sessionIdForTap: String? = null

    // ── 对外面 ───────────────────────────────────────────────────────

    fun overlayPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** 贴出或原位刷新一帧 [Spec]；无权限时静默收窗。可反复调用。 */
    fun present(spec: Spec) {
        Log.d(
            TAG,
            "present kind=${spec.toolKind} head=${spec.toolHeadline?.take(40)} " +
                "status=${spec.statusLine.take(40)} running=${spec.running} " +
                "outcome=${spec.outcome} reply=${spec.replyExcerpt?.take(20)} " +
                "session=${spec.targetSessionId} perm=${overlayPermissionGranted()}",
        )
        if (!overlayPermissionGranted()) {
            if (attached) takeDown()
            return
        }
        main.post {
            try {
                sessionIdForTap = spec.targetSessionId
                if (surface == null) attachWindow()
                render(spec)
            } catch (e: Throwable) {
                Log.w(TAG, "present failed: ${e.message}", e)
            }
        }
    }

    fun takeDown() {
        main.post {
            val view = surface ?: return@post
            try {
                ringSpin?.cancel()
            } catch (_: Throwable) {}
            ringSpin = null
            try {
                wm.removeView(view)
            } catch (e: Throwable) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
            surface = null
            ring = null
            headline = null
            status = null
            reply = null
            mark = null
            cross = null
            windowParams = null
            attached = false
        }
    }

    /**
     * 屏幕度量变了（旋转 / 分屏拖宽）之后把已挂的胶囊夹回屏内并按新
     * 度量重定宽。胶囊是 WindowManager 视图，转屏不会重建它，不夹
     * 一下就可能整个飞出可视区。未挂窗时是空操作。
     */
    fun relayoutForNewScreenMetrics() {
        main.post {
            val view = surface ?: return@post
            val params = windowParams ?: return@post
            val metrics = context.resources.displayMetrics
            val width = capsuleWidthPx()
            val height = dp(HEIGHT_DP)
            val boundX = (metrics.widthPixels - width).coerceAtLeast(0)
            val boundY = (metrics.heightPixels - height).coerceAtLeast(0)
            val x = params.x.coerceIn(0, boundX)
            val y = params.y.coerceIn(0, boundY)
            if (params.width == width && params.x == x && params.y == y) return@post
            params.width = width
            params.x = x
            params.y = y
            try {
                wm.updateViewLayout(view, params)
                overlayPrefs?.setOverlayPosition(x, y)
            } catch (e: Throwable) {
                Log.w(TAG, "relayout failed: ${e.message}")
            }
        }
    }

    // ── 建窗 ─────────────────────────────────────────────────────────

    private fun attachWindow() {
        Log.d(TAG, "attachWindow — no surface yet")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            capsuleWidthPx(),
            dp(HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val store = overlayPrefs
            val rememberedX = store?.getOverlayX() ?: -1
            val rememberedY = store?.getOverlayY() ?: -1
            if (rememberedX >= 0 && rememberedY >= 0) {
                x = rememberedX
                y = rememberedY
            } else {
                // 首次贴窗默认落左下角：导航栏高度拿不准，按 48dp 估并
                // 依赖 FLAG_LAYOUT_NO_LIMITS 兜住出界。
                val metrics = context.resources.displayMetrics
                val inset = dp(EDGE_INSET_DP)
                x = inset
                y = metrics.heightPixels - dp(LOGO_DP + 16) - dp(48) - inset
            }
        }
        windowParams = params
        val shell = buildShell(params)
        wm.addView(shell, params)
        surface = shell
        attached = true
    }

    /** 组装胶囊视图树：圆形图标 + 转圈 / 两三行文字 / ×。 */
    private fun buildShell(params: WindowManager.LayoutParams): View {
        val density = context.resources.displayMetrics.density
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 右内边距加大到 14dp：44dp 高的胶囊圆角半径 22dp，6dp 的
            // 对称边距会把 × 的角藏进弧线里。
            setPadding(dp(6), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // 半径跟随半高，改 HEIGHT_DP 也保持标准胶囊形。
                cornerRadius = (HEIGHT_DP / 2f) * density
                setColor(PILL_FILL)
                setStroke(dp(1), PILL_EDGE)
            }
            elevation = 8f * density
            gravity = Gravity.CENTER_VERTICAL
            // 窗宽已在外层钉死；这只作防御性下限，防将来有人改回
            // WRAP_CONTENT 时胶囊缩成一条。
            minimumWidth = dp(120)
        }

        shell.addView(buildLogoStack())
        shell.addView(buildTextColumn())
        shell.addView(buildDismissButton())
        shell.setOnTouchListener(DragOrTapGesture(params))
        return shell
    }

    private fun buildLogoStack(): View {
        val logoBox = dp(LOGO_DP)
        val shell = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(logoBox, logoBox).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(6)
            }
        }
        val logo = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(logoBox, logoBox).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 圆形裁剪走 clipToOutline + 椭圆轮廓，免掉 BitmapShader。
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
        }
        shell.addView(logo)
        val spin = SpinnerRingView(
            context,
            strokeWidth = dp(RING_STROKE_DP).toFloat(),
            arcColor = RING_TINT,
        ).apply {
            // 圈与图标同盒（inset=0）：居中描边让弧线贴住图标可见边缘。
            layoutParams = FrameLayout.LayoutParams(logoBox, logoBox).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        ring = spin
        shell.addView(spin)
        return shell
    }

    private fun buildTextColumn(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // weight=1 + 宽 0：文字列吃掉图标与 × 之间的全部余量，×
            // 恒贴右缘，不随文字长短漂。
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headline = title
        titleRow.addView(title)
        val glyph = OutcomeMarkView(context).apply {
            val side = dp(OUTCOME_MARK_DP)
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                leftMargin = dp(4)
                gravity = Gravity.CENTER_VERTICAL
            }
            visibility = View.GONE
        }
        mark = glyph
        titleRow.addView(glyph)
        column.addView(titleRow)

        val line = TextView(context).apply {
            setTextColor(STATUS_TINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        status = line
        column.addView(line)

        val excerpt = TextView(context).apply {
            setTextColor(REPLY_TINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        reply = excerpt
        column.addView(excerpt)
        return column
    }

    private fun buildDismissButton(): View {
        val button = DismissCrossView(context).apply {
            val side = dp(14)
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                leftMargin = dp(10)
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { dismissedByUser() }
            contentDescription = context.getString(R.string.overlay_dismiss)
        }
        cross = button
        return button
    }

    // ── 内容渲染 ─────────────────────────────────────────────────────

    private fun render(spec: Spec) {
        val hasIdentity = !spec.toolHeadline.isNullOrBlank() || spec.toolKind != null
        val showStatus = spec.running ||
            (hasIdentity && !spec.statusLine.equals(IDLE_LABEL, ignoreCase = true))
        val hasReply = !spec.running && !spec.replyExcerpt.isNullOrBlank()
        val wrapUp = if (!spec.running) completionWord(spec.outcome) else null

        val identity = when {
            !spec.toolHeadline.isNullOrBlank() -> spec.toolHeadline
            else -> shortLabel(spec.toolKind)
        }
        val headlineText = when {
            !hasIdentity -> if (!hasReply) wrapUp else null
            spec.running || hasReply -> identity
            wrapUp != null -> "$identity — $wrapUp"
            else -> identity
        }
        headline?.let { view ->
            if (headlineText != null) {
                view.text = headlineText
                view.visibility = View.VISIBLE
            } else {
                view.text = ""
                view.visibility = View.GONE
            }
        }
        status?.let { view ->
            if (showStatus) {
                view.text = spec.statusLine
                view.visibility = View.VISIBLE
            } else {
                view.text = ""
                view.visibility = View.GONE
            }
        }

        // 三行文字的 maxWidth 同步压进定宽信封（减去图标、边距与 ×
        // 约共 76dp，下限 80dp），保证省略号先出现、盒子永不胀开。
        val textBudget = (capsuleWidthPx() - dp(76)).coerceAtLeast(dp(80))
        headline?.maxWidth = textBudget
        status?.maxWidth = textBudget
        reply?.maxWidth = textBudget

        reply?.let { view ->
            if (!spec.running && !spec.replyExcerpt.isNullOrBlank()) {
                view.text = spec.replyExcerpt
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
        }

        // × 只在收尾态有意义：流式中胶囊本来就会自己收，别让它被读
        // 成"取消这次运行"。
        cross?.visibility = if (spec.running) View.GONE else View.VISIBLE

        ring?.let { spin ->
            if (spec.running) {
                if (spin.visibility != View.VISIBLE) spin.visibility = View.VISIBLE
                if (ringSpin?.isStarted != true) startSpin(spin)
            } else {
                if (spin.visibility != View.GONE) spin.visibility = View.GONE
                ringSpin?.cancel()
                ringSpin = null
            }
        }

        mark?.let { glyph ->
            when {
                spec.running -> glyph.visibility = View.GONE
                spec.outcome == ToolOutcome.Success -> {
                    glyph.restyle(OutcomeMarkView.Shape.Check, SUCCESS_TINT)
                    glyph.visibility = View.VISIBLE
                }
                spec.outcome == ToolOutcome.Error || spec.outcome == ToolOutcome.Timeout -> {
                    glyph.restyle(OutcomeMarkView.Shape.Cross, FAILURE_TINT)
                    glyph.visibility = View.VISIBLE
                }
                else -> glyph.visibility = View.GONE // 取消/未知：不猜
            }
        }
    }

    private fun completionWord(outcome: ToolOutcome): String? = when (outcome) {
        ToolOutcome.Success -> context.getString(R.string.overlay_completion_completed)
        ToolOutcome.Error -> context.getString(R.string.overlay_completion_failed)
        ToolOutcome.Timeout -> context.getString(R.string.overlay_completion_timeout)
        ToolOutcome.Cancelled -> context.getString(R.string.overlay_completion_cancelled)
        ToolOutcome.Unknown -> null
    }

    private fun startSpin(target: View) {
        ringSpin?.cancel()
        ringSpin = ObjectAnimator.ofFloat(target, View.ROTATION, 0f, 360f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
    }

    // ── 点按 / 拖动 / 关闭 ───────────────────────────────────────────

    private fun dismissedByUser() {
        try { onDismissedByUser?.invoke() } catch (_: Throwable) {}
        takeDown()
    }

    /** 整个胶囊的点按：带着会话深链回应用（minis://session/<id>）。 */
    private fun openChatAndDismiss() {
        try {
            val deepLink = sessionIdForTap?.takeIf { it.isNotBlank() }
            val intent = Intent().setClassName(context, "com.openminis.app.NovexLaunchActivity").apply {
                if (deepLink != null) data = Uri.parse("minis://session/$deepLink")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "tap-to-open failed: ${e.message}")
        }
        dismissedByUser()
    }

    /**
     * 拖动/点按二分手势：位移超过 8dp 才算拖动（期间实时跟手，抬手
     * 落点记忆）；未过阈值的抬起算点按。每个胶囊恰好配一个手势实例，
     * 跨事件姿态就记在实例里。
     */
    private inner class DragOrTapGesture(
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {

        private var pressX = 0f
        private var pressY = 0f
        private var originX = 0
        private var originY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val touchSlop = (DRAG_SLOP_DP * context.resources.displayMetrics.density).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressX = event.rawX
                    pressY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - pressX).toInt()
                    val dy = (event.rawY - pressY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = originX + dx
                        params.y = originY + dy
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (e: Throwable) {
                            Log.w(TAG, "updateViewLayout failed: ${e.message}")
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        overlayPrefs?.setOverlayPosition(params.x, params.y)
                    } else {
                        openChatAndDismiss()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    // ── 度量 ─────────────────────────────────────────────────────────

    /**
     * 胶囊定宽：屏宽一半，上限 min(0.70 屏宽, 400dp)（横屏防摊满全
     * 屏），下限 180dp（小屏防过窄）。每次现读度量，转屏后 relayout
     * 即可取到新值。
     */
    private fun capsuleWidthPx(): Int {
        val metrics = context.resources.displayMetrics
        val ceiling = minOf(
            (metrics.widthPixels * WIDTH_CAP_FRACTION).toInt(),
            dp(WIDTH_CAP_DP),
        )
        return (metrics.widthPixels * WIDTH_FRACTION).toInt()
            .coerceAtMost(ceiling)
            .coerceAtLeast(dp(WIDTH_FLOOR_DP))
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 胶囊内"工具短名"（通知栏长文案去前缀版；措辞与站内浮条对齐，冻结）。 */
    private fun shortLabel(toolKind: String?): String = when (toolKind) {
        null -> "Minis"
        "shell_execute" -> "Shell"
        "file_read" -> "File"
        "file_write" -> "Editor"
        "file_edit" -> "Edit"
        "browser_use" -> "Browser"
        "read_image" -> "Image"
        "memory_write", "memory_get" -> "Memory"
        "web_search" -> "Search"
        else -> toolKind
    }

    private companion object {
        private const val TAG = "OverlayCapsule"

        private const val DRAG_SLOP_DP = 8
        private const val LOGO_DP = 26
        private const val RING_STROKE_DP = 1
        private const val OUTCOME_MARK_DP = 12
        private const val EDGE_INSET_DP = 10
        private const val HEIGHT_DP = 44
        private const val WIDTH_FRACTION = 0.50f
        private const val WIDTH_FLOOR_DP = 180
        private const val WIDTH_CAP_FRACTION = 0.70f
        private const val WIDTH_CAP_DP = 400

        private const val IDLE_LABEL = "Idle"

        private val RING_TINT = Color.argb(230, 120, 200, 255)
        private val SUCCESS_TINT = Color.parseColor("#4CAF50")
        private val FAILURE_TINT = Color.parseColor("#E53935")
        private val PILL_FILL = Color.argb(230, 28, 28, 30)
        private val PILL_EDGE = Color.argb(40, 255, 255, 255)
        private val STATUS_TINT = Color.argb(200, 220, 220, 220)
        private val REPLY_TINT = Color.argb(235, 200, 220, 255)
    }
}
