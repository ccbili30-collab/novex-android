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
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIModelsApi
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

private enum class CheckState { WAITING, RUNNING, PASSED, FAILED }
private data class ConnectionCheck(val label: String, var state: CheckState, var detail: String = "")
private data class SetupValues(val base: String, val key: String, val models: List<String>)

internal fun toggleModelSelection(current: List<String>, clicked: String): List<String> {
    val clean = clicked.trim()
    if (clean.isEmpty()) return current.distinct()
    val normalized = current.map(String::trim).filter(String::isNotEmpty).distinct()
    return if (clean in normalized) normalized - clean else normalized + clean
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
    val initialModels = remember(instanceId) {
        providerRepository.entriesFor(instanceId ?: "").map { it.model.id }.distinct()
            .ifEmpty { if (existing == null) listOf("deepseek-chat") else emptyList() }
    }
    val selectedModels = remember(instanceId) { mutableStateListOf<String>().apply { addAll(initialModels) } }
    var manualModelId by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val fetchedModels = remember { mutableStateListOf<String>() }
    val checks = remember { mutableStateListOf(
        ConnectionCheck("接口可连接", CheckState.WAITING), ConnectionCheck("密钥可验证", CheckState.WAITING),
        ConnectionCheck("普通对话可用", CheckState.WAITING), ConnectionCheck("工具调用可用", CheckState.WAITING),
    ) }
    fun resetChecks() { checks.indices.forEach { checks[it] = checks[it].copy(state = CheckState.WAITING, detail = "") } }
    fun setSelectedModels(models: List<String>) {
        selectedModels.clear()
        selectedModels.addAll(models.map(String::trim).filter(String::isNotEmpty).distinct())
        error = null
        resetChecks()
    }
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

    Scaffold(topBar = { TopAppBar(
        title = { Text(if (existing == null) "连接模型" else "模型连接") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
    ) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("连接 OpenAI（开放人工智能）兼容接口", style = MaterialTheme.typography.headlineSmall)
            Text("支持 DeepSeek 与常见中转站。可自动拉取模型，也可以手动填写。通过四项检测后才会启用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(label = { Text("名称") }, value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(label = { Text("接口地址") }, value = apiBase, onValueChange = { apiBase = it; error = null; resetChecks() }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(label = { Text("API（应用程序接口）密钥") }, value = apiKey, onValueChange = { apiKey = it; error = null; resetChecks() }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
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
                            1 -> selectedModels.first()
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
                                text = { Text(id) },
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
                OutlinedButton(enabled = !testing, onClick = {
                    val values = validate(requireModels = false) ?: return@OutlinedButton
                    testing = true
                    scope.launch {
                        val models = fetchModels(values.base, values.key)
                        fetchedModels.clear(); fetchedModels.addAll(models)
                        if (models.isEmpty()) error = "没有拉取到模型，请检查地址和密钥，或继续手动填写模型名称。"
                        else {
                            if (selectedModels.none { it in models }) setSelectedModels(listOf(models.first()))
                            modelMenuExpanded = true
                        }
                        testing = false
                    }
                }) { Text(if (testing) "拉取中" else "拉取模型") }
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
                    "已勾选：${selectedModels.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("连通检测", style = MaterialTheme.typography.titleMedium)
            checks.forEach { CheckRow(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !testing, onClick = {
                val values = validate() ?: return@Button
                testing = true; error = null; resetChecks()
                scope.launch {
                    val ok = verifyConnection(values.base, values.key, values.models) { index, state, detail -> checks[index] = checks[index].copy(state = state, detail = detail) }
                    if (ok) { saveConnections(providerRepository, existing, label, values.base, values.key, values.models); onSaved() }
                    else error = "检测未全部通过，勾选的模型都不会启用。请取消失败模型或修改配置后重试。"
                    testing = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (testing) "正在检测…" else "检测并启用（${selectedModels.size}）") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys"))) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("前往 DeepSeek 获取密钥") }
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

private suspend fun verifyConnection(base: String, key: String, modelIds: List<String>, update: (Int, CheckState, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
    val canonical = canonicalBase(base); val client = OkHttpClient()
    fun fail(index: Int, message: String): Boolean { update(index, CheckState.FAILED, message); return false }
    update(0, CheckState.RUNNING, "")
    val reachable = runCatching { client.newCall(Request.Builder().url("$canonical/models").build()).execute().use { it.code in 200..499 } }.getOrDefault(false)
    if (!reachable) return@withContext fail(0, "无法连接接口地址")
    update(0, CheckState.PASSED, "服务器已响应")
    update(1, CheckState.RUNNING, "")
    val authCode = runCatching { client.newCall(Request.Builder().url("$canonical/models").header("Authorization", "Bearer $key").build()).execute().use { it.code } }.getOrDefault(0)
    if (authCode == 401 || authCode == 403) return@withContext fail(1, "密钥无效或访问被拒绝")
    if (authCode in 200..299) update(1, CheckState.PASSED, "身份验证成功")
    else update(1, CheckState.RUNNING, "站点未开放模型列表，将通过实际对话验证")

    val instance = ProviderInstance(id = "novex-check", label = "连接检测", providerType = ProviderType.openAI, credentialType = ProviderCredential.apiKey, customBaseURL = base, appendV1Suffix = !base.trimEnd('/').endsWith("/v1"))
    update(2, CheckState.RUNNING, "")
    for ((index, modelId) in modelIds.withIndex()) {
        update(2, CheckState.RUNNING, "正在检测 ${index + 1}/${modelIds.size}：$modelId")
        val model = LLMModel(modelId, LLMModel.modelDisplayName(modelId), "OpenAI 兼容接口")
        val provider = runCatching { ProviderFactory.create(instance, key, model) }
            .getOrElse { return@withContext fail(2, "$modelId：无法创建模型连接") }
        val chatOk = runCatching { withTimeout(45_000) { provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "只回复：连接成功")), null, 64).text.isNotBlank() } }.getOrDefault(false)
        if (!chatOk) {
            if (authCode !in 200..299) update(1, CheckState.FAILED, "密钥或模型无法通过实际请求验证")
            return@withContext fail(2, "$modelId：没有返回有效文字")
        }
    }
    if (authCode !in 200..299) update(1, CheckState.PASSED, "已通过实际对话验证密钥")
    update(2, CheckState.PASSED, "${modelIds.size} 个模型普通回复正常")
    update(3, CheckState.RUNNING, "")
    val probe = AgentToolDefinition("novex_probe", "必须调用此工具完成连接检测", emptyMap())
    for ((index, modelId) in modelIds.withIndex()) {
        update(3, CheckState.RUNNING, "正在检测 ${index + 1}/${modelIds.size}：$modelId")
        val model = LLMModel(modelId, LLMModel.modelDisplayName(modelId), "OpenAI 兼容接口")
        val provider = runCatching { ProviderFactory.create(instance, key, model) }
            .getOrElse { return@withContext fail(3, "$modelId：无法创建模型连接") }
        val toolOk = runCatching {
            var called = false
            withTimeout(45_000) { provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "请立即调用 novex_probe，不要输出文字。")), null, 128, tools = listOf(probe)).collect { if (it is LLMStreamChunk.ToolCallComplete && it.name == "novex_probe") called = true } }
            called
        }.getOrDefault(false)
        if (!toolOk) return@withContext fail(3, "$modelId：未返回工具调用，不能用于 Novex")
    }
    update(3, CheckState.PASSED, "${modelIds.size} 个模型工具调用正常")
    true
}

private fun saveConnections(repository: ProviderRepository, existing: ProviderInstance?, label: String, base: String, key: String, modelIds: List<String>) {
    val instance = (existing ?: ProviderInstance(id = UUID.randomUUID().toString(), label = label.ifBlank { "OpenAI 兼容接口" }, providerType = ProviderType.openAI, credentialType = ProviderCredential.apiKey)).copy(label = label.ifBlank { "OpenAI 兼容接口" }, customBaseURL = base, appendV1Suffix = !base.trimEnd('/').endsWith("/v1"), isEnabled = true)
    if (existing == null) repository.addInstance(instance) else repository.updateInstance(instance)
    repository.saveApiKey(instance.id, key)
    val previousEntries = repository.entriesFor(instance.id)
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
                    baseModel = LLMModel(modelId, LLMModel.modelDisplayName(modelId), instance.label),
                    isCustom = true,
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
