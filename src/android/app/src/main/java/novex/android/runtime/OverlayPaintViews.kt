package novex.android.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 悬浮胶囊上三个手绘小部件。之所以不用矢量资源而用 Path 现画：
 * 三个图形加起来不到三十行绘制代码，单开 drawable 文件反而重。
 * 视觉参数（线宽比例、路径坐标）与被替换实现逐点一致。
 */

/** 转圈的细弧：画 180° 半圆，配合 View 自身的 ROTATION 动画形成旋转感。 */
internal class SpinnerRingView(
    context: Context,
    private val strokeWidth: Float,
    arcColor: Int,
) : View(context) {

    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = arcColor
        this.strokeWidth = strokeWidth
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val half = strokeWidth / 2f
        box.set(half, half, width - half, height - half)
        // 从正上方起笔的半圆；留下的 180° 空缺让"在转"一眼可辨。
        canvas.drawArc(box, -90f, 180f, false, brush)
    }
}

/** 右端的 × 关闭钮：浅灰细线，读作窗口控件而非状态指示。 */
internal class DismissCrossView(context: Context) : View(context) {

    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(220, 200, 200, 200)
    }
    private val stroke = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        brush.strokeWidth = (w * 0.14f).coerceAtLeast(2f)
        stroke.reset()
        stroke.moveTo(w * 0.25f, h * 0.25f)
        stroke.lineTo(w * 0.75f, h * 0.75f)
        stroke.moveTo(w * 0.75f, h * 0.25f)
        stroke.lineTo(w * 0.25f, h * 0.75f)
        canvas.drawPath(stroke, brush)
    }
}

/** 收尾定性图样：对勾或叉。颜色由宿主按成功/失败注入。 */
internal class OutcomeMarkView(context: Context) : View(context) {

    enum class Shape { Check, Cross }

    private var shape = Shape.Check
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#4CAF50")
    }
    private val stroke = Path()

    fun restyle(next: Shape, tint: Int) {
        shape = next
        brush.color = tint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        brush.strokeWidth = (w * 0.16f).coerceAtLeast(2f)
        stroke.reset()
        when (shape) {
            Shape.Check -> {
                stroke.moveTo(w * 0.18f, h * 0.52f)
                stroke.lineTo(w * 0.42f, h * 0.74f)
                stroke.lineTo(w * 0.82f, h * 0.30f)
            }
            Shape.Cross -> {
                stroke.moveTo(w * 0.22f, h * 0.22f)
                stroke.lineTo(w * 0.78f, h * 0.78f)
                stroke.moveTo(w * 0.78f, h * 0.22f)
                stroke.lineTo(w * 0.22f, h * 0.78f)
            }
        }
        canvas.drawPath(stroke, brush)
    }
}
