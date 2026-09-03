package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.tools.AgentTools
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

private enum class ModelResultState { PASSED, WARNING, FAILED, CANCELLED }
private data class ModelVerificationUiResult(
    val state: ModelResultState,
    val message: String,
)
private data class SetupValues(
    val base: String,
    val key: String,
    val models: List<String>,
)
private data class ConnectionVerification(
    val models: NovexModelVerification,
    val fatalError: String? = null,
)

internal const val NOVEX_DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"

internal fun novexModelDisplayName(modelId: String): String =
    if (modelId == NOVEX_DEFAULT_DEEPSEEK_MODEL) {
        "DeepSeek V4 Flash（深度求索 V4 快速版）"
    } else {
        LLMModel.modelDisplayName(modelId)
    }

internal fun toggleModelSelection(current: List<String>, clicked: String): List<String> {
    val clean = clicked.trim()
    if (clean.isEmpty()) return current.distinct()
    val normalized = current.map(String::trim).filter(String::isNotEmpty).distinct()
    return if (clean in normalized) normalized - clean else normalized + clean
}

internal fun looksLikeImageGenerationModel(modelId: String): Boolean {
    val id = modelId.lowercase()
    return listOf("gpt-image", "dall-e", "imagen", "image-gen", "image_generation", "flux", "seedream", "nano-banana")
        .any(id::contains)
}

/** Input capability assigned to a chat model saved by the simplified setup. */
internal fun novexChatInputModalities(
    modelId: String,
    existingInputModalities: List<String>? = null,
): List<String> {
    val existing = existingInputModalities.orEmpty()
        .map(String::normalizeModalityName)
        .filter(String::isNotEmpty)
        .distinct()
    if (existing.isNotEmpty()) {
        return (listOf("text") + existing).distinct()
    }

    // OpenAI-compatible /models normally exposes only an id, not modality
    // metadata. Keep inference deliberately conservative: names that explicitly
    // advertise vision/visual/VL get native image input; ordinary chat and
    // image-generation model names are not upgraded implicitly.
    val normalizedId = modelId.trim().lowercase()
    val explicitlyVisual = looksLikeVisionInputModel(
        normalizedId,
        novexModelDisplayName(modelId),
    )
    return if (explicitlyVisual) listOf("text", "image") else listOf("text")
}

internal fun novexCanonicalBase(base: String, appendV1Suffix: Boolean): String {
    val normalized = base.trim().trimEnd('/')
    return if (appendV1Suffix && !normalized.endsWith("/v1")) "$normalized/v1" else normalized
}

internal fun novexProviderInstanceForSave(
    existing: ProviderInstance?,
    label: String,
    base: String,
    appendV1Suffix: Boolean,
): ProviderInstance = (existing ?: ProviderInstance(
    id = UUID.randomUUID().toString(),
    label = label.ifBlank { "OpenAI 兼容接口" },
    providerType = ProviderType.openAI,
    credentialType = ProviderCredential.apiKey,
)).copy(
    label = label.ifBlank { "OpenAI 兼容接口" },
    customBaseURL = base.trim().trimEnd('/'),
    appendV1Suffix = appendV1Suffix,
    isEnabled = true,
)

/** Novex 的单一 OpenAI（开放人工智能）兼容接口设置页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovexProviderSetupScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    instanceId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(instanceId) { instanceId?.let(providerRepository::instance) }
    var label by remember { mutableStateOf(existing?.label ?: "DeepSeek") }
    var apiBase by remember { mutableStateOf(existing?.customBaseURL ?: "https://api.deepseek.com") }
    var apiKey by remember { mutableStateOf(existing?.id?.let(providerRepository::loadApiKey) ?: "") }
    var appendV1Suffix by remember(instanceId) {
        mutableStateOf(existing?.appendV1Suffix ?: true)
    }
    var deleteConfirm by remember(instanceId) { mutableStateOf(false) }
    val existingEntries = remember(instanceId) { providerRepository.entriesFor(instanceId ?: "") }
    val initialModels = remember(instanceId) {
        existingEntries.filterNot { it.model.outputModalities.orEmpty().contains("image") }
            .map { it.model.id }.distinct()
            .ifEmpty { if (existing == null) listOf(NOVEX_DEFAULT_DEEPSEEK_MODEL) else emptyList() }
    }
    val selectedModels = remember(instanceId) { mutableStateListOf<String>().apply { addAll(initialModels) } }
    val modelToolsEnabled = remember(instanceId) {
        mutableStateMapOf<String, Boolean>().apply {
            existingEntries
                .filterNot { it.model.outputModalities.orEmpty().contains("image") }
                .forEach { entry -> put(entry.model.id, entry.model.supportsTools != false) }
        }
    }
    var manualModelId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var fetchingModels by remember { mutableStateOf(false) }
    var checkingModelId by remember { mutableStateOf<String?>(null) }
    var verificationJob by remember { mutableStateOf<Job?>(null) }
    val verificationResults = remember { mutableStateMapOf<String, ModelVerificationUiResult>() }
    val fetchedModels = remember { mutableStateListOf<String>() }
    fun invalidateVerification() {
        verificationJob?.cancel()
        verificationJob = null
        checkingModelId = null
        error = null
        verificationResults.clear()
    }
    fun setSelectedModels(models: List<String>) {
        selectedModels.clear()
        selectedModels.addAll(models.map(String::trim).filter(String::isNotEmpty).distinct())
    }
    fun toolsEnabled(modelId: String): Boolean = modelToolsEnabled[modelId] != false
    fun validate(requireModels: Boolean = true): SetupValues? {
        val base = apiBase.trim().trimEnd('/')
        val key = apiKey.trim()
        val models = selectedModels.map(String::trim).filter(String::isNotEmpty).distinct()
        error = when {
            base.isEmpty() -> "请填写接口地址"
            !base.startsWith("http://") && !base.startsWith("https://") -> "接口地址需要以 https:// 或 http:// 开头"
            key.isEmpty() -> "请填写 API 密钥"
            requireModels && models.isEmpty() -> "请至少勾选一个模型"
            else -> null
        }
        return if (error == null) SetupValues(base, key, models) else null
    }
    fun startVerification(values: SetupValues, modelId: String) {
        if (checkingModelId == modelId) {
            verificationJob?.cancel()
            return
        }
        if (checkingModelId != null) return
        checkingModelId = modelId
        error = null
        verificationResults.remove(modelId)
        verificationJob = scope.launch {
            try {
                val verification = verifyConnection(
                    values.base,
                    values.key,
                    appendV1Suffix,
                    listOf(modelId),
                    toolEnabledByModel = { toolsEnabled(it) },
                )
                if (verification.fatalError != null) {
                    verificationResults[modelId] = ModelVerificationUiResult(
                        ModelResultState.FAILED,
                        "$modelId：${verification.fatalError}",
                    )
                } else {
                    val result = verification.models
                    val state = when {
                        result.failures.any { it.modelId == modelId } -> ModelResultState.FAILED
                        result.warnings.any { it.modelId == modelId } -> ModelResultState.WARNING
                        else -> ModelResultState.PASSED
                    }
                    verificationResults[modelId] = ModelVerificationUiResult(
                        state,
                        formatNovexModelVerificationLine(result, modelId),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (checkingModelId == modelId) {
                    verificationResults[modelId] = ModelVerificationUiResult(
                        ModelResultState.CANCELLED,
                        "$modelId：检测已取消",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                verificationResults[modelId] = ModelVerificationUiResult(
                    ModelResultState.FAILED,
                    "$modelId：${failure.message ?: failure.javaClass.simpleName}",
                )
            } finally {
                if (checkingModelId == modelId) {
                    checkingModelId = null
                    verificationJob = null
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(
        title = { Text(if (existing == null) "连接模型" else "模型连接") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        actions = {
            if (existing != null) {
                IconButton(onClick = { deleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除 AI 服务商")
                }
            }
        },
    ) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("连接 OpenAI（开放人工智能）兼容接口", style = MaterialTheme.typography.headlineSmall)
            Text("支持 DeepSeek（深度求索）与常见中转站。可直接保存启用，也可以按需检测当前模型或全部模型。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(label = { Text("名称") }, value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(label = { Text("接口地址") }, value = apiBase, onValueChange = { apiBase = it; invalidateVerification() }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动补全 /v1")
                    Text(
                        if (appendV1Suffix) {
                            "填写域名即可；请求时自动补上 /v1"
                        } else {
                            "关闭后完全按填写的接口地址请求"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = appendV1Suffix,
                    onCheckedChange = {
                        appendV1Suffix = it
                        invalidateVerification()
                    },
                )
            }
            OutlinedTextField(label = { Text("API（应用程序接口）密钥") }, value = apiKey, onValueChange = { apiKey = it; invalidateVerification() }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(
                "滑到最下方获取密钥",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "勾选启用；扳手控制工具；刷新仅检测这一行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(enabled = !fetchingModels && checkingModelId == null, onClick = {
                    val values = validate(requireModels = false) ?: return@OutlinedButton
                    fetchingModels = true
                    scope.launch {
                        val models = fetchModels(values.base, values.key, appendV1Suffix)
                            .filterNot(::looksLikeImageGenerationModel)
                        fetchedModels.clear(); fetchedModels.addAll(models)
                        if (models.isEmpty()) error = "没有拉取到模型，请检查地址和密钥，或继续手动填写模型名称。"
                        else {
                            if (selectedModels.none { it in models }) setSelectedModels(listOf(models.first()))
                        }
                        fetchingModels = false
                    }
                }) { Text(if (fetchingModels) "拉取中" else "拉取模型") }
            }
            val displayModels = (fetchedModels + selectedModels)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(100)
            if (displayModels.isEmpty()) {
                Text(
                    "尚无模型，请先拉取或手动添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider()
                displayModels.forEach { modelId ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = modelId in selectedModels,
                            onCheckedChange = { checked ->
                                setSelectedModels(
                                    if (checked) (selectedModels + modelId).distinct()
                                    else selectedModels - modelId,
                                )
                            },
                        )
                        Text(
                            novexModelDisplayName(modelId),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(
                            onClick = {
                                modelToolsEnabled[modelId] = !toolsEnabled(modelId)
                                verificationResults.remove(modelId)
                            },
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = if (toolsEnabled(modelId)) "关闭该模型的工具调用" else "开启该模型的工具调用",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (toolsEnabled(modelId)) Color(0xFF168A45) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (toolsEnabled(modelId)) "✓" else "×",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        IconButton(
                            enabled = checkingModelId == null || checkingModelId == modelId,
                            onClick = {
                                if (checkingModelId == modelId) {
                                    verificationJob?.cancel()
                                } else {
                                    val values = validate(requireModels = false) ?: return@IconButton
                                    startVerification(values, modelId)
                                }
                            },
                        ) {
                            Icon(
                                if (checkingModelId == modelId) Icons.Default.Stop else Icons.Default.Refresh,
                                contentDescription = if (checkingModelId == modelId) "停止检测 $modelId" else "检测 $modelId",
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    label = { Text("手动添加模型") },
                    value = manualModelId,
                    onValueChange = { manualModelId = it; error = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        val id = manualModelId.trim()
                        if (id.isNotEmpty()) {
                            if (looksLikeImageGenerationModel(id)) {
                                error = "图片生成模型请前往“设置 → 生图服务”添加。"
                            } else {
                                if (id !in fetchedModels) fetchedModels.add(id)
                                setSelectedModels((selectedModels + id).distinct())
                                manualModelId = ""
                            }
                        }
                    },
                    enabled = manualModelId.isNotBlank(),
                ) {
                    Icon(Icons.Default.Add, "添加模型")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (verificationResults.isNotEmpty()) {
                Text("检测结果", style = MaterialTheme.typography.titleMedium)
                verificationResults.forEach { (_, result) ->
                    val color = when (result.state) {
                        ModelResultState.PASSED -> Color(0xFF168A45)
                        ModelResultState.WARNING -> Color(0xFFB77900)
                        ModelResultState.FAILED -> MaterialTheme.colorScheme.error
                        ModelResultState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val icon = when (result.state) {
                        ModelResultState.PASSED -> Icons.Default.CheckCircle
                        ModelResultState.WARNING, ModelResultState.FAILED -> Icons.Default.Error
                        ModelResultState.CANCELLED -> Icons.Default.Close
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                        Text(result.message, color = color, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = {
                val values = validate() ?: return@Button
                error = null
                runCatching {
                    saveConnections(
                        repository = providerRepository,
                        existing = existing,
                        label = label,
                        base = values.base,
                        appendV1Suffix = appendV1Suffix,
                        key = values.key,
                        modelIds = values.models,
                        modelToolsEnabled = values.models.associateWith(::toolsEnabled),
                    )
                }.onSuccess {
                    onSaved()
                }.onFailure { failure ->
                    error = "保存模型连接失败：${failure.message ?: failure.javaClass.simpleName}"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存并启用（${selectedModels.size}）") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys"))) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("前往 DeepSeek（深度求索）获取密钥") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (deleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("删除这个 AI 服务商？") },
            text = {
                Text("服务商、密钥及其 ${existingEntries.size} 个模型会被移除，历史对话不会删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        providerRepository.removeInstance(existing.id)
                        deleteConfirm = false
                        onBack()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

private suspend fun fetchModels(base: String, key: String, appendV1Suffix: Boolean): List<String> = runCatching {
    OpenAIModelsApi.fetchModels(
        key,
        novexCanonicalBase(base, appendV1Suffix),
        forceRefresh = true,
    ).map { it.id }.distinct()
}.getOrDefault(emptyList())

private suspend fun verifyConnection(
    base: String,
    key: String,
    appendV1Suffix: Boolean,
    modelIds: List<String>,
    toolEnabledByModel: (String) -> Boolean,
): ConnectionVerification = withContext(Dispatchers.IO) {
    val canonical = novexCanonicalBase(base, appendV1Suffix); val client = OkHttpClient()
    fun fatal(message: String): ConnectionVerification {
        return ConnectionVerification(NovexModelVerification(emptyList(), emptyList()), message)
    }
    val reachable = runCatching { client.newCall(Request.Builder().url("$canonical/models").build()).execute().use { it.code in 200..499 } }.getOrDefault(false)
    if (!reachable) return@withContext fatal("无法连接接口地址")
    val authCode = runCatching { client.newCall(Request.Builder().url("$canonical/models").header("Authorization", "Bearer $key").build()).execute().use { it.code } }.getOrDefault(0)
    if (authCode == 401 || authCode == 403) return@withContext fatal("密钥无效或访问被拒绝（HTTP $authCode）")

    val instance = ProviderInstance(id = "novex-check", label = "连接检测", providerType = ProviderType.openAI, credentialType = ProviderCredential.apiKey, customBaseURL = base, appendV1Suffix = appendV1Suffix)
    val providerResults = mutableMapOf<String, Result<LLMProvider>>()
    fun providerFor(modelId: String): Result<LLMProvider> = providerResults.getOrPut(modelId) {
        val model = LLMModel(modelId, novexModelDisplayName(modelId), "OpenAI（开放人工智能）兼容接口")
        runCatching { ProviderFactory.create(instance, key, model) }
    }
    val verificationTools = AgentTools.makeAgentTools(
        supportsImageInput = false,
        visionGroupConfigured = false,
        memoryEnabled = true,
    )
    suspend fun probeChat(modelId: String): String? {
        val provider = providerFor(modelId).getOrElse {
            return "无法创建模型连接：${it.message ?: it.javaClass.simpleName}"
        }
        return runCatching {
            val response = withTimeout(45_000) {
                provider.sendMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER, "只回复：连接成功")),
                    null,
                    64,
                    tools = if (toolEnabledByModel(modelId)) verificationTools else emptyList(),
                )
            }
            when {
                response.stopReason == null ->
                    "流式响应没有 finish_reason（结束原因），连接可能已中断"
                response.text.isBlank() ->
                    "HTTP 200，finish_reason=${response.stopReason}，但没有返回有效文字"
                else -> null
            }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
    suspend fun probeTool(modelId: String): String? {
        val provider = providerFor(modelId).getOrElse {
            return "无法创建模型连接：${it.message ?: it.javaClass.simpleName}"
        }
        return runCatching {
            var called = false
            var finishReason: String? = null
            val visibleText = StringBuilder()
            withTimeout(45_000) {
                provider.streamMessage(
                    listOf(
                        LLMMessage(
                            LLMMessage.Role.USER,
                            "请立即调用 present_choices，提供“继续”和“返回”两个选项，不要输出文字。",
                        ),
                    ),
                    null,
                    256,
                    tools = verificationTools,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> visibleText.append(chunk.text)
                        is LLMStreamChunk.ToolCallComplete -> {
                            if (chunk.name == "present_choices") called = true
                        }
                        is LLMStreamChunk.Finished -> finishReason = chunk.stopReason
                        else -> Unit
                    }
                }
            }
            when {
                called -> null
                finishReason == null ->
                    "流式响应没有 finish_reason（结束原因），也没有结构化工具调用"
                visibleText.isBlank() ->
                    "HTTP 200，finish_reason=$finishReason，但没有文字或结构化工具调用"
                else ->
                    "finish_reason=$finishReason，只返回了文字，没有结构化 present_choices 调用"
            }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    val result = verifyNovexModels(
        modelIds = modelIds,
        repetitions = 3,
        shouldProbeTools = toolEnabledByModel,
        chatProbe = ::probeChat,
        toolProbe = ::probeTool,
    )
    ConnectionVerification(result)
}

private fun saveConnections(
    repository: ProviderRepository,
    existing: ProviderInstance?,
    label: String,
    base: String,
    appendV1Suffix: Boolean,
    key: String,
    modelIds: List<String>,
    modelToolsEnabled: Map<String, Boolean>,
) {
    val instance = novexProviderInstanceForSave(existing, label, base, appendV1Suffix)
    if (existing == null) repository.addInstance(instance) else repository.updateInstance(instance)
    repository.saveApiKey(instance.id, key)
    val previousEntries = repository.entriesFor(instance.id)
        .filterNot { it.model.outputModalities.orEmpty().contains("image") }
    val previousIds = previousEntries.map { it.id }.toSet()
    val group = repository.config.value.modelGroups.firstOrNull { candidate ->
        candidate.memberEntryIds.any { it in previousIds }
    }
    previousEntries.filter { it.model.id !in modelIds }.forEach { repository.removeEntry(it.id) }
    modelIds.forEach { modelId ->
        if (repository.entriesFor(instance.id).none { it.model.id == modelId }) {
            repository.addEntry(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = LLMModel(
                        id = modelId,
                        displayName = novexModelDisplayName(modelId),
                        provider = instance.label,
                        inputModalities = novexChatInputModalities(modelId),
                        outputModalities = listOf("text"),
                    ),
                    overrides = ModelOverrides(
                        supportsTools = modelToolsEnabled[modelId],
                    ),
                    isCustom = true,
                ),
            )
        }
    }
    repository.entriesFor(instance.id)
        .filterNot { it.model.outputModalities.orEmpty().contains("image") }
        .forEach { entry ->
        val desiredInput = novexChatInputModalities(entry.model.id, entry.model.inputModalities)
        val desiredOutput = listOf("text")
        val desiredTools = modelToolsEnabled[entry.model.id]
        if (entry.model.inputModalities != desiredInput ||
            entry.model.outputModalities != desiredOutput ||
            entry.overrides.supportsTools != desiredTools
        ) {
            repository.updateEntry(
                entry.copy(
                    overrides = entry.overrides.copy(
                        inputModalities = desiredInput,
                        outputModalities = desiredOutput,
                        supportsTools = desiredTools,
                    ),
                ),
            )
        }
    }
    val selectedEntries = repository.entriesFor(instance.id).filter { it.model.id in modelIds }
    val selectedIds = selectedEntries.map { it.id }
    if (group == null) {
        ModelGroup(name = "默认模型", memberEntryIds = selectedIds.toMutableList()).also {
            repository.addGroup(it)
            repository.defaultPrimaryGroupId = it.id
        }
    } else {
        val retained = group.memberEntryIds.filterNot { it in previousIds }
        repository.updateGroup(group.copy(memberEntryIds = (retained + selectedIds).distinct().toMutableList()))
        repository.defaultPrimaryGroupId = group.id
    }
}
