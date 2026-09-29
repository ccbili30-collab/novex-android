package com.openminis.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.openminis.app.startup.NovexLaunchDestination
import com.openminis.app.startup.NovexStartupMetrics
import com.openminis.app.startup.novexLaunchDestination

/**
 * Minimal launcher owned by Novex. It can draw without verifying or composing
 * the large legacy activity while application repositories initialize off the
 * main thread.
 */
class NovexLaunchActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var forwarded = false

    private val readinessCheck = object : Runnable {
        override fun run() {
            val app = application as? MinisApp
            if (app == null) {
                forwardToMain()
                return
            }
            val plainLauncherStart = intent?.action == Intent.ACTION_MAIN && intent?.data == null
            when (novexLaunchDestination(app.startupCoordinator.state.value, app.startupCoordinator.safeMode, plainLauncherStart)) {
                NovexLaunchDestination.WAIT -> handler.postDelayed(this, 50L)
                NovexLaunchDestination.HOME -> installHome(app)
                NovexLaunchDestination.LEGACY -> forwardToMain()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLaunchSurface())
    }

    override fun onDestroy() {
        handler.removeCallbacks(readinessCheck)
        super.onDestroy()
    }

    private fun forwardToMain() = forwardTo("com.openminis.app.MainActivity")

    private fun installHome(app: MinisApp) {
        if (forwarded || isFinishing) return
        forwarded = true
        handler.removeCallbacks(readinessCheck)
        installNovexHomeSurface(app)
    }

    private fun forwardTo(targetClassName: String) {
        if (forwarded || isFinishing) return
        forwarded = true
        startActivity(Intent().setClassName(this, targetClassName).apply {
            action = intent?.action
            data = intent?.data
            intent?.extras?.let(::putExtras)
        })
        finish()
        overridePendingTransition(0, 0)
    }

    private fun createLaunchSurface(): FrameLayout {
        var minimumStarted = false
        val night =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = object : FrameLayout(this) {
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                super.dispatchDraw(canvas)
                if (!minimumStarted) {
                    minimumStarted = true
                    NovexStartupMetrics.reportAppFrame()
                    (application as? MinisApp)?.startupCoordinator?.startMinimum()
                    handler.post(readinessCheck)
                }
            }
        }.apply {
            // home-v2 Canvas：冷启动轻量页与 Compose 根页面同色，跟随夜间
            // 模式（#F4F5F4 / #0F1112），切换时（含 displayCutout 拟合区）
            // 不再闪白/闪浅底。
            setBackgroundColor(if (night) Color.rgb(15, 17, 18) else Color.rgb(244, 245, 244))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dpPx(), 72.dpPx(), 24.dpPx(), 24.dpPx())
        }
        column.addView(TextView(this).apply {
            text = "Novex"
            textSize = 28f
            setTextColor(if (night) Color.rgb(242, 243, 243) else Color.rgb(22, 22, 26))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        repeat(3) { index ->
            column.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 12.dpPx().toFloat()
                    setColor(
                        if (night) {
                            if (index == 0) Color.rgb(38, 41, 43) else Color.rgb(26, 29, 31)
                        } else {
                            if (index == 0) Color.rgb(237, 240, 246) else Color.rgb(243, 244, 247)
                        }
                    )
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (index == 0) 54.dpPx() else 72.dpPx(),
            ).apply {
                topMargin = if (index == 0) 30.dpPx() else 14.dpPx()
            })
        }
        surface.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        return surface
    }

    private fun Int.dpPx(): Int = (this * resources.displayMetrics.density).toInt()
}
