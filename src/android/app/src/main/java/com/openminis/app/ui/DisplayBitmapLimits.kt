package com.openminis.app.ui

import coil.request.ImageRequest
import coil.size.Precision

/**
 * [T-android-canvas-large-bitmap-crash] 交给 Compose / Canvas **显示**的图片
 * 解码尺寸上限（血统清剿 P3.7 就地真重写；上限常量与 Coil 装配为行为冻结
 * 面）。
 *
 * 背景：一张 markdown 附件（`telecom_whole_market.png`，超长图表）在 vivo
 * V2454DA / Android 16 上崩了：
 *
 *     java.lang.RuntimeException: Canvas: trying to draw too large(215040000bytes) bitmap
 *       at android.graphics.RecordingCanvas.throwIfCannotDraw
 *
 * 215,040,000 字节 / 4（ARGB_8888）= 53.76 Mpx——约 3000x17920 的图。
 * `RecordingCanvas` 拒收约 100MB 以上的位图（确切上限随 GPU 最大纹理维度
 * 浮动），绘制一抛，进程就随 `ThreadedRenderer.draw` 一起没。
 *
 * 为什么会解这么大：markdown 图片渲染器用的是
 * `SubcomposeAsyncImage(model = file, contentScale = ContentScale.FillWidth)`
 * 且**不带** `ImageRequest` 尺寸。Coil 按布局约束定请求尺寸，但 markdown
 * 列纵向可滚，高度约束是 `Constraints.Infinity`——无界维度下 Coil 回落图
 * 片**固有**尺寸，PNG 按全分辨率解码。宽而矮的图能扛住（宽度有界）；细
 * 长的就扛不住。
 *
 * 修法是钳**解码**而非事后缩放：在 `ImageRequest` 层钳顶，巨图根本不会
 * 被分配，先解码后降采样方案里残留的 OOM 风险也一并消失。
 *
 * 仅显示侧守卫。与网络侧钳发送字节的
 * [com.openminis.app.provider.ImageBudget] 无关。
 */
object DisplayBitmapLimits {

    /**
     * 屏显位图解码的最长边上限（像素）。
     *
     * 4096 是装机量里几乎所有 GPU 都支持的最大纹理维度——界内的位图恒可
     * 绘制。放行的最坏情形 4096x4096 ARGB_8888 = 64MB，稳居 ~100MB 的
     * `RecordingCanvas` 上限之下；又远超任何手机/平板视口，全屏/缩放查看
     * 器仍有充足细节可平移浏览。
     */
    const val MAX_DISPLAY_EDGE_PX = 4096

    /**
     * 把显示解码上限装到 [ImageRequest.Builder] 上。
     *
     * 用 `Precision.INEXACT`：Coil 可以用二次幂采样率落到界内，而不必产
     * 出精确尺寸的位图——更省，而我们在乎的只是上界、不是精确像素数。
     *
     * Coil 只向下缩、绝不放大，常规尺寸图片（绝大多数）解码与从前完全一
     * 样、渲染不变；受影响的只有本会越界的图。
     */
    fun ImageRequest.Builder.limitDisplaySize(): ImageRequest.Builder =
        size(MAX_DISPLAY_EDGE_PX, MAX_DISPLAY_EDGE_PX)
            .precision(Precision.INEXACT)
}
