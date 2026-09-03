package com.openminis.app.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.OutlinedButton
import com.openminis.app.ui.novex.OutlinedTextField
import com.openminis.app.ui.novex.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.openminis.app.ui.novex.NovexCheckToggle
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.data.MAX_CONVERSATION_PROMPT_CHARS
import com.openminis.app.data.MAX_IMAGE_STYLE_PROMPT_CHARS
import com.openminis.app.data.character.CharacterCardStore
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import kotlinx.coroutines.launch

private data class ImageStylePreset(val name: String, val prompt: String)

private val imageStylePresets = listOf(
    ImageStylePreset("写实摄影", "写实摄影风格，自然光影，真实材质与细节，避免插画感。"),
    ImageStylePreset("动漫插画", "高质量动漫插画风格，清晰线条，细腻上色，角色一致。"),
    ImageStylePreset("电影感", "电影画面风格，叙事性构图，戏剧化光影，统一电影调色。"),
    ImageStylePreset("水彩", "透明水彩画风格，柔和晕染，纸张纹理，轻盈自然。"),
    ImageStylePreset("油画", "古典油画风格，厚重笔触，丰富层次，柔和明暗过渡。"),
    ImageStylePreset("像素艺术", "精细像素艺术风格，统一像素密度与有限色板。"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    val ready by viewModel.conversationSettingsReady.collectAsState()
    val seed = remember(sessionId) { viewModel.conversationSettingsSnapshot() }
    var initial by remember(sessionId) { mutableStateOf(seed) }
    var hydrated by remember(sessionId) { mutableStateOf(ready) }
    var prompt by remember(sessionId) { mutableStateOf(seed.conversationPrompt) }
    var imageStyle by remember(sessionId) { mutableStateOf(seed.imageStylePrompt) }
    var roleDisplay by remember(sessionId) { mutableStateOf(seed.rolePresentationEnabled) }
    var assistantName by remember(sessionId) { mutableStateOf(seed.assistantDisplayName) }
    var assistantAvatar by remember(sessionId) { mutableStateOf(seed.assistantAvatarPath) }
    var playerName by remember(sessionId) { mutableStateOf(seed.playerDisplayName) }
    var playerAvatar by remember(sessionId) { mutableStateOf(seed.playerAvatarPath) }
    var saving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun current() = ConversationSettingsSnapshot(
        conversationPrompt = prompt,
        imageStylePrompt = imageStyle,
        rolePresentationEnabled = roleDisplay,
        assistantDisplayName = assistantName,
        assistantAvatarPath = assistantAvatar,
        playerDisplayName = playerName,
        playerAvatarPath = playerAvatar,
    )
    LaunchedEffect(ready) {
        if (ready && !hydrated) {
            val loaded = viewModel.conversationSettingsSnapshot()
            initial = loaded
            prompt = loaded.conversationPrompt
            imageStyle = loaded.imageStylePrompt
            roleDisplay = loaded.rolePresentationEnabled
            assistantName = loaded.assistantDisplayName
            assistantAvatar = loaded.assistantAvatarPath
            playerName = loaded.playerDisplayName
            playerAvatar = loaded.playerAvatarPath
            hydrated = true
        }
    }

    val changed = hydrated && current() != initial
    fun leave() {
        if (changed) showDiscardDialog = true else onBack()
    }
    fun save() {
        if (saving) return
        saving = true
        viewModel.saveConversationSettings(current()) { result ->
            saving = false
            result.onSuccess { onBack() }
                .onFailure { error ->
                    scope.launch { snackbar.showSnackbar("保存失败：${error.message ?: error::class.java.simpleName}") }
                }
        }
    }

    val assistantPicker = conversationImagePicker("conversation-assistant-avatar") { assistantAvatar = it }
    val playerPicker = conversationImagePicker("conversation-player-avatar") { playerAvatar = it }

    BackHandler(onBack = ::leave)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("对话设置") },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = hydrated && !saving) {
                        Text(if (saving) "保存中…" else "保存")
                    }
                },
            )
        },
    ) { padding ->
        if (!hydrated) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SettingsTitle("当前对话提示词")
            Text(
                "这里是当前对话自己的提示词副本。你可以直接改写全文；只想追加规则时，继续写在末尾即可。修改不会影响其他对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(MAX_CONVERSATION_PROMPT_CHARS) },
                label = { Text("提示词") },
                minLines = 10,
                supportingText = { Text("${prompt.length} / $MAX_CONVERSATION_PROMPT_CHARS") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { prompt = viewModel.sourceConversationPrompt() }) {
                    Text("恢复来源提示词")
                }
            }

            SettingsTitle("图片生成风格")
            Text(
                "保存后，这段风格要求会自动加入本对话每一次生成图片和参考图编辑请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                imageStylePresets.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            FilterChip(
                                selected = imageStyle == preset.prompt,
                                onClick = { imageStyle = preset.prompt },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = imageStyle,
                onValueChange = { imageStyle = it.take(MAX_IMAGE_STYLE_PROMPT_CHARS) },
                label = { Text("固定风格或预设词（可选）") },
                minLines = 4,
                supportingText = { Text("${imageStyle.length} / $MAX_IMAGE_STYLE_PROMPT_CHARS") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { imageStyle = "" }, enabled = imageStyle.isNotEmpty()) {
                    Text("清除固定风格")
                }
            }

            SettingsTitle("角色显示模式")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { roleDisplay = !roleDisplay }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示双方头像和对话气泡", fontWeight = FontWeight.Medium)
                    Text(
                        "只改变当前对话的显示方式，不会自动添加角色设定或世界规则。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NovexCheckToggle(checked = roleDisplay, onCheckedChange = { roleDisplay = it })
            }
            if (roleDisplay) {
                IdentityEditor(
                    title = "角色卡 / 助手身份",
                    name = assistantName,
                    onNameChange = { assistantName = it.take(80) },
                    avatarPath = assistantAvatar,
                    onPickAvatar = assistantPicker,
                    onClearAvatar = { assistantAvatar = null },
                )
                Spacer(Modifier.height(12.dp))
                IdentityEditor(
                    title = "玩家身份",
                    name = playerName,
                    onNameChange = { playerName = it.take(80) },
                    avatarPath = playerAvatar,
                    onPickAvatar = playerPicker,
                    onClearAvatar = { playerAvatar = null },
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "保存中…" else "保存当前对话设置")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前对话设置尚未保存。") },
            confirmButton = {
                TextButton(onClick = onBack) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun SettingsTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun IdentityEditor(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    avatarPath: String?,
    onPickAvatar: () -> Unit,
    onClearAvatar: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val avatar = avatarPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = "$title 头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null)
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    OutlinedButton(onClick = onPickAvatar) { Text("选择头像") }
                    if (avatarPath != null) {
                        TextButton(onClick = onClearAvatar) { Text("移除头像") }
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("显示名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun conversationImagePicker(kind: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            runCatching { CharacterCardStore.copyMedia(context, uri, kind) }
                .onSuccess(onPicked)
        }
    }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
