package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.novex.Button
import androidx.compose.material3.CircularProgressIndicator
import com.openminis.app.ui.novex.DropdownMenu
import com.openminis.app.ui.novex.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.OutlinedButton
import com.openminis.app.ui.novex.OutlinedTextField
import com.openminis.app.ui.novex.NovexCheckToggle
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.image.ImageModelCatalog
import com.openminis.app.R
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.NovexTopTextAction
import com.openminis.app.ui.novex.NovexDestructiveConfirmationDialog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ImageProtocol(val title: String) {
    OPENAI("OpenAI 兼容"),
    GEMINI("Gemini 原生"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationSettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddSource: () -> Unit,
    onSourceClick: (String) -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { providerRepository.ensureImageGenerationMigration() }
    }
    val sources = config.imageGenerationProviderInstanceIds.mapNotNull { id ->
        config.instances.firstOrNull { it.id == id }
    }

    SettingsScaffold(
        title = "生图来源",
        onBack = onBack,
        actions = {
            NovexTopAction(
                icon = R.drawable.ic_phosphor_plus,
                contentDescription = "新增来源",
                onClick = onAddSource,
            )
        },
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "每个来源独立保存地址与密钥；进入来源后拉取、勾选并排序模型。这里的顺序决定跨来源自动降级顺序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sources.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("还没有生图来源", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onAddSource) {
                        Icon(com.openminis.app.ui.novex.NovexIcons.Add, null)
                        Text("新增来源", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            sources.forEachIndexed { index, source ->
                val group = providerRepository.imageGenerationGroupForProvider(source.id)
                val enabled = group?.id in config.imageGenerationGroupIds
                val selectedCount = group?.memberEntryIds?.size ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSourceClick(source.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NovexCheckToggle(
                        checked = enabled,
                        enabled = selectedCount > 0,
                        onCheckedChange = { checked ->
                            group?.let { providerRepository.setImageGenerationGroupEnabled(it.id, checked) }
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${if (source.providerType == ProviderType.gemini) "Gemini 原生" else "OpenAI 兼容"} · 已选 $selectedCount 个模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            source.effectiveBaseURL.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    IconButton(
                        enabled = index > 0,
                        onClick = {
                            val order = sources.map { it.id }.toMutableList()
                            order.add(index - 1, order.removeAt(index))
                            providerRepository.reorderImageGenerationProviders(order)
                        },
                    ) { Icon(com.openminis.app.ui.novex.NovexIcons.ArrowUpward, "上移") }
                    IconButton(
                        enabled = index < sources.lastIndex,
                        onClick = {
                            val order = sources.map { it.id }.toMutableList()
                            order.add(index + 1, order.removeAt(index))
                            providerRepository.reorderImageGenerationProviders(order)
                        },
                    ) { Icon(com.openminis.app.ui.novex.NovexIcons.ArrowDownward, "下移") }
                    Icon(com.openminis.app.ui.novex.NovexIcons.KeyboardArrowRight, null)
                }
                if (index < sources.lastIndex) HorizontalDivider()
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationSourceScreen(
    sourceId: String?,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    providerRepository.config.collectAsState().value
    val scope = rememberCoroutineScope()
    val initial = remember(sourceId) { sourceId?.let(providerRepository::instance) }
    var savedId by remember(sourceId) { mutableStateOf(initial?.id) }
    var label by remember(sourceId) { mutableStateOf(initial?.label.orEmpty()) }
    var apiBase by remember(sourceId) { mutableStateOf(initial?.customBaseURL ?: "https://api.openai.com/v1") }
    var apiKey by remember(sourceId) {
        mutableStateOf(initial?.let { providerRepository.loadApiKey(it.id) }.orEmpty())
    }
    var protocol by remember(sourceId) {
        mutableStateOf(if (initial?.providerType == ProviderType.gemini) ImageProtocol.GEMINI else ImageProtocol.OPENAI)
    }
    var sourceEndpoint by remember(sourceId) {
        mutableStateOf(initial?.imageEndpointMode ?: ImageEndpointMode.auto)
    }
    var appendV1Suffix by remember(sourceId) {
        mutableStateOf(initial?.appendV1Suffix ?: true)
    }
    var protocolExpanded by remember { mutableStateOf(false) }
    var endpointExpanded by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var manualModel by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }

    fun validate(): String? {
        val base = apiBase.trim()
        if (label.isBlank()) return "请填写来源名称"
        if (!base.startsWith("https://") && !base.startsWith("http://")) return "接口地址必须以 https:// 或 http:// 开头"
        val official = base.contains("api.openai.com", true) || base.contains("generativelanguage.googleapis.com", true)
        if (official && apiKey.isBlank()) return "官方接口需要 API Key"
        return null
    }

    fun saveSource(): String? {
        validate()?.let { status = it; return null }
        val id = savedId ?: UUID.randomUUID().toString()
        val previous = providerRepository.instance(id)
        val providerType = if (protocol == ImageProtocol.GEMINI) ProviderType.gemini else ProviderType.openAI
        if (previous != null && previous.providerType != providerType) {
            status = "已有来源不能直接切换协议，请新建来源"
            return null
        }
        val instance = (previous ?: ProviderInstance(
            id = id,
            label = label.trim(),
            providerType = providerType,
            credentialType = ProviderCredential.apiKey,
        )).copy(
            label = label.trim(),
            customBaseURL = apiBase.trim().trimEnd('/'),
            appendV1Suffix = providerType != ProviderType.gemini && appendV1Suffix,
            imageEndpointMode = if (providerType == ProviderType.openAI) sourceEndpoint else ImageEndpointMode.chatCompletions,
            imageEndpointResolved = null,
            isEnabled = true,
        )
        if (previous == null) {
            providerRepository.addInstance(instance)
            providerRepository.entriesFor(id).forEach { providerRepository.removeEntry(it.id) }
        } else {
            providerRepository.updateInstance(instance)
        }
        providerRepository.saveApiKey(id, apiKey.trim())
        providerRepository.setImageGenerationProvider(id, true)
        providerRepository.ensureImageGenerationGroupForProvider(id, label.trim())
        savedId = id
        status = "已保存"
        return id
    }

    fun pullModels() {
        val id = saveSource() ?: return
        if (pulling) return
        scope.launch {
            pulling = true
            status = "正在拉取模型…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ImageModelCatalog.fetch(
                        providerType = if (protocol == ImageProtocol.GEMINI) ProviderType.gemini else ProviderType.openAI,
                        baseURL = apiBase.trim().trimEnd('/'),
                        apiKey = apiKey.trim(),
                        appendV1Suffix = protocol == ImageProtocol.OPENAI && appendV1Suffix,
                    )
                }
            }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    status = "接口返回成功，但没有可用模型；已保留现有列表"
                } else {
                    providerRepository.replaceImageGenerationModels(id, models)
                    status = "已拉取 ${models.size} 个模型"
                }
            }.onFailure { error -> status = "拉取失败：${error.message ?: error::class.java.simpleName}" }
            pulling = false
        }
    }

    val currentId = savedId
    val entries = currentId?.let(providerRepository::entriesFor).orEmpty()
    val group = currentId?.let(providerRepository::imageGenerationGroupForProvider)
    val selectedIds = group?.memberEntryIds.orEmpty()
    val byId = entries.associateBy { it.id }
    val orderedEntries = selectedIds.mapNotNull(byId::get) + entries.filter { it.id !in selectedIds }

    SettingsScaffold(
        title = if (currentId == null) "新增生图来源" else "编辑生图来源",
        onBack = onBack,
        actions = {
            if (currentId != null) {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_trash,
                    contentDescription = "删除来源",
                    onClick = { deleteConfirm = true },
                )
            }
            NovexTopTextAction(label = "保存", onClick = { saveSource() })
        },
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it; status = null },
                label = { Text("来源名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Box {
                OutlinedButton(onClick = { protocolExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("协议：${protocol.title}", modifier = Modifier.weight(1f))
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
                onValueChange = { apiBase = it; status = null },
                label = { Text("API 地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (protocol == ImageProtocol.OPENAI) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动补全 /v1")
                        Text(
                            if (appendV1Suffix) {
                                "填写域名即可；请求时自动补上 /v1"
                            } else {
                                "完全按填写的 API 地址请求"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NovexCheckToggle(
                        checked = appendV1Suffix,
                        onCheckedChange = { appendV1Suffix = it; status = null },
                    )
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; status = null },
                label = { Text("API Key（自建或免密接口可留空）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (protocol == ImageProtocol.OPENAI) {
                Box {
                    OutlinedButton(onClick = { endpointExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("默认端点：${sourceEndpoint.title()}", modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = endpointExpanded, onDismissRequest = { endpointExpanded = false }) {
                        ImageEndpointMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.title()) },
                                onClick = { sourceEndpoint = mode; endpointExpanded = false },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveSource() }, modifier = Modifier.weight(1f)) { Text("保存来源") }
                OutlinedButton(onClick = ::pullModels, enabled = !pulling, modifier = Modifier.weight(1f)) {
                    if (pulling) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    else Icon(com.openminis.app.ui.novex.NovexIcons.Refresh, null)
                    Text("拉取模型", modifier = Modifier.padding(start = 6.dp))
                }
            }
            status?.let {
                Text(
                    it,
                    color = if (it.contains("失败") || it.startsWith("请") || it.contains("不能")) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (currentId != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("模型", style = MaterialTheme.typography.titleMedium)
                Text(
                    "勾选决定是否参与生图；已选模型排在前面，其顺序决定此来源内的降级顺序。能力未声明的模型仍可手动启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualModel,
                        onValueChange = { manualModel = it },
                        label = { Text("手动添加模型 ID") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(
                        enabled = manualModel.isNotBlank(),
                        onClick = {
                            val modelId = manualModel.trim()
                            val existingEntry = providerRepository.entriesFor(currentId).firstOrNull { it.model.id == modelId }
                            val entry = existingEntry ?: ModelEntry(
                                providerInstanceId = currentId,
                                baseModel = LLMModel(
                                    id = modelId,
                                    displayName = LLMModel.modelDisplayName(modelId),
                                    provider = label.ifBlank { "Custom" },
                                    inputModalities = listOf("text", "image"),
                                    outputModalities = listOf("image"),
                                ),
                                isCustom = true,
                            ).also(providerRepository::addEntry)
                            providerRepository.setImageGenerationModelEnabled(currentId, entry.id, true)
                            manualModel = ""
                        },
                    ) { Icon(com.openminis.app.ui.novex.NovexIcons.Add, "添加") }
                }
                orderedEntries.forEach { entry ->
                    val selected = entry.id in selectedIds
                    val selectedIndex = selectedIds.indexOf(entry.id)
                    ImageModelRow(
                        entry = entry,
                        selected = selected,
                        showEndpoint = protocol == ImageProtocol.OPENAI,
                        onSelected = { providerRepository.setImageGenerationModelEnabled(currentId, entry.id, it) },
                        onEndpoint = { providerRepository.setImageModelEndpointMode(entry.id, it) },
                        onMoveUp = if (selected && selectedIndex > 0) {
                            {
                            val order = selectedIds.toMutableList()
                            order.add(selectedIndex - 1, order.removeAt(selectedIndex))
                            providerRepository.reorderImageGenerationModels(currentId, order)
                            }
                        } else null,
                        onMoveDown = if (selected && selectedIndex in 0 until selectedIds.lastIndex) {
                            {
                            val order = selectedIds.toMutableList()
                            order.add(selectedIndex + 1, order.removeAt(selectedIndex))
                            providerRepository.reorderImageGenerationModels(currentId, order)
                            }
                        } else null,
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (deleteConfirm && currentId != null) {
        NovexDestructiveConfirmationDialog(
            title = "删除这个生图来源？",
            message = "来源、密钥和它的模型会被移除，其他来源不受影响。",
            confirming = false,
            onDismiss = { deleteConfirm = false },
            onConfirm = {
                    providerRepository.removeInstance(currentId)
                    deleteConfirm = false
                    onBack()
            },
        )
    }
}

@Composable
private fun ImageModelRow(
    entry: ModelEntry,
    selected: Boolean,
    showEndpoint: Boolean,
    onSelected: (Boolean) -> Unit,
    onEndpoint: (ImageEndpointMode?) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var endpointMenu by remember(entry.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelected(!selected) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovexCheckToggle(checked = selected, onCheckedChange = onSelected)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(entry.model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showEndpoint && selected) {
                Box {
                    TextButton(onClick = { endpointMenu = true }) {
                        Text("端点：${entry.overrides.imageEndpointMode?.title() ?: "跟随来源"}")
                    }
                    DropdownMenu(expanded = endpointMenu, onDismissRequest = { endpointMenu = false }) {
                        DropdownMenuItem(text = { Text("跟随来源") }, onClick = { onEndpoint(null); endpointMenu = false })
                        ImageEndpointMode.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.title()) }, onClick = { onEndpoint(mode); endpointMenu = false })
                        }
                    }
                }
            }
        }
        IconButton(enabled = onMoveUp != null, onClick = { onMoveUp?.invoke() }) {
            Icon(com.openminis.app.ui.novex.NovexIcons.ArrowUpward, "上移")
        }
        IconButton(enabled = onMoveDown != null, onClick = { onMoveDown?.invoke() }) {
            Icon(com.openminis.app.ui.novex.NovexIcons.ArrowDownward, "下移")
        }
    }
}

private fun ImageEndpointMode.title(): String = when (this) {
    ImageEndpointMode.auto -> "自动检测"
    ImageEndpointMode.imagesGenerations -> "/images/generations"
    ImageEndpointMode.chatCompletions -> "/chat/completions"
}
