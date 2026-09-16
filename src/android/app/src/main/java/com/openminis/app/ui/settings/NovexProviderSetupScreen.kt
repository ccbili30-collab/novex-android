package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.NovexCheckToggle
import com.openminis.app.ui.novex.OutlinedButton
import com.openminis.app.ui.novex.OutlinedTextField
import com.openminis.app.ui.novex.Scaffold
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.TopAppBar
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

internal fun looksLikeImageGenerationModel(modelId: String): Boolean =
    com.openminis.app.data.model.ChatModelSelection.imageGenerationId(modelId)

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

/**
 * [T-provider-direction] 模型连接页的接口方向。旧「新增供应商」多步页（AddProviderScreen）
 * 已死代码化——所有连接都从本页创建，方向在这里选。Chat = 现状默认；
 * Responses = openAI 类型 + useResponsesAPI；Anthropic = anthropic 类型。
 */
internal enum class NovexProviderDirection(
    val buttonLabel: String,
    val headerTitle: String,
    val headerSubtitle: String,
    val hint: String,
    val defaultLabel: String,
    val defaultBase: String,
    val defaultKeyHelpUrl: String,
    val defaultKeyHelpLabel: String,
) {
    CHAT(
        buttonLabel = "Chat",
        headerTitle = "连接 OpenAI（开放人工智能）兼容接口",
        headerSubtitle = "支持 DeepSeek（深度求索）与常见中转站。可直接保存启用，也可以按需检测当前模型或全部模型。",
        hint = "默认 /v1/chat/completions，中转站最通用。",
        defaultLabel = "DeepSeek",
        defaultBase = "https://api.deepseek.com",
        defaultKeyHelpUrl = "https://platform.deepseek.com/api_keys",
        defaultKeyHelpLabel = "前往 DeepSeek（深度求索）获取密钥",
    ),
    RESPONSES(
        buttonLabel = "Responses",
        headerTitle = "连接 OpenAI Responses 接口",
        headerSubtitle = "适合只开放 /v1/responses 一个口子的中转站，或官方 OpenAI。其余流程与 Chat 方向一致。",
        hint = "走 /v1/responses；chat 口子不可用的中转选这个。",
        defaultLabel = "OpenAI",
        defaultBase = "https://api.openai.com",
        defaultKeyHelpUrl = "https://platform.openai.com/api-keys",
        defaultKeyHelpLabel = "前往 OpenAI 官网获取密钥",
    ),
    ANTHROPIC(
        buttonLabel = "Anthropic",
        headerTitle = "连接 Anthropic 兼容接口",
        headerSubtitle = "适合 Anthropic 兼容中转（Claude 系模型），请求走 /v1/messages。其余流程与 Chat 方向一致。",
        hint = "走 /v1/messages；Anthropic 兼容中转选这个。",
        defaultLabel = "Anthropic",
        defaultBase = "https://api.anthropic.com",
        defaultKeyHelpUrl = "https://console.anthropic.com/settings/keys",
        defaultKeyHelpLabel = "前往 Anthropic 官网获取密钥",
    ),
}

/** 编辑已有连接时按实例反显方向（anthropic 类型 → Anthropic；openAI + useResponsesAPI → Responses）。 */
internal fun novexDirectionOf(instance: ProviderInstance?): NovexProviderDirection = when {
    instance == null -> NovexProviderDirection.CHAT
    instance.providerType == ProviderType.anthropic -> NovexProviderDirection.ANTHROPIC
    instance.useResponsesAPI -> NovexProviderDirection.RESPONSES
    else -> NovexProviderDirection.CHAT
}

internal fun novexProviderInstanceForSave(
    existing: ProviderInstance?,
    label: String,
    base: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection = NovexProviderDirection.CHAT,
): ProviderInstance {
    val fallbackLabel = when (direction) {
        NovexProviderDirection.CHAT -> "OpenAI 兼容接口"
        NovexProviderDirection.RESPONSES -> "OpenAI Responses 接口"
        NovexProviderDirection.ANTHROPIC -> "Anthropic 兼容接口"
    }
    val providerType = if (direction == NovexProviderDirection.ANTHROPIC) ProviderType.anthropic else ProviderType.openAI
    return (existing ?: ProviderInstance(
        id = UUID.randomUUID().toString(),
        label = label.ifBlank { fallbackLabel },
        providerType = providerType,
        credentialType = ProviderCredential.apiKey,
    )).copy(
        label = label.ifBlank { fallbackLabel },
        providerType = providerType,
        customBaseURL = base.trim().trimEnd('/'),
        appendV1Suffix = appendV1Suffix,
        useResponsesAPI = direction == NovexProviderDirection.RESPONSES,
        isEnabled = true,
    )
}

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
    // [T-provider-direction] 接口方向：编辑时按实例反显，新建默认 Chat。
    var direction by remember(instanceId) { mutableStateOf(novexDirectionOf(existing)) }
    var deleteConfirm by remember(instanceId) { mutableStateOf(false) }
    val existingEntries = remember(instanceId) { providerRepository.entriesFor(instanceId ?: "") }
    val initialModels = remember(instanceId) {
        existingEntries.filterNot { com.openminis.app.data.model.ChatModelSelection.imageOutput(it.model) || com.openminis.app.data.model.ChatModelSelection.imageOutput(it.baseModel) }
            .map { it.model.id }.distinct()
            .ifEmpty { if (existing == null) listOf(NOVEX_DEFAULT_DEEPSEEK_MODEL) else emptyList() }
    }
    val selectedModels = remember(instanceId) { mutableStateListOf<String>().apply { addAll(initialModels) } }
    val modelToolsEnabled = remember(instanceId) {
        mutableStateMapOf<String, Boolean>().apply {
            existingEntries
                .filterNot { com.openminis.app.data.model.ChatModelSelection.imageOutput(it.model) || com.openminis.app.data.model.ChatModelSelection.imageOutput(it.baseModel) }
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
    val fetchedMetadata = remember { mutableStateMapOf<String, LLMModel>() }
    var fetchedMetadataSource by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun invalidateVerification() {
        verificationJob?.cancel()
        verificationJob = null
        checkingModelId = null
        error = null
        verificationResults.clear()
    }
    // [T-provider-direction] 切方向：默认地址/名称未动过则跟着换，清掉旧协议
    // 拉取的模型缓存与未动过的 DeepSeek 预选，避免把 chat 拉的列表当成
    // anthropic 口子的可用模型。已勾选的非默认模型保留（模型 id 常通用）。
    fun switchDirection(next: NovexProviderDirection) {
        if (next == direction) return
        if (apiBase == direction.defaultBase) apiBase = next.defaultBase
        if (label == direction.defaultLabel) label = next.defaultLabel
        direction = next
        if (next != NovexProviderDirection.CHAT &&
            selectedModels.size == 1 && selectedModels.first() == NOVEX_DEFAULT_DEEPSEEK_MODEL
        ) {
            selectedModels.clear()
        }
        fetchedModels.clear()
        fetchedMetadata.clear()
        fetchedMetadataSource = null
        invalidateVerification()
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
                    direction,
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
        navigationIcon = { IconButton(onClick = onBack) { Icon(com.openminis.app.ui.novex.NovexIcons.ArrowBack, "返回") } },
        actions = {
            if (existing != null) {
                IconButton(onClick = { deleteConfirm = true }) {
                    Icon(com.openminis.app.ui.novex.NovexIcons.Delete, contentDescription = "删除 AI 服务商")
                }
            }
        },
    ) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(direction.headerTitle, style = MaterialTheme.typography.headlineSmall)
            Text(direction.headerSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(label = { Text("名称") }, value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            // [T-provider-direction] 接口方向三选一：选中项实底、其余描边。
            Column {
                Text("接口方向", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NovexProviderDirection.values().forEach { option ->
                        if (option == direction) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(option.buttonLabel) }
                        } else {
                            OutlinedButton(onClick = { switchDirection(option) }, modifier = Modifier.weight(1f)) { Text(option.buttonLabel) }
                        }
                    }
                }
                Text(
                    direction.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                NovexCheckToggle(
                    checked = appendV1Suffix,
                    onCheckedChange = {
                        appendV1Suffix = it
                        invalidateVerification()
                    },
                )
            }
            OutlinedTextField(label = { Text("API（应用程序接口）密钥") }, value = apiKey, onValueChange = { apiKey = it; invalidateVerification() }, leadingIcon = { Icon(com.openminis.app.ui.novex.NovexIcons.Key, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
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
                    val metadataBase = novexCanonicalBase(values.base, appendV1Suffix)
                    scope.launch {
                        val models = fetchModels(values.base, values.key, appendV1Suffix, direction)
                            .filterNot { looksLikeImageGenerationModel(it.id) }
                        fetchedMetadata.clear(); fetchedMetadata.putAll(models.associateBy { it.id })
                        fetchedMetadataSource = metadataBase to values.key
                        val ids = models.map { it.id }
                        fetchedModels.clear(); fetchedModels.addAll(ids)
                        if (models.isEmpty()) error = "没有拉取到模型，请检查地址和密钥，或继续手动填写模型名称。"
                        else {
                            if (selectedModels.none { it in ids }) setSelectedModels(listOf(ids.first()))
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
                        NovexCheckToggle(
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
                                    com.openminis.app.ui.novex.NovexIcons.Build,
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
                                if (checkingModelId == modelId) com.openminis.app.ui.novex.NovexIcons.Stop else com.openminis.app.ui.novex.NovexIcons.Refresh,
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
                    Icon(com.openminis.app.ui.novex.NovexIcons.Add, "添加模型")
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
                        ModelResultState.PASSED -> com.openminis.app.ui.novex.NovexIcons.CheckCircle
                        ModelResultState.WARNING, ModelResultState.FAILED -> com.openminis.app.ui.novex.NovexIcons.Error
                        ModelResultState.CANCELLED -> com.openminis.app.ui.novex.NovexIcons.Close
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
                        direction = direction,
                        key = values.key,
                        modelIds = values.models,
                        modelToolsEnabled = values.models.associateWith(::toolsEnabled),
                        metadata = if (fetchedMetadataSource == (novexCanonicalBase(values.base, appendV1Suffix) to values.key)) fetchedMetadata.toMap() else emptyMap(),
                    )
                }.onSuccess {
                    onSaved()
                }.onFailure { failure ->
                    error = "保存模型连接失败：${failure.message ?: failure.javaClass.simpleName}"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存并启用（${selectedModels.size}）") }
            // [T-qianchen-preset] 取钥链接按实例自适应：内置预设带 keyHelpUrl
            // （前尘 API → proxy.qianc.ltd）；其余按接口方向给默认指引。
            val helpUrl = existing?.keyHelpUrl ?: direction.defaultKeyHelpUrl
            val helpLabel = existing?.keyHelpUrl?.let { "前往 ${existing.label} 官网获取密钥" }
                ?: direction.defaultKeyHelpLabel
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl))) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(helpLabel) }
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

private suspend fun fetchModels(
    base: String,
    key: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection,
): List<LLMModel> = runCatching {
    val canonical = novexCanonicalBase(base, appendV1Suffix)
    val models = if (direction == NovexProviderDirection.ANTHROPIC) {
        com.openminis.app.provider.anthropic.AnthropicModelsApi.fetchModels(
            key,
            canonical,
            forceRefresh = true,
        )
    } else {
        OpenAIModelsApi.fetchModels(
            key,
            canonical,
            forceRefresh = true,
        )
    }
    models.distinctBy { it.id }
}.getOrDefault(emptyList())

private suspend fun verifyConnection(
    base: String,
    key: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection,
    modelIds: List<String>,
    toolEnabledByModel: (String) -> Boolean,
): ConnectionVerification = withContext(Dispatchers.IO) {
    val canonical = novexCanonicalBase(base, appendV1Suffix); val client = OkHttpClient()
    fun fatal(message: String): ConnectionVerification {
        return ConnectionVerification(NovexModelVerification(emptyList(), emptyList()), message)
    }
    // [T-provider-direction] Anthropic 方向的探活/鉴权探测带上版本头；
    // 兼容中转普遍同时收 Bearer，与 AnthropicModelsApi 的自定义端点行为一致。
    fun probeRequest(url: String, withKey: Boolean): Request = Request.Builder()
        .url(url)
        .apply { if (direction == NovexProviderDirection.ANTHROPIC) header("anthropic-version", "2023-06-01") }
        .apply { if (withKey) header("Authorization", "Bearer $key") }
        .build()
    val reachable = runCatching { client.newCall(probeRequest("$canonical/models", withKey = false)).execute().use { it.code in 200..499 } }.getOrDefault(false)
    if (!reachable) return@withContext fatal("无法连接接口地址")
    val authCode = runCatching { client.newCall(probeRequest("$canonical/models", withKey = true)).execute().use { it.code } }.getOrDefault(0)
    if (authCode == 401 || authCode == 403) return@withContext fatal("密钥无效或访问被拒绝（HTTP $authCode）")

    val instance = novexProviderInstanceForSave(
        existing = null,
        label = "连接检测",
        base = base,
        appendV1Suffix = appendV1Suffix,
        direction = direction,
    ).copy(id = "novex-check")
    val providerDisplayName = when (direction) {
        NovexProviderDirection.ANTHROPIC -> "Anthropic 兼容接口"
        else -> "OpenAI（开放人工智能）兼容接口"
    }
    val providerResults = mutableMapOf<String, Result<LLMProvider>>()
    fun providerFor(modelId: String): Result<LLMProvider> = providerResults.getOrPut(modelId) {
        val model = LLMModel(modelId, novexModelDisplayName(modelId), providerDisplayName)
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
    direction: NovexProviderDirection,
    key: String,
    modelIds: List<String>,
    modelToolsEnabled: Map<String, Boolean>,
    metadata: Map<String, LLMModel> = emptyMap(),
) {
    require(modelIds.none { looksLikeImageGenerationModel(it) || metadata[it]?.let(com.openminis.app.data.model.ChatModelSelection::imageOutput) == true }) {
        "请选择聊天模型，生图模型不能用于此对话"
    }
    val instance = novexProviderInstanceForSave(existing, label, base, appendV1Suffix, direction)
    if (existing == null) repository.addInstance(instance) else repository.updateInstance(instance)
    repository.saveApiKey(instance.id, key)
    val previousEntries = repository.entriesFor(instance.id)
        .filterNot { com.openminis.app.data.model.ChatModelSelection.imageOutput(it.model) || com.openminis.app.data.model.ChatModelSelection.imageOutput(it.baseModel) }
    val previousIds = previousEntries.map { it.id }.toSet()
    val group = repository.config.value.modelGroups.firstOrNull { candidate ->
        candidate.memberEntryIds.any { it in previousIds }
    }
    previousEntries.filter { it.model.id !in modelIds }.forEach { repository.removeEntry(it.id) }
    modelIds.forEach { modelId ->
        if (repository.entriesFor(instance.id).none { it.model.id == modelId && com.openminis.app.data.model.ChatModelSelection.eligible(it) }) {
            repository.addEntry(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = novexConnectionModel(metadata[modelId], LLMModel(
                        id = modelId,
                        displayName = novexModelDisplayName(modelId),
                        provider = instance.label,
                        inputModalities = novexChatInputModalities(modelId),
                        outputModalities = listOf("text"),
                    ), instance.effectiveBaseURL),
                    overrides = ModelOverrides(
                        supportsTools = modelToolsEnabled[modelId],
                    ),
                    isCustom = true,
                ),
            )
        }
    }
    repository.entriesFor(instance.id)
        .filterNot { com.openminis.app.data.model.ChatModelSelection.imageOutput(it.model) || com.openminis.app.data.model.ChatModelSelection.imageOutput(it.baseModel) }
        .forEach { entry ->
        val refreshedBase = novexConnectionModel(metadata[entry.model.id], entry.baseModel, instance.effectiveBaseURL)
        val desiredInput = novexChatInputModalities(entry.model.id, entry.model.inputModalities)
        val desiredOutput = listOf("text")
        val desiredTools = modelToolsEnabled[entry.model.id]
        if (entry.baseModel != refreshedBase || entry.model.inputModalities != desiredInput ||
            entry.model.outputModalities != desiredOutput ||
            entry.overrides.supportsTools != desiredTools
        ) {
            repository.updateEntry(
                entry.copy(
                    baseModel = refreshedBase,
                    overrides = entry.overrides.copy(
                        inputModalities = desiredInput,
                        outputModalities = desiredOutput,
                        supportsTools = desiredTools,
                    ),
                ),
            )
        }
    }
    val selectedEntries = repository.entriesFor(instance.id).filter {
        it.model.id in modelIds && com.openminis.app.data.model.ChatModelSelection.eligible(it)
    }
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

/** Fetched capacities survive saving; separately stored user overrides are untouched. */
internal fun novexConnectionModel(fetched: LLMModel?, previous: LLMModel, base: String?): LLMModel =
    com.openminis.app.data.model.NovexDeepSeekModelMetadata.official(
        fetched ?: com.openminis.app.provider.ModelsDevApi.enrichModel(previous), base)
