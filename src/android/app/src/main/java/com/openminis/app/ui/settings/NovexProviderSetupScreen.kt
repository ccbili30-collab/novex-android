package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

private enum class CheckState { WAITING, RUNNING, PASSED, FAILED }
private enum class VerificationSummaryState { PASSED, PARTIAL, FAILED }
private data class ConnectionCheck(val label: String, var state: CheckState, var detail: String = "")
private data class SetupValues(
    val base: String,
    val key: String,
    val models: List<String>,
    val imageModel: String?,
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
    val existingEntries = remember(instanceId) { providerRepository.entriesFor(instanceId ?: "") }
    val initialModels = remember(instanceId) {
        existingEntries.filterNot { it.model.outputModalities.orEmpty().contains("image") }
            .map { it.model.id }.distinct()
            .ifEmpty { if (existing == null) listOf(NOVEX_DEFAULT_DEEPSEEK_MODEL) else emptyList() }
    }
    val selectedModels = remember(instanceId) { mutableStateListOf<String>().apply { addAll(initialModels) } }
    var imageModelId by remember(instanceId) {
        mutableStateOf(existingEntries.firstOrNull { it.model.outputModalities.orEmpty().contains("image") }?.model?.id.orEmpty())
    }
    var manualModelId by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var imageModelMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var verificationReport by remember { mutableStateOf<String?>(null) }
    var verificationSummaryState by remember { mutableStateOf<VerificationSummaryState?>(null) }
    var fetchingModels by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var verificationMenuExpanded by remember { mutableStateOf(false) }
    val fetchedModels = remember { mutableStateListOf<String>() }
    val checks = remember { mutableStateListOf(
        ConnectionCheck("接口可连接", CheckState.WAITING), ConnectionCheck("密钥可验证", CheckState.WAITING),
        ConnectionCheck("普通对话可用", CheckState.WAITING), ConnectionCheck("工具调用可用", CheckState.WAITING),
    ) }
    fun resetChecks() { checks.indices.forEach { checks[it] = checks[it].copy(state = CheckState.WAITING, detail = "") } }
    fun invalidateVerification() {
        error = null
        verificationReport = null
        verificationSummaryState = null
        resetChecks()
    }
    fun setSelectedModels(models: List<String>) {
        selectedModels.clear()
        selectedModels.addAll(models.map(String::trim).filter(String::isNotEmpty).distinct())
        invalidateVerification()
    }
    fun validate(requireModels: Boolean = true): SetupValues? {
        val base = apiBase.trim().trimEnd('/')
        val key = apiKey.trim()
        val imageModel = imageModelId.trim().takeIf(String::isNotEmpty)
        val models = selectedModels.map(String::trim).filter(String::isNotEmpty).distinct().filterNot { it == imageModel }
        error = when {
            base.isEmpty() -> "请填写接口地址"
            !base.startsWith("http://") && !base.startsWith("https://") -> "接口地址需要以 https:// 或 http:// 开头"
            key.isEmpty() -> "请填写 API 密钥"
            requireModels && models.isEmpty() -> "请至少勾选一个模型"
            else -> null
        }
        return if (error == null) SetupValues(base, key, models, imageModel) else null
    }
    fun startVerification(values: SetupValues, modelIds: List<String>) {
        checking = true
        error = null
        verificationReport = null
        verificationSummaryState = null
        resetChecks()
        scope.launch {
            try {
                val verification = verifyConnection(
                    values.base,
                    values.key,
                    modelIds,
                ) { index, state, detail ->
                    checks[index] = checks[index].copy(state = state, detail = detail)
                }
                if (verification.fatalError != null) {
                    verificationSummaryState = VerificationSummaryState.FAILED
                    error = "检测失败：${verification.fatalError}"
                } else {
                    val result = verification.models
                    verificationSummaryState = when {
                        result.availableModels.isEmpty() -> VerificationSummaryState.FAILED
                        result.failures.isNotEmpty() -> VerificationSummaryState.PARTIAL
                        else -> VerificationSummaryState.PASSED
                    }
                    val headline = when (verificationSummaryState) {
                        VerificationSummaryState.PASSED -> "检测成功"
                        VerificationSummaryState.PARTIAL -> "检测完成：部分模型不可用"
                        VerificationSummaryState.FAILED -> "检测失败"
                        null -> "检测完成"
                    }
                    verificationReport = "$headline\n${formatNovexVerificationReport(result)}"
                }
            } catch (failure: Throwable) {
                verificationSummaryState = VerificationSummaryState.FAILED
                error = "检测失败：${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                checking = false
            }
        }
    }

    Scaffold(topBar = { TopAppBar(
        title = { Text(if (existing == null) "连接模型" else "模型连接") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
    ) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("连接 OpenAI（开放人工智能）兼容接口", style = MaterialTheme.typography.headlineSmall)
            Text("支持 DeepSeek（深度求索）与常见中转站。可直接保存启用，也可以按需检测当前模型或全部模型。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(label = { Text("名称") }, value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(label = { Text("接口地址") }, value = apiBase, onValueChange = { apiBase = it; invalidateVerification() }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(label = { Text("API（应用程序接口）密钥") }, value = apiKey, onValueChange = { apiKey = it; invalidateVerification() }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(
                "滑到最下方获取密钥",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { if (fetchedModels.isNotEmpty() || selectedModels.isNotEmpty()) modelMenuExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = when (selectedModels.size) {
                            0 -> "请选择模型"
                            1 -> novexModelDisplayName(selectedModels.first())
                            else -> "已选择 ${selectedModels.size} 个模型"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("启用的模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    val menuModels = (fetchedModels + selectedModels).distinct().take(100)
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        if (fetchedModels.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("全选已拉取模型") },
                                onClick = { setSelectedModels((selectedModels + fetchedModels).distinct()) },
                            )
                            DropdownMenuItem(
                                text = { Text("清空选择") },
                                onClick = { setSelectedModels(emptyList()) },
                            )
                            HorizontalDivider()
                        }
                        menuModels.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(novexModelDisplayName(id)) },
                                leadingIcon = {
                                    Checkbox(
                                        checked = id in selectedModels,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = { setSelectedModels(toggleModelSelection(selectedModels, id)) },
                            )
                        }
                    }
                }
                OutlinedButton(enabled = !fetchingModels && !checking, onClick = {
                    val values = validate(requireModels = false) ?: return@OutlinedButton
                    fetchingModels = true
                    scope.launch {
                        val models = fetchModels(values.base, values.key)
                        fetchedModels.clear(); fetchedModels.addAll(models)
                        if (models.isEmpty()) error = "没有拉取到模型，请检查地址和密钥，或继续手动填写模型名称。"
                        else {
                            if (selectedModels.none { it in models }) setSelectedModels(listOf(models.first()))
                            modelMenuExpanded = true
                        }
                        fetchingModels = false
                    }
                }) { Text(if (fetchingModels) "拉取中" else "拉取模型") }
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
                            setSelectedModels((selectedModels + id).distinct())
                            manualModelId = ""
                        }
                    },
                    enabled = manualModelId.isNotBlank(),
                ) {
                    Icon(Icons.Default.Add, "添加模型")
                }
            }
            if (selectedModels.isNotEmpty()) {
                Text(
                    "已勾选：${selectedModels.joinToString("、") { novexModelDisplayName(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExposedDropdownMenuBox(
                expanded = imageModelMenuExpanded,
                onExpandedChange = { imageModelMenuExpanded = it },
            ) {
                OutlinedTextField(
                    label = { Text("生图模型（可选）") },
                    value = imageModelId,
                    onValueChange = { value ->
                        imageModelId = value
                        if (value.isNotBlank()) selectedModels.remove(value.trim())
                        invalidateVerification()
                    },
                    placeholder = { Text("例如 gpt-image") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(imageModelMenuExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = imageModelMenuExpanded,
                    onDismissRequest = { imageModelMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("不使用生图模型") },
                        onClick = {
                            imageModelId = ""
                            imageModelMenuExpanded = false
                            invalidateVerification()
                        },
                    )
                    val candidates = fetchedModels.distinct().sortedWith(
                        compareByDescending<String>(::looksLikeImageGenerationModel).thenBy(String::lowercase),
                    )
                    candidates.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(novexModelDisplayName(id)) },
                            onClick = {
                                imageModelId = id
                                selectedModels.remove(id)
                                imageModelMenuExpanded = false
                                invalidateVerification()
                            },
                        )
                    }
                }
            }
            Text(
                "配置后，人工智能需要图片时会自动调用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("连通检测（可选）", style = MaterialTheme.typography.titleMedium)
                    selectedModels.firstOrNull()?.let { modelId ->
                        Text(
                            "当前模型：${novexModelDisplayName(modelId)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(
                        enabled = !fetchingModels && !checking && selectedModels.isNotEmpty(),
                        onClick = { verificationMenuExpanded = true },
                    ) {
                        Icon(Icons.Default.MoreVert, "更多检测选项")
                    }
                    DropdownMenu(
                        expanded = verificationMenuExpanded,
                        onDismissRequest = { verificationMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("检测全部已选模型") },
                            onClick = {
                                verificationMenuExpanded = false
                                val values = validate() ?: return@DropdownMenuItem
                                startVerification(values, values.models)
                            },
                        )
                    }
                }
            }
            checks.forEach { CheckRow(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            verificationReport?.let {
                val reportColor = when (verificationSummaryState) {
                    VerificationSummaryState.PASSED -> Color(0xFF168A45)
                    VerificationSummaryState.PARTIAL -> Color(0xFFB77900)
                    VerificationSummaryState.FAILED -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(it, color = reportColor, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                enabled = !fetchingModels && !checking && selectedModels.isNotEmpty(),
                onClick = {
                    val values = validate() ?: return@OutlinedButton
                    val currentModel = values.models.firstOrNull() ?: return@OutlinedButton
                    startVerification(values, listOf(currentModel))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (checking) "正在检测…" else "检测当前模型")
            }
            Button(enabled = !fetchingModels && !checking, onClick = {
                val values = validate() ?: return@Button
                error = null
                runCatching {
                    saveConnections(
                        repository = providerRepository,
                        existing = existing,
                        label = label,
                        base = values.base,
                        key = values.key,
                        modelIds = values.models,
                        imageModelId = values.imageModel,
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
}

@Composable private fun CheckRow(check: ConnectionCheck) {
    val color = when (check.state) { CheckState.PASSED -> Color(0xFF168A45); CheckState.FAILED -> MaterialTheme.colorScheme.error; CheckState.RUNNING -> Color(0xFFB77900); CheckState.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant }
    val icon = when (check.state) { CheckState.PASSED -> Icons.Default.CheckCircle; CheckState.FAILED -> Icons.Default.Error; else -> Icons.Default.HourglassTop }
    val stateText = when (check.state) { CheckState.PASSED -> "通过"; CheckState.FAILED -> "失败"; CheckState.RUNNING -> "检测中"; CheckState.WAITING -> "待检测" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = color)
        Column(Modifier.weight(1f)) { Text(check.label); if (check.detail.isNotBlank()) Text(check.detail, style = MaterialTheme.typography.bodySmall, color = color) }
        Text(stateText, color = color)
    }
}

private suspend fun fetchModels(base: String, key: String): List<String> = runCatching {
    OpenAIModelsApi.fetchModels(key, canonicalBase(base), forceRefresh = true).map { it.id }.distinct()
}.getOrDefault(emptyList())

private fun canonicalBase(base: String): String = base.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }

private suspend fun verifyConnection(base: String, key: String, modelIds: List<String>, update: (Int, CheckState, String) -> Unit): ConnectionVerification = withContext(Dispatchers.IO) {
    val canonical = canonicalBase(base); val client = OkHttpClient()
    fun fatal(index: Int, message: String): ConnectionVerification {
        update(index, CheckState.FAILED, message)
        return ConnectionVerification(NovexModelVerification(emptyList(), emptyList()), message)
    }
    update(0, CheckState.RUNNING, "")
    val reachable = runCatching { client.newCall(Request.Builder().url("$canonical/models").build()).execute().use { it.code in 200..499 } }.getOrDefault(false)
    if (!reachable) return@withContext fatal(0, "无法连接接口地址")
    update(0, CheckState.PASSED, "服务器已响应")
    update(1, CheckState.RUNNING, "")
    val authCode = runCatching { client.newCall(Request.Builder().url("$canonical/models").header("Authorization", "Bearer $key").build()).execute().use { it.code } }.getOrDefault(0)
    if (authCode == 401 || authCode == 403) return@withContext fatal(1, "密钥无效或访问被拒绝（HTTP $authCode）")
    if (authCode in 200..299) update(1, CheckState.PASSED, "身份验证成功")
    else update(1, CheckState.RUNNING, "站点未开放模型列表，将通过实际对话验证")

    val instance = ProviderInstance(id = "novex-check", label = "连接检测", providerType = ProviderType.openAI, credentialType = ProviderCredential.apiKey, customBaseURL = base, appendV1Suffix = !base.trimEnd('/').endsWith("/v1"))
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
                    tools = verificationTools,
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

    update(2, CheckState.RUNNING, "")
    val result = verifyNovexModels(
        modelIds = modelIds,
        repetitions = 3,
        onProgress = { stage, index, total, modelId ->
            val row = if (stage == NovexProbeStage.CHAT) 2 else 3
            update(row, CheckState.RUNNING, "正在检测 ${index + 1}/$total：$modelId")
        },
        chatProbe = ::probeChat,
        toolProbe = ::probeTool,
    )
    val chatFailures = result.failures.filter { it.stage == NovexProbeStage.CHAT }
    val toolFailures = result.failures.filter { it.stage == NovexProbeStage.TOOL }
    val chatPassedCount = modelIds.distinct().size - chatFailures.size
    update(
        2,
        if (chatPassedCount > 0) CheckState.PASSED else CheckState.FAILED,
        if (chatFailures.isEmpty()) "$chatPassedCount 个模型普通回复正常"
        else "通过 $chatPassedCount 个；失败：${chatFailures.joinToString("、") { it.modelId }}",
    )
    update(
        3,
        if (result.availableModels.isNotEmpty()) CheckState.PASSED else CheckState.FAILED,
        if (toolFailures.isEmpty()) "${result.availableModels.size} 个模型工具调用正常"
        else "可用 ${result.availableModels.size} 个；失败：${toolFailures.joinToString("、") { it.modelId }}",
    )
    if (authCode !in 200..299) {
        if (chatPassedCount > 0) update(1, CheckState.PASSED, "已通过实际对话验证密钥")
        else update(1, CheckState.FAILED, "密钥或模型无法通过实际请求验证")
    }
    ConnectionVerification(result)
}

private fun saveConnections(
    repository: ProviderRepository,
    existing: ProviderInstance?,
    label: String,
    base: String,
    key: String,
    modelIds: List<String>,
    imageModelId: String?,
) {
    val instance = (existing ?: ProviderInstance(id = UUID.randomUUID().toString(), label = label.ifBlank { "OpenAI 兼容接口" }, providerType = ProviderType.openAI, credentialType = ProviderCredential.apiKey)).copy(label = label.ifBlank { "OpenAI 兼容接口" }, customBaseURL = base, appendV1Suffix = !base.trimEnd('/').endsWith("/v1"), isEnabled = true)
    if (existing == null) repository.addInstance(instance) else repository.updateInstance(instance)
    repository.saveApiKey(instance.id, key)
    val previousEntries = repository.entriesFor(instance.id)
    val previousIds = previousEntries.map { it.id }.toSet()
    val group = repository.config.value.modelGroups.firstOrNull { candidate ->
        candidate.memberEntryIds.any { it in previousIds }
    }
    val allModelIds = (modelIds + listOfNotNull(imageModelId)).distinct()
    previousEntries.filter { it.model.id !in allModelIds }.forEach { repository.removeEntry(it.id) }
    allModelIds.forEach { modelId ->
        if (repository.entriesFor(instance.id).none { it.model.id == modelId }) {
            val isImageModel = modelId == imageModelId
            repository.addEntry(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = LLMModel(
                        id = modelId,
                        displayName = novexModelDisplayName(modelId),
                        provider = instance.label,
                        inputModalities = if (isImageModel) {
                            listOf("text", "image")
                        } else {
                            novexChatInputModalities(modelId)
                        },
                        outputModalities = if (isImageModel) listOf("image") else listOf("text"),
                    ),
                    isCustom = true,
                ),
            )
        }
    }
    repository.entriesFor(instance.id).forEach { entry ->
        val shouldBeImage = entry.model.id == imageModelId
        val desiredInput = if (shouldBeImage) {
            listOf("text", "image")
        } else {
            novexChatInputModalities(entry.model.id, entry.model.inputModalities)
        }
        val desiredOutput = if (shouldBeImage) listOf("image") else listOf("text")
        if (entry.model.inputModalities != desiredInput || entry.model.outputModalities != desiredOutput) {
            repository.updateEntry(
                entry.copy(
                    overrides = entry.overrides.copy(
                        inputModalities = desiredInput,
                        outputModalities = desiredOutput,
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
    imageModelId?.let { selectedImageId ->
        repository.entriesFor(instance.id).firstOrNull { it.model.id == selectedImageId }?.let { imageEntry ->
            repository.addAgentLoopEntry(imageEntry.id)
        }
    }
}
