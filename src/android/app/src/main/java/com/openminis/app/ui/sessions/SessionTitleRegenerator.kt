package com.openminis.app.ui.sessions

// [GH#210] 会话标题再生管线：候选模型有序尝试 → JSON 解析 → 本地兜底标题。
// 从 SessionListViewModel 拆出的协作者——它只依赖仓库与应用上下文，不碰
// VM 状态，因此同一个管线对任意"用户主动触发"入口可用。

import android.content.Context
import android.util.Log
import novex.android.data.model.LLMMessage
import novex.android.data.model.ThinkingLevel
import com.openminis.app.data.attachments.stripAgentAttachmentMetadata
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import org.json.JSONObject

internal class SessionTitleRegenerator(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository?,
    private val context: Context,
) {
    private companion object {
        const val TAG = "SessionListVM"

        /** [GH#210] ChatViewModel 给未生成标题会话写的占位符。兜底路径按这个
         *  精确串（加 null/空白）判定，用户手输的标题永远不会被覆盖。 */
        const val NEW_CHAT_TITLE = "New Chat"

        const val TITLE_MAX_CHARS = 30
        const val CONTEXT_SNIPPET_CHARS = 200
    }

    /**
     * 从 DB 现有内容给 [id] 生成标题。绝不自动调用——用户没点过的隐式
     * LLM 请求（比如列表加载时扫全部无标题会话）意味着启动时一批隐藏
     * 网络调用，换个装饰性标题不值。
     *
     * @param origin 标记日志行来源，便于核对派发与结果的对应。
     * @return 有标题落库（LLM 或兜底）时 true。
     */
    suspend fun generate(id: String, origin: String): Boolean {
        val startedAt = System.currentTimeMillis()
        // 轻量启动面（providerRepository == null）跳过全部 LLM 候选，直接
        // 走本地兜底标题——这里绝不允许触碰/拉起 ProviderRepository。
        val providerRepository = providerRepository
            ?: return applyFallbackTitle(id, null, origin)
        var firstUserRaw: String? = null
        try {
            val session = chatRepository.sessionById(id) ?: return false
            val messages = chatRepository.historyFor(id)
            if (messages.isEmpty()) return false

            // [T-titlegen-context-first-last-pair] 摘要 = 首条用户 + 首条助手，
            // 多轮时再加末条用户 + 末条助手，各截 200 字——再生的标题要能
            // 反映中后段的话题转移，而不只是开场。
            val userMessages = messages.filter { it.role == "user" }
            // 未截断的首条用户消息留给兜底路径。
            firstUserRaw = userMessages.firstOrNull()?.let { extractMessageText(it.partsJson) }
            val userText = firstUserRaw?.take(CONTEXT_SNIPPET_CHARS) ?: return false
            // 首/末条助手"文本"消息——跳过提取后为空白的纯工具消息，
            // 让摘要带真文字。
            val assistantTexts = messages.filter { it.role == "assistant" }
                .map { extractMessageText(it.partsJson) }
                .filter { it.isNotBlank() }
            val firstAssistantText = assistantTexts.firstOrNull()?.take(CONTEXT_SNIPPET_CHARS) ?: ""
            val hasMultipleUserTurns = userMessages.size > 1
            val lastUserText = if (hasMultipleUserTurns) {
                userMessages.lastOrNull()?.let { extractMessageText(it.partsJson) }?.take(CONTEXT_SNIPPET_CHARS) ?: ""
            } else ""
            val lastAssistantText = if (hasMultipleUserTurns) {
                assistantTexts.lastOrNull()?.take(CONTEXT_SNIPPET_CHARS) ?: ""
            } else ""

            val prompt = buildTitlePrompt(
                userText, firstAssistantText, lastUserText, lastAssistantText,
            )

            val candidates = orderedCandidates(providerRepository, session.modelId)
            var lastError: Exception? = null
            for (entry in candidates) {
                val provider = buildProvider(providerRepository, entry, context, TAG)
                    ?: continue
                AppLogger.info(
                    "TitleGen",
                    "dispatch origin=$origin session=${id.take(8)} model=${entry.model.id}",
                )
                try {
                    // T334：推理模型会把全部 token 预算烧在隐藏思考里，还没吐
                    // 内容就到顶。maxTokens=100 时每个推理候选都返回
                    // finish_reason=length + 空文本然后循环静默跳过。给推理
                    // 模型真预算（2048）让它想完还能吐 JSON 标题。
                    val titleMaxTokens = if (entry.model.supportsReasoning == true) 2048 else 100
                    // [T-android-titlegen-reasoning] 显式关思考
                    // （thinkingLevel=OFF），对齐 iOS callSubModelForTitle 与
                    // 自动路径。provider 的 injectThinkingParams 认得 OFF（如
                    // DeepSeek V4 → {"thinking":{"type":"disabled"}}），上面
                    // 的 maxTokens 增量仍留作 OFF 被忽略的模型（Qwen3）的
                    // 安全网。
                    val response = provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                        // [T-android-titlegen-systemprompt-unify] 与自动路径共用
                        // TITLE_GEN_SYSTEM_PROMPT（对齐 iOS 措辞）。裸传——
                        // AnthropicProvider 在 provider 层处理 OAuth Claude
                        // Code 前缀。
                        systemPrompt = com.openminis.app.ui.chat.TITLE_GEN_SYSTEM_PROMPT,
                        maxTokens = titleMaxTokens,
                        // [T-android-titlegen-temperature] null（不是 0.3）让
                        // buildRequestBody 省略该字段——gpt-5.x 家族只收
                        // temperature=1，传别的直接 400 静默跳过该候选。对齐
                        // 自动标题路径与 iOS AIChatViewModel.swift:11244。
                        temperature = null,
                        thinkingLevel = ThinkingLevel.OFF,
                    )
                    val (title, category) = parseTitleResponse(response.text)
                    if (title.isNotEmpty()) {
                        chatRepository.renameSessionWithCategory(id, title, category)
                        AppLogger.info(
                            "TitleGen",
                            "outcome=set origin=$origin session=${id.take(8)} " +
                                "model=${entry.model.id} elapsedMs=${System.currentTimeMillis() - startedAt}",
                        )
                        return true
                    }
                    // T334：之前这条空结果路径是静默的——只有最后一个失败的
                    // 候选的异常会被报，推理模型预算耗尽被完全遮住。显式报出
                    // 真实原因。
                    Log.w(
                        TAG,
                        "Title regen empty from ${entry.model.displayName}: " +
                            "stopReason=${response.stopReason} textLen=${response.text.length} " +
                            "maxTokens=$titleMaxTokens supportsReasoning=${entry.model.supportsReasoning}",
                    )
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Title regen via ${entry.model.displayName} failed: ${e.message}")
                    continue // 限流/供应商错误时试下一个候选
                }
            }
            AppLogger.warning(
                "TitleGen",
                "outcome=no-title origin=$origin session=${id.take(8)} " +
                    "reason=all-candidates-exhausted lastError=${lastError?.javaClass?.simpleName} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        } catch (e: Exception) {
            AppLogger.warning(
                "TitleGen",
                "outcome=exception origin=$origin session=${id.take(8)} " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        }
        // 所有失败出口都汇到这里。对齐 iOS applyFallbackTitle：会话绝不能
        // 因为 LLM 不可达就永远没标题。
        return applyFallbackTitle(id, firstUserRaw, origin)
    }

    private fun buildTitlePrompt(
        userText: String,
        firstAssistantText: String,
        lastUserText: String,
        lastAssistantText: String,
    ): String = buildString {
        append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
        append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
        append("You MUST respond with valid JSON only. Example:\n")
        append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
        append("User: $userText\n")
        if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
        if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
        if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
        append(com.openminis.app.ui.chat.titleLanguageDirective())
    }

    /**
     * 候选序：标题子模型 > 会话绑定主模型 > 其余可用模型。
     *
     * T334：滤掉非文本输出模型（tts/voiceclone/voicedesign/image/video/
     * audio-only）以及 id 明显指非聊天能力的——它们要么对 chat/completions
     * 直接 400，要么流不出有用内容，会把真实结果盖成误导性的
     * "Param Incorrect" 尾错。
     *
     * [T-android-regenerate-title-submodel] 手动 Regenerate 与自动标题路径
     * （ChatViewModel.resolveTitleProvider）及 iOS resolveSubEntry 对齐：
     * 之前这里忽略子模型，配了便宜/快速标题模型的用户还得为主模型付钱。
     * 子模型也要过 T334 模态过滤；未配置子组或成员全禁用时为 null，落回
     * 主模型优先的原序。
     */
    private fun orderedCandidates(
        providerRepository: ProviderRepository,
        sessionModelId: String?,
    ): List<novex.android.data.model.ModelEntry> {
        val titleEligible = providerRepository.allVisibleEntries().filter { entry ->
            entry.isTextCapable()
        }
        val subEntry = providerRepository.resolveTitleSubEntry()
            ?.takeIf { sub -> titleEligible.any { it == sub } }
        val primary = titleEligible.firstOrNull { it.model.id == sessionModelId }
            ?.takeIf { it != subEntry }
        return listOfNotNull(subEntry, primary) +
            titleEligible.filter { it != subEntry && it != primary }
    }

    /**
     * [GH#210] 用首条用户消息写兜底标题。
     *
     * 对齐 iOS applyFallbackTitle——包括写入前立即重读会话。这个复查是
     * 防止覆盖用户在 LLM 在途（可能数十秒）期间手输（或并发写入）的标题
     * 的护栏。
     */
    private suspend fun applyFallbackTitle(id: String, firstUserRaw: String?, origin: String): Boolean {
        val current = chatRepository.sessionById(id)?.title?.trim()
        if (!current.isNullOrEmpty() && current != NEW_CHAT_TITLE) {
            AppLogger.info(
                "TitleGen",
                "outcome=fallback-skipped origin=$origin session=${id.take(8)} reason=already-titled",
            )
            return false
        }
        val cleaned = fallbackTitleFrom(firstUserRaw)
        if (cleaned == null) {
            AppLogger.warning(
                "TitleGen",
                "outcome=fallback-unavailable origin=$origin session=${id.take(8)} " +
                    "reason=first-user-message-empty-after-cleanup",
            )
            return false
        }
        chatRepository.renameSession(id, cleaned)
        // 只记长度——绝不记正文。
        AppLogger.info(
            "TitleGen",
            "outcome=fallback origin=$origin session=${id.take(8)} titleLen=${cleaned.length}",
        )
        return true
    }

    /**
     * 剥掉输入器的模型专用附件元数据、压空白、截 30 字。与 iOS
     * fallbackTitle(fromFirstUserMessage:) 及
     * ChatViewModel.applyFallbackTitleFromFirstMessage 同形，这里恢复的
     * 标题与自动路径写的无从区分。无可用内容时返回 null。
     */
    private fun fallbackTitleFrom(raw: String?): String? {
        val cleaned = stripAgentAttachmentMetadata(raw ?: return null)
            .replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > TITLE_MAX_CHARS) {
            cleaned.take(TITLE_MAX_CHARS).trimEnd() + "…"
        } else {
            cleaned
        }
    }

    /** 模型返回的标题 JSON → (title, category)。JSON → 正则 → 首行三级兜底。 */
    private fun parseTitleResponse(text: String): Pair<String, String?> {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        try {
            val json = JSONObject(cleaned)
            val title = json.optString("title", "").trim()
            val category = json.optString("category", "").trim().ifEmpty { null }
            if (title.isNotEmpty()) return title to category
        } catch (_: Exception) {}
        val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        if (titleMatch != null) {
            return titleMatch.groupValues[1].trim() to catMatch?.groupValues?.getOrNull(1)?.trim()
        }
        val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
        return firstLine.take(50) to null
    }
}

/** T334 文本能力判定：输出模态含 text（或缺省），且 id 不明显指非聊天
 *  能力。组建议路径共用同一判定。 */
internal fun novex.android.data.model.ModelEntry.isTextCapable(): Boolean {
    val outs = model.outputModalities
    val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
    val idLower = model.id.lowercase()
    val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
        .any { idLower.contains(it) }
    return outputsText && !nonChatId
}

/**
 * 候选实例化 + OAuth 令牌刷新，标题/组建议两条管线共用。失败返回 null
 * 让调用方跳下一个候选。
 */
internal suspend fun buildProvider(
    providerRepository: ProviderRepository,
    entry: novex.android.data.model.ModelEntry,
    context: Context,
    logTag: String,
): com.openminis.app.provider.LLMProvider? {
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
    var apiKey = providerRepository.loadApiKey(instance.id) ?: return null

    if (instance.credentialType == novex.android.data.model.ProviderCredential.oauth) {
        try {
            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val freshToken = manager?.validAccessToken()
            if (freshToken != null && freshToken != apiKey) {
                providerRepository.saveApiKey(instance.id, freshToken)
                apiKey = freshToken
            }
        } catch (e: Exception) {
            Log.w(logTag, "OAuth refresh failed: ${e.message}")
        }
    }
    return try {
        ProviderFactory.create(instance, apiKey, entry.model, context)
    } catch (e: Exception) {
        Log.w(logTag, "Provider creation failed for ${entry.model.displayName}: ${e.message}")
        null
    }
}
