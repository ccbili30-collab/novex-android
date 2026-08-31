package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.tools.isImageGenerationEntry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ImageProtocol(val title: String) {
    OPENAI("OpenAI（开放人工智能）兼容图片接口"),
    GEMINI("Gemini（双子星）原生接口"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationSettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    var editingGroupId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var apiBase by remember { mutableStateOf("https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var modelsText by remember { mutableStateOf("gpt-image-1") }
    var protocol by remember { mutableStateOf(ImageProtocol.OPENAI) }
    var endpointMode by remember { mutableStateOf(ImageEndpointMode.auto) }
    var protocolExpanded by remember { mutableStateOf(false) }
    var endpointExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { providerRepository.ensureImageGenerationMigration() }
    }

    val entryById = config.modelEntries.associateBy { it.id }
    val imageGroups = config.modelGroups.filter { group ->
        group.id in config.imageGenerationGroupIds ||
            group.memberEntryIds.any { id -> entryById[id]?.let(::isImageGenerationEntry) == true }
    }
    val enabledIds = config.imageGenerationGroupIds

    fun resetForm() {
        editingGroupId = null
        label = ""
        apiBase = "https://api.openai.com/v1"
        apiKey = ""
        modelsText = "gpt-image-1"
        protocol = ImageProtocol.OPENAI
        endpointMode = ImageEndpointMode.auto
        error = null
    }

    fun editGroup(group: ModelGroup) {
        val entries = group.memberEntryIds.mapNotNull(entryById::get)
        val providerIds = entries.map { it.providerInstanceId }.distinct()
        if (providerIds.size != 1) {
            error = "该迁移分组包含多个提供商，请保留为自动降级组，或新建独立生图服务。"
            return
        }
        val instance = config.instances.firstOrNull { it.id == providerIds.single() } ?: return
        editingGroupId = group.id
        label = group.name
        apiBase = instance.customBaseURL.orEmpty()
        apiKey = providerRepository.loadApiKey(instance.id).orEmpty()
        modelsText = entries.joinToString("，") { it.model.id }
        protocol = if (instance.providerType == ProviderType.gemini) ImageProtocol.GEMINI else ImageProtocol.OPENAI
        endpointMode = instance.imageEndpointMode
        error = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生图服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("生图分组", style = MaterialTheme.typography.titleMedium)
            Text(
                "勾选即启用；排序决定自动尝试顺序。一个分组内的多个模型也会依次降级。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (imageGroups.isEmpty()) {
                Text("尚未配置生图服务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            imageGroups.forEach { group ->
                val enabled = group.id in enabledIds
                val enabledIndex = enabledIds.indexOf(group.id)
                val models = group.memberEntryIds.mapNotNull(entryById::get)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { providerRepository.setImageGenerationGroupEnabled(group.id, it) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            models.joinToString("、") { it.model.id }.ifBlank { "没有有效的图片模型" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        enabled = enabled && enabledIndex > 0,
                        onClick = {
                            val reordered = enabledIds.toMutableList()
                            val item = reordered.removeAt(enabledIndex)
                            reordered.add(enabledIndex - 1, item)
                            providerRepository.reorderImageGenerationGroups(reordered)
                        },
                    ) { Icon(Icons.Default.ArrowUpward, "上移") }
                    IconButton(
                        enabled = enabled && enabledIndex >= 0 && enabledIndex < enabledIds.lastIndex,
                        onClick = {
                            val reordered = enabledIds.toMutableList()
                            val item = reordered.removeAt(enabledIndex)
                            reordered.add(enabledIndex + 1, item)
                            providerRepository.reorderImageGenerationGroups(reordered)
                        },
                    ) { Icon(Icons.Default.ArrowDownward, "下移") }
                    IconButton(onClick = { editGroup(group) }) {
                        Icon(Icons.Default.Edit, "编辑")
                    }
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(4.dp))
            Text(if (editingGroupId == null) "新增生图服务" else "编辑生图服务", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it; error = null },
                label = { Text("分组名称") },
                placeholder = { Text("例如：主生图线路") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Column {
                OutlinedButton(onClick = { protocolExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(protocol.title, modifier = Modifier.weight(1f))
                }
                DropdownMenu(expanded = protocolExpanded, onDismissRequest = { protocolExpanded = false }) {
                    ImageProtocol.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            onClick = {
                                protocol = option
                                protocolExpanded = false
                                if (option == ImageProtocol.GEMINI && apiBase.contains("openai.com")) {
                                    apiBase = "https://generativelanguage.googleapis.com/v1beta"
                                }
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = apiBase,
                onValueChange = { apiBase = it; error = null },
                label = { Text("接口地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; error = null },
                label = { Text("API（应用程序接口）密钥") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = modelsText,
                onValueChange = { modelsText = it; error = null },
                label = { Text("模型（多个用逗号分隔）") },
                placeholder = { Text("gpt-image-1，gpt-image-2") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (protocol == ImageProtocol.OPENAI) {
                Column {
                    OutlinedButton(onClick = { endpointExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (endpointMode) {
                                ImageEndpointMode.auto -> "图片端点：自动检测"
                                ImageEndpointMode.imagesGenerations -> "图片端点：/images/generations"
                                ImageEndpointMode.chatCompletions -> "图片端点：对话接口"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    DropdownMenu(expanded = endpointExpanded, onDismissRequest = { endpointExpanded = false }) {
                        ImageEndpointMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (mode) {
                                            ImageEndpointMode.auto -> "自动检测"
                                            ImageEndpointMode.imagesGenerations -> "/images/generations"
                                            ImageEndpointMode.chatCompletions -> "对话接口"
                                        },
                                    )
                                },
                                onClick = { endpointMode = mode; endpointExpanded = false },
                            )
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (editingGroupId != null) {
                    TextButton(onClick = ::resetForm) { Text("取消编辑") }
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val models = modelsText.split(',', '，', '\n')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .distinct()
                        val base = apiBase.trim().trimEnd('/')
                        error = when {
                            label.isBlank() -> "请填写分组名称"
                            !base.startsWith("https://") && !base.startsWith("http://") -> "接口地址必须以 https:// 或 http:// 开头"
                            apiKey.isBlank() -> "请填写 API（应用程序接口）密钥"
                            models.isEmpty() -> "请至少填写一个生图模型"
                            else -> null
                        }
                        if (error == null) {
                            runCatching {
                                saveImageGenerationService(
                                    repository = providerRepository,
                                    editingGroupId = editingGroupId,
                                    label = label.trim(),
                                    base = base,
                                    key = apiKey.trim(),
                                    models = models,
                                    protocol = protocol,
                                    endpointMode = endpointMode,
                                )
                            }.onSuccess { resetForm() }
                                .onFailure { error = it.message ?: it.javaClass.simpleName }
                        }
                    },
                ) {
                    Icon(if (editingGroupId == null) Icons.Default.Image else Icons.Default.Save, null)
                    Text(if (editingGroupId == null) "添加并启用" else "保存修改", modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun saveImageGenerationService(
    repository: ProviderRepository,
    editingGroupId: String?,
    label: String,
    base: String,
    key: String,
    models: List<String>,
    protocol: ImageProtocol,
    endpointMode: ImageEndpointMode,
) {
    val existingGroup = editingGroupId?.let(repository::group)
    val existingEntries = existingGroup?.memberEntryIds.orEmpty()
        .mapNotNull { id -> repository.config.value.modelEntries.firstOrNull { it.id == id } }
    val existingProviderId = existingEntries.map { it.providerInstanceId }.distinct().singleOrNull()
    val existingProvider = existingProviderId?.let(repository::instance)
    val providerType = if (protocol == ImageProtocol.GEMINI) ProviderType.gemini else ProviderType.openAI
    val instance = (existingProvider ?: ProviderInstance(
        id = UUID.randomUUID().toString(),
        label = label,
        providerType = providerType,
        credentialType = ProviderCredential.apiKey,
    )).copy(
        label = label,
        customBaseURL = base,
        appendV1Suffix = protocol == ImageProtocol.OPENAI && !base.endsWith("/v1"),
        imageEndpointMode = if (protocol == ImageProtocol.OPENAI) endpointMode else ImageEndpointMode.chatCompletions,
        imageEndpointResolved = null,
        isEnabled = true,
    )
    if (existingProvider == null) {
        repository.addInstance(instance)
        // Dedicated image providers must not retain text-model seeds added for
        // an official endpoint.
        repository.entriesFor(instance.id).forEach { repository.removeEntry(it.id) }
    } else {
        require(existingProvider.providerType == providerType) {
            "编辑时不能直接切换协议，请新建另一个生图服务"
        }
        repository.updateInstance(instance)
    }
    repository.saveApiKey(instance.id, key)
    repository.setImageGenerationProvider(instance.id, true)

    val currentImageEntries = repository.entriesFor(instance.id).filter(::isImageGenerationEntry)
    currentImageEntries.filter { it.model.id !in models }.forEach { repository.removeEntry(it.id) }
    for (modelId in models) {
        if (repository.entriesFor(instance.id).none { it.model.id == modelId }) {
            repository.addEntry(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = LLMModel(
                        id = modelId,
                        displayName = LLMModel.modelDisplayName(modelId),
                        provider = label,
                        inputModalities = listOf("text", "image"),
                        outputModalities = listOf("image"),
                    ),
                    isCustom = true,
                ),
            )
        }
    }
    val memberIds = repository.entriesFor(instance.id)
        .filter { it.model.id in models && isImageGenerationEntry(it) }
        .map { it.id }
        .toMutableList()
    val group = existingGroup?.copy(name = label, memberEntryIds = memberIds)
        ?: ModelGroup(name = label, memberEntryIds = memberIds)
    if (existingGroup == null) repository.addGroup(group) else repository.updateGroup(group)
    repository.setImageGenerationGroupEnabled(group.id, true)
    repository.ensureImageGenerationMigration()
}
