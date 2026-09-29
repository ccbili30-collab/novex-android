package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMResponse

/**
 * P3.1d 生图接口面（绞杀缝）：消费方（GenerateImageTool / QuickTestSheet）经此
 * 取「能用 Images API 生图/改图」的实现，不再下钻具体 provider 类型。
 *
 * 实现（P3.1e 后仅剩一个）：
 *  - novex.android.transport.NovexTransportProvider 的 imageDelegate（全部九类
 *    OpenAI 家族线路——官方直连/Azure/Responses/OpenRouter/xAI/Kimi——内部走
 *    自有 novex.model ImagesClient）。
 *    墓碑：P3.1e 换管前另有上游 openai 包实现（provider/openai/ 整包已删）。
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
