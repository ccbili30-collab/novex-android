package novex.android.repo

import com.openminis.app.data.repository.ProviderRepository

import android.content.Context
import android.util.Log
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import com.openminis.app.provider.ModelsCatalogApi
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi

/**
 * 模型目录刷新器 —— 把「实例 → 可用模型表」这件事的所有网络决策收拢于此。
 *
 * 取数次序（每步失败自然滑到下一步，绝不倒退成内置 GPT 表）：
 *  1. OpenAI Codex OAuth：静态表（该 token 调不了 /v1/models）；
 *  2. 各厂原生 models 端点（anthropic / gemini / openAI / openRouter /
 *     xAI / kimiCode，含 UA 覆盖与 OAuth 分支）；
 *  3. models.dev 按 base URL 查注册表 —— 第三方兼容网关多数有收录；
 *     查不到（vLLM/Ollama 私有主机）保留现有列表不动。
 *
 * 自动刷新（[autoRefresh]）多一条保护：实例存在用户自定义条目时不整体
 * 替换，只补全上下文窗口等容量元数据 —— 手工整理过的清单不能被刷没。
 * 已下线实例（OpenCode Zen）直接跳过。
 */
internal class ProviderModelRefresher(
    private val context: Context,
    private val repo: ProviderRepository,
) {

    private val logTag = "NovexModelRefresh"

    suspend fun refreshNow(instance: ProviderInstance) {
        if (isOpenCodeFreeInstanceId(instance.id)) return
        var credential = repo.loadApiKey(instance.id)

        // OAuth 先试着把 token 续上（对齐 iOS validAccessToken）。
        if (instance.credentialType == ProviderCredential.oauth && credential != null) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val fresh = manager?.validAccessToken()
                if (fresh != null && fresh != credential) {
                    repo.saveApiKey(instance.id, fresh)
                    credential = fresh
                    Log.i(logTag, "OAuth token renewed before model fetch")
                }
            } catch (e: Exception) {
                Log.w(logTag, "OAuth token renewal failed: ${e.message}")
            }
        }

        Log.i(
            logTag,
            "fetch begin: id=${instance.id} type=${instance.providerType} " +
                "credential=${instance.credentialType} hasKey=${credential != null} baseURL=${instance.effectiveBaseURL}",
        )

        // OpenAI Codex OAuth：token 不能调 /v1/models，直接用静态表。
        if (instance.providerType == ProviderType.openAI &&
            instance.credentialType == ProviderCredential.oauth
        ) {
            val static = ModelsCatalogApi.fetchOpenAiModelsOAuth()
            if (static.isNotEmpty()) {
                repo.replaceEntries(instance.id, static)
                return
            }
        }

        if (credential != null) {
            val fetched = try {
                fetchFromProvider(instance, credential)
            } catch (e: Exception) {
                Log.e(logTag, "provider models fetch threw: ${e.message}", e)
                emptyList()
            }
            Log.i(logTag, "provider endpoint returned ${fetched.size} model(s)")
            if (fetched.isNotEmpty()) {
                repo.replaceEntries(instance.id, fetched)
                return
            }
        }

        val registryModels = ModelsDevApi.fetchModels(modelsDevLookupBase(instance))
        if (registryModels.isNotEmpty()) {
            Log.i(logTag, "models.dev registry supplied ${registryModels.size} model(s) for ${instance.label}")
            repo.replaceEntries(instance.id, registryModels)
        } else if (isThirdPartyHost(instance)) {
            Log.i(logTag, "no registry match for third-party host; keeping existing list of ${instance.label}")
        }
    }

    /** 每日/后台自动刷新：有自定义条目的实例只补元数据，不换清单。 */
    suspend fun autoRefresh(instance: ProviderInstance) {
        if (isOpenCodeFreeInstanceId(instance.id)) return
        val hasCustom = repo.config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        if (!hasCustom) {
            refreshNow(instance)
            return
        }
        // OpenAI 端点可安全地只刷容量元数据：不改分组、不增删、不覆盖用户。
        if (instance.providerType == ProviderType.openAI) {
            val key = repo.loadApiKey(instance.id) ?: return
            val latest = ModelsCatalogApi.fetchOpenAiModels(
                key, instance.effectiveBaseURL, forceRefresh = true,
                customUserAgent = instance.customUserAgent,
            ).associateBy { it.id }
            synchronized(repo.store.lock) {
                val updated = repo.store.workingCopy()
                updated.modelEntries.replaceAll { entry ->
                    if (entry.providerInstanceId != instance.id) {
                        entry
                    } else {
                        val capacity = latest[entry.baseModel.id]?.contextWindow
                            ?: return@replaceAll entry
                        entry.copy(baseModel = entry.baseModel.copy(contextWindow = capacity))
                    }
                }
                repo.store.save(updated)
            }
        }
    }

    private suspend fun fetchFromProvider(
        instance: ProviderInstance,
        credential: String,
    ): List<novex.android.data.model.LLMModel> {
        val baseURL = instance.effectiveBaseURL
        return when (instance.providerType) {
            ProviderType.anthropic -> ModelsCatalogApi.fetchAnthropicModels(
                credential, baseURL,
                isOAuth = instance.credentialType == ProviderCredential.oauth,
                customUserAgent = instance.customUserAgent,
            )
            ProviderType.gemini -> ModelsCatalogApi.fetchGeminiModels(credential)
            ProviderType.openAI -> ModelsCatalogApi.fetchOpenAiModels(
                credential, baseURL, customUserAgent = instance.customUserAgent,
            )
            ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(credential)
            // xAI 的 OAuth 表是规格固定的，没有 /v1/models 门禁调用；若日后
            // 开放动态端点，在此换成 OpenAI 兼容取数即可。
            ProviderType.xAI -> com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth()
            // Kimi 的 OAuth token 可以直接调 models 端点（OpenAI 兼容形状），
            // 上代际更替频繁，实拉表取代内置兜底。
            ProviderType.kimiCode -> ModelsCatalogApi.fetchOpenAiModels(
                credential,
                baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                customUserAgent = instance.customUserAgent,
            )
        }
    }

    /** models.dev 查询用的 base：实例生效端点优先，否则按厂商的官方端点。 */
    private fun modelsDevLookupBase(instance: ProviderInstance): String {
        instance.effectiveBaseURL?.let { return it }
        return when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            ProviderType.openAI -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
        }
    }

    private fun isThirdPartyHost(instance: ProviderInstance): Boolean {
        val custom = instance.customBaseURL ?: return false
        val lowered = custom.lowercase()
        return listOf("api.openai.com", "chatgpt.com", "openrouter.ai").none { lowered.contains(it) }
    }
}
