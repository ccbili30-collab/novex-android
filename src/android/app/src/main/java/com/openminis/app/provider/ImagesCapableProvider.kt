package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMResponse

/**
 * P3.1d 生图接口面（绞杀缝）：消费方（GenerateImageTool / QuickTestSheet）经此
 * 取「能用 Images API 生图/改图」的实现，不再下钻具体 provider 类型。
 *
 * 两个实现：
 *  - 上游 [com.openminis.app.provider.openai.OpenAIProvider]（P3.1e 换管前的存量
 *    OpenAI 家族线路：官方直连/Azure/Responses/OpenRouter/xAI/Kimi）；
 *  - novex.android.transport.NovexTransportProvider 的 imageDelegate（OpenAI 兼容
 *    中转线，内部走自有 novex.model ImagesClient）。
 * anthropic/gemini 线无 Images API——不实现本接口，调用方回落「不支持生图」。
 */
interface ImagesCapableProvider {
    /** 经 Images API `POST {base}/images/generations` 生图。 */
    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse

    /** 经 Images API `POST {base}/images/edits` 以图生图（multipart）。 */
    suspend fun editImage(
        prompt: String,
        images: List<LLMMessage.ImagePart>,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse
}
