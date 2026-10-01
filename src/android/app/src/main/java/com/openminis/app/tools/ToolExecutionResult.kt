package com.openminis.app.tools

/**
 * 工具执行的统一收尾值（血统清剿 P3.7 就地真重写；字段集为消费方依赖面
 * 冻结——工具族与 UI 以命名实参构造）。因 [imageData] 是 ByteArray，data
 * class 自带的 equals/hashCode 不合用，手工按全字段实现。
 */
data class ToolExecutionResult(
    val output: String,
    val success: Boolean,
    val imageData: ByteArray? = null,
    val imageMimeType: String? = null,
    val toolTitle: String = "",
    /** 浏览器动作发生时的页面 URL（预览展示用）。 */
    val pageURL: String? = null,
    /** 截图 JPEG 的本地文件路径（缩略图/详情视图用）。 */
    val imageFilePath: String? = null,
    /**
     * 图片字节落盘后的沙箱可见 linux 路径（如
     * `/var/minis/browser/<sid>/screenshot_<ts>.jpg`、
     * `/var/minis/attachments/generated/...`）。请求级图片预算用它发「可
     * 重取」的文本占位来替换字节，免得累计负载顶穿单请求上限。与
     * [imageFilePath] 不同——那是宿主 OS 绝对路径，agent 直接够不着。
     */
    val imageLinuxPath: String? = null,
    /**
     * 工具未在自身类别的超时内返回时为 true。与普通 `!success` 分开，UI
     * 才能渲染 TIMEOUT 态（时钟图标）而非 FAILED 态（错误图标）。对齐
     * iOS 用错误消息内联解析出的失败子类。
     */
    val timedOut: Boolean = false,
    /** 不可重试的宿主级失败：把这个结果持久化后，停止当前自动运行。 */
    val stopAgentReason: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolExecutionResult) return false
        return output == other.output &&
            success == other.success &&
            imageData.contentEquals(other.imageData) &&
            imageMimeType == other.imageMimeType &&
            toolTitle == other.toolTitle &&
            pageURL == other.pageURL &&
            imageFilePath == other.imageFilePath &&
            imageLinuxPath == other.imageLinuxPath &&
            timedOut == other.timedOut &&
            stopAgentReason == other.stopAgentReason
    }

    override fun hashCode(): Int = listOf(
        output.hashCode(),
        success.hashCode(),
        imageData?.contentHashCode() ?: 0,
        imageMimeType?.hashCode() ?: 0,
        toolTitle.hashCode(),
        pageURL?.hashCode() ?: 0,
        imageFilePath?.hashCode() ?: 0,
        imageLinuxPath?.hashCode() ?: 0,
        timedOut.hashCode(),
        stopAgentReason?.hashCode() ?: 0,
    ).fold(1) { acc, part -> 31 * acc + part }
}
