package com.openminis.app.provider

/**
 * P3.1e 图片学习式降级的进程级共享面（自已删上游 openai 包的 provider 伴生对象迁出，随
 * openai 包整体删除落地）。跨实现互相可见：OpenAI 兼容线（chat 与 responses 两
 * 方言）的适配器在此学习/查询；anthropic/gemini 线恒真实发送（与被替换实现一致）。
 *
 * 2026-09-16 用户实锤的语义：按名字猜视觉（vision/vl）会冤枉 claude/gemini/deepseek
 * 等全部视觉模型。默认真实发送图片像素；仅当端点明确报图片相关错误后把该模型记入
 * 此集，后续请求回落占位文本。重启后重新探测。
 */
object ImageDegradationLearning {
    /** 已学习降级的模型 id 集（进程级粘性）。 */
    val imageDegradedModels: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 失败消息是否指向「端点不收图片输入」。 */
    fun looksLikeImageRejection(message: String?): Boolean {
        val value = message.orEmpty().lowercase()
        if (!value.contains("image")) return false
        return listOf("image_url", "input_image", "unknown variant", "modalit", "multimodal",
            "not support", "unsupported", "invalid").any(value::contains)
    }

    /** 学习式降级后的中性占位——不再自述「不支持」。 */
    const val IMAGE_DEGRADED_PLACEHOLDER =
        "[本条消息附带过图片，但该端点未接受图片输入，本轮未见图片内容；如需图片细节请让用户描述图片或改用接受图片输入的模型]"
}
