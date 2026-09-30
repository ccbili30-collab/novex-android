package novex.android.repo

import com.openminis.app.data.repository.ProviderRepository

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ModelOverrides
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType

/**
 * 实例的导出 / 导入 —— 一份可分享的 JSON，凭据随包（base64）。
 *
 * JSON 键集是跨端交换格式（iOS ↔ Android），冻结：providerType / label /
 * credentialType / models[]（modelId、displayName、isHidden、isCustom、
 * contextWindow、maxOutputTokens、supportsReasoning、
 * interleavedReasoningField、overrides{…}、modalityOverride、
 * inputModalities、outputModalities）/ apiKey / manualOAuthToken /
 * oauthToken / oauthEmail / oauthGcpProject / customBaseURL /
 * appendV1Suffix / useResponsesAPI / keyHelpUrl / autoResponsesFallback /
 * customUserAgent。新增字段一律「可缺省」，旧端读不到走默认。
 *
 * 模态信息双编码：Android 原生字符串列表（Android↔Android 无损）+
 * iOS 的 modalityOverride 位域（位布局见 [ModalityBits]，iOS↔Android 互通）。
 */
internal class ProviderTransfer(
    private val context: Context,
    private val repo: ProviderRepository,
) {

    // ── 导出 ───────────────────────────────────────────────────────────

    fun exportInstance(instanceId: String): String? {
        repo.store.ensureLoaded()
        val instance = repo.instance(instanceId) ?: return null
        val hidden = repo.config.value.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }
        val entries = repo.visibleEntries(instanceId) + hidden

        val modelsArray = JSONArray()
        for (entry in entries) modelsArray.put(modelToJson(entry))

        return JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            put("models", modelsArray)
            encodeApiKey(instanceId)?.let { put("apiKey", it) }
            encodeManualBearer(instance)?.let { put("manualOAuthToken", it) }
            encodeLoginTokens(instance)
            instance.customBaseURL?.let { put("customBaseURL", it) }
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            instance.keyHelpUrl?.takeIf { it.isNotBlank() }?.let { put("keyHelpUrl", it) }
            if (instance.autoResponsesFallback) put("autoResponsesFallback", true)
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        }.toString(2)
    }

    private fun modelToJson(entry: ModelEntry): JSONObject = JSONObject().apply {
        put("modelId", entry.baseModel.id)
        put("displayName", entry.baseModel.displayName)
        put("isHidden", entry.isHidden)
        if (entry.isCustom) put("isCustom", true)
        entry.baseModel.contextWindow?.let { put("contextWindow", it) }
        entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
        entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
        entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
        entry.baseModel.inputModalities?.let { put("inputModalities", JSONArray(it)) }
        entry.baseModel.outputModalities?.let { put("outputModalities", JSONArray(it)) }
        ModalityBits.bitfieldOf(entry.baseModel.inputModalities, entry.baseModel.outputModalities)
            .takeIf { it != 0 }?.let { put("modalityOverride", it) }
        if (!entry.overrides.isEmpty) {
            put("overrides", JSONObject().apply {
                entry.overrides.displayName?.let { put("displayName", it) }
                entry.overrides.maxOutputTokens?.let { put("maxOutputTokens", it) }
                entry.overrides.contextWindow?.let { put("contextWindow", it) }
                entry.overrides.supportsReasoning?.let { put("supportsReasoning", it) }
                entry.overrides.inputModalities?.let { put("inputModalities", JSONArray(it)) }
                entry.overrides.outputModalities?.let { put("outputModalities", JSONArray(it)) }
                ModalityBits.bitfieldOf(entry.overrides.inputModalities, entry.overrides.outputModalities)
                    .takeIf { it != 0 }?.let { put("modalityOverride", it) }
            })
        }
    }

    private fun encodeApiKey(instanceId: String): String? =
        repo.loadApiKey(instanceId)?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }

    /** 手工粘贴的 OAuth Bearer（区别于登录流程的 token），iOS 同名键。 */
    private fun encodeManualBearer(instance: ProviderInstance): String? {
        val token = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            ?.loadManualBearerToken() ?: return null
        if (token.isNullOrEmpty()) return null
        return Base64.encodeToString(token.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 登录流程存的结构化 OAuth 凭据（access/refresh/expire）。此前导出漏了
     * 它，OAuth 登录的 Claude/OpenAI/Gemini/xAI 导出后变成「未认证」——
     * 补上；Gemini 的账号邮箱与 GCP 项目一并带走。
     */
    private fun JSONObject.encodeLoginTokens(instance: ProviderInstance) {
        val manager = oauthManagerFor(instance)
        manager?.exportStoredTokensJson()?.let { json ->
            put("oauthToken", Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP))
        }
        if (instance.providerType != ProviderType.gemini || manager == null) return
        manager.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
            put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
        }
        manager.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
            put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
        }
    }

    /** 全 OAuth 厂商的 manager 映射（forInstance 不含 gemini —— 它只服务登录/手工 token 路径）。 */
    private fun oauthManagerFor(instance: ProviderInstance): com.openminis.app.auth.OAuthManager? =
        when (instance.providerType) {
            ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
            ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
            ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
            ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
            ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
            else -> null
        }

    // ── 导入 ───────────────────────────────────────────────────────────

    /**
     * 从导出 JSON 建新实例。标签撞车自动加 " (N)" 后缀；三种凭据
     * （apiKey / 手工 bearer / 登录 token）按 base64 解，退纯文本（老包）；
     * models 数组整体替换内置表（自定义/隐藏位/覆盖原样还原）。
     */
    fun importInstance(payload: String): String? {
        repo.store.ensureLoaded()
        val dict = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return null
        }
        val providerType = try {
            ProviderType.valueOf(dict.optString("providerType", ""))
        } catch (_: Exception) {
            return null
        }
        val requestedLabel = dict.optString("label", "")
        if (requestedLabel.isEmpty()) return null
        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) {
            ProviderCredential.apiKey
        }

        val taken = repo.config.value.instances.mapTo(HashSet()) { it.label }
        val label = dedupeLabel(requestedLabel, taken)

        val instance = ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = label,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null },
            appendV1Suffix = dict.optBoolean("appendV1Suffix", true),
            useResponsesAPI = dict.optBoolean("useResponsesAPI", false),
            customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null },
            keyHelpUrl = dict.optString("keyHelpUrl", "").ifEmpty { null },
            autoResponsesFallback = dict.optBoolean("autoResponsesFallback", false),
        )
        repo.addInstance(instance)

        decodeBase64OrPlain(dict.optString("apiKey", "").ifEmpty { null })
            ?.let { repo.saveApiKey(instance.id, it) }
        decodeBase64OrPlain(dict.optString("manualOAuthToken", "").ifEmpty { null })
            ?.let { token ->
                com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    ?.saveManualBearerToken(token)
            }
        decodeBase64OrPlain(dict.optString("oauthToken", "").ifEmpty { null })
            ?.let { json -> oauthManagerFor(instance)?.importStoredTokensJson(json) }
        if (instance.providerType == ProviderType.gemini) {
            val manager = oauthManagerFor(instance)
            decodeBase64OrPlain(dict.optString("oauthEmail", "").ifEmpty { null })
                ?.let { manager?.importOAuthString("email", it) }
            decodeBase64OrPlain(dict.optString("oauthGcpProject", "").ifEmpty { null })
                ?.let { manager?.importOAuthString("gcp_project", it) }
        }

        importModels(dict.optJSONArray("models"), instance, providerType)
        return label
    }

    private fun dedupeLabel(requested: String, taken: Set<String>): String {
        if (requested !in taken) return requested
        var suffix = 2
        while ("$requested ($suffix)" in taken) suffix += 1
        return "$requested ($suffix)"
    }

    private fun decodeBase64OrPlain(encoded: String?): String? {
        if (encoded == null) return null
        return try {
            String(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (_: Exception) {
            encoded // 手工编辑的老包 / 纯文本导出。
        }
    }

    private fun importModels(models: JSONArray?, instance: ProviderInstance, providerType: ProviderType) {
        if (models == null || models.length() == 0) return
        val entries = ArrayList<ModelEntry>()
        for (i in 0 until models.length()) {
            val item = models.getJSONObject(i)
            val modelId = item.optString("modelId", "")
            if (modelId.isEmpty()) continue
            val (baseIn, baseOut) = ModalityBits.fromJsonObject(item)
            entries.add(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = LLMModel(
                        id = modelId,
                        displayName = item.optString("displayName", modelId),
                        provider = providerType.displayName,
                        contextWindow = positiveInt(item, "contextWindow"),
                        maxOutputTokens = positiveInt(item, "maxOutputTokens"),
                        supportsReasoning = if (item.has("supportsReasoning")) item.optBoolean("supportsReasoning") else null,
                        interleavedReasoningField = item.optString("interleavedReasoningField", "").ifEmpty { null },
                        inputModalities = baseIn,
                        outputModalities = baseOut,
                    ),
                    overrides = readOverrides(item.optJSONObject("overrides")),
                    isCustom = item.optBoolean("isCustom", false),
                    isHidden = item.optBoolean("isHidden", false),
                ),
            )
        }
        // 整体替换内置种子表。锁 + 工作副本的纪律与所有变更器一致 —— 本
        // 函数曾是绕过纪律的暗桩（读回 addInstance 刚发布的对象就地改）。
        synchronized(repo.store.lock) {
            repo.store.ensureLoaded()
            val config = repo.store.workingCopy()
            config.modelEntries.removeAll { it.providerInstanceId == instance.id }
            config.modelEntries.addAll(entries)
            repo.store.save(config)
        }
    }

    private fun readOverrides(source: JSONObject?): ModelOverrides {
        if (source == null) return ModelOverrides()
        val (overIn, overOut) = ModalityBits.fromJsonObject(source)
        return ModelOverrides(
            displayName = source.optString("displayName", "").ifEmpty { null },
            maxOutputTokens = positiveInt(source, "maxOutputTokens"),
            contextWindow = positiveInt(source, "contextWindow"),
            supportsReasoning = if (source.has("supportsReasoning")) source.optBoolean("supportsReasoning") else null,
            inputModalities = overIn,
            outputModalities = overOut,
        )
    }

    private fun positiveInt(source: JSONObject, key: String): Int? =
        if (source.has(key)) source.optInt(key).takeIf { it > 0 } else null
}

/**
 * 模态位域编解码（与 iOS LLMTypes.swift 的 ModelModality OptionSet 对齐）。
 * 位布局：textIn=1, textOut=2, imgIn=4, pdfIn=8, audIn=16, vidIn=32,
 * imgOut=64, audOut=128, vidOut=256。
 */
private object ModalityBits {
    private const val TEXT_IN = 1 shl 0
    private const val TEXT_OUT = 1 shl 1
    private const val IMAGE_IN = 1 shl 2
    private const val PDF_IN = 1 shl 3
    private const val AUDIO_IN = 1 shl 4
    private const val VIDEO_IN = 1 shl 5
    private const val IMAGE_OUT = 1 shl 6
    private const val AUDIO_OUT = 1 shl 7
    private const val VIDEO_OUT = 1 shl 8

    private val inputBits = mapOf(
        "text" to TEXT_IN, "image" to IMAGE_IN, "pdf" to PDF_IN,
        "audio" to AUDIO_IN, "video" to VIDEO_IN,
    )
    private val outputBits = mapOf(
        "text" to TEXT_OUT, "image" to IMAGE_OUT,
        "audio" to AUDIO_OUT, "video" to VIDEO_OUT,
    )

    fun bitfieldOf(inputs: List<String>?, outputs: List<String>?): Int {
        var bits = 0
        inputs?.forEach { raw -> bits = bits or (inputBits[raw.lowercase()] ?: 0) }
        outputs?.forEach { raw -> bits = bits or (outputBits[raw.lowercase()] ?: 0) }
        return bits
    }

    fun listsOfBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = inputBits.filter { bits and it.value != 0 }.keys.toList()
        val outputs = outputBits.filter { bits and it.value != 0 }.keys.toList()
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    /**
     * 读取双编码的模态信息：原生列表键优先（Android 原生导出，无损）；
     * 缺失时回落 modalityOverride 位域（iOS 导出）；两者皆无 → (null, null)。
     */
    fun fromJsonObject(source: JSONObject): Pair<List<String>?, List<String>?> {
        val nativeIn = source.optJSONArray("inputModalities")?.toStringList()
        val nativeOut = source.optJSONArray("outputModalities")?.toStringList()
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!source.has("modalityOverride")) return null to null
        return listsOfBitfield(source.optInt("modalityOverride", 0))
    }

    private fun JSONArray.toStringList(): List<String>? =
        (0 until length()).mapNotNull { idx -> optString(idx).takeIf { it.isNotEmpty() } }
            .takeIf { it.isNotEmpty() }
}
