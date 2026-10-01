package com.openminis.app.provider.xai

import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import novex.android.data.model.LLMModel

/**
 * OAuth 用户可见的 xAI（Grok）静态目录（血统清剿 P3.7 就地真重写；
 * 目录源 LLMModel.allXAI 与日志格式为冻结面）。
 *
 * 与 OpenAI/Anthropic 不同，xAI Console 不对 OAuth bearer 持有者关
 * `/v1/models` 的闸，但规格 /tmp/grok-oauth-design.md §6 把用户面的默认
 * 列表钉成一组合格已知集——「添加供应商」一步不依赖网络往返。按 UI 应
 * 展示的优先次序原样返回。
 *
 * 同一集合就是 [LLMModel.allXAI] 伴生列表——两处保持同步。经
 * [ModelsDevApi.enrichModels] 返回，上下文窗口/模态元数据从共享目录补齐。
 */
object XAIModelsApi {
    private const val TAG = "XAIModelsApi"

    /** /v1/models 不可达或不可信时的静态回落。 */
    fun fetchModelsOAuth(): List<LLMModel> =
        LLMModel.allXAI
            .also { roster ->
                AppLogger.info(TAG, "xAI OAuth model list (${roster.size} models): ${roster.joinToString { it.id }}")
            }
            .let(ModelsDevApi::enrichModels)
}
