package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.auth.OAuthManager
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.ui.ModalBottomSheet
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import sh.calvin.reorderable.ReorderableColumn

@Composable
fun ProviderListScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { providerRepository.ensureImageGenerationMigration() }
    }

    val instances = config.instances.filterNot {
        it.id in config.imageGenerationProviderInstanceIds ||
            providerRepository.isOpenCodeFreeInstance(it.id)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { importProviderFile(context, it, providerRepository) } }

    SettingsScaffold(
        title = stringResource(R.string.provider_list_providers),
        onBack = onBack,
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(NovexIcons.Add, stringResource(R.string.provider_list_add_provider))
            }
        },
    ) {
        if (instances.isEmpty()) {
            EmptyProviderState()
        } else {
            instances.groupBy { it.providerType }.forEach { (type, members) ->
                ProviderTypeSection(
                    members = members,
                    repository = providerRepository,
                    context = context,
                    onOpen = onProviderClick,
                )
            }
        }
        Spacer(Modifier.height(80.dp))
    }

    if (menuOpen) {
        ProviderAddMenu(
            onAdd = { menuOpen = false; onAddProvider() },
            onImport = {
                menuOpen = false
                importLauncher.launch(
                    arrayOf("application/json", "application/zip", "application/x-zip-compressed"),
                )
            },
            onDismiss = { menuOpen = false },
        )
    }
}

// ── 导入 ────────────────────────────────────────────────────────────────────

private fun importProviderFile(context: Context, uri: Uri, repo: ProviderRepository) {
    fun toast(res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    runCatching {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = ProviderImportZip.queryDisplayName(context, uri).orEmpty()
        val isZip = mime == "application/zip" || mime == "application/x-zip-compressed" ||
            name.lowercase().endsWith(".zip")

        if (isZip) {
            ProviderImportZip.importFromZip(
                context = context,
                uri = uri,
                onImportSingle = { repo.importInstanceJSON(it) },
                onExtractFailed = { toast(R.string.import_zip_extract_failed) },
                onNoSupported = { toast(R.string.import_zip_no_supported) },
                onSummary = { ok, total ->
                    toast(context.getString(R.string.import_zip_summary, ok, total))
                },
            )
        } else {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            val label = json?.let(repo::importInstanceJSON)
            toast(
                if (label != null) "Imported provider \"$label\"" else "Invalid provider configuration file",
            )
        }
    }.onFailure { toast("Failed to read file") }
}

// ── 空态与菜单 ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyProviderState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = NovexDimensions.PageHorizontal)
            .clip(RoundedCornerShape(14.dp))
            .background(NovexColors.Surface)
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            NovexIcons.VpnKey,
            contentDescription = null,
            tint = NovexColors.TertiaryText,
            modifier = Modifier.size(36.dp),
        )
        Text(
            stringResource(R.string.provider_list_no_providers_configured),
            style = NovexType.Body,
            color = NovexColors.SecondaryText,
        )
        Text(
            stringResource(R.string.provider_list_add_a_provider_to_get_started),
            style = NovexType.Metadata,
            color = NovexColors.TertiaryText,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderAddMenu(
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 32.dp)) {
            MenuLine(NovexIcons.Add, stringResource(R.string.provider_list_add_provider), onAdd)
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = NovexColors.Divider)
            MenuLine(NovexIcons.FileDownload, stringResource(R.string.provider_list_import_provider), onImport)
        }
    }
}

@Composable
private fun MenuLine(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = NovexType.ItemTitle)
    }
}

// ── 分组列表 ─────────────────────────────────────────────────────────────────

@Composable
private fun ProviderTypeSection(
    members: List<ProviderInstance>,
    repository: ProviderRepository,
    context: Context,
    onOpen: (String) -> Unit,
) {
    SettingsSection(header = members.first().providerType.displayName) {
        // 长按拖拽排序：拖动中 localOrder 跟随手指，落定时只提交本段 id 顺序
        // （reorderInstances 保留未提及实例的相对位置，其它段不受影响）。
        var localOrder by remember(members.map { it.id }) { mutableStateOf(members) }
        ReorderableColumn(
            list = localOrder,
            onSettle = { from, to ->
                localOrder = localOrder.toMutableList().apply { add(to, removeAt(from)) }
                repository.reorderInstances(localOrder.map { it.id })
            },
        ) { index, instance, isDragging ->
            key(instance.id) {
                val modelCount = repository.visibleEntries(instance.id).size
                val apiKey = repository.loadApiKey(instance.id)
                // OAuth 实例的「已配置」看凭据（手动 token 或已登录 OAuth），
                // 不只看 apiKey 字段；免密兼容端点视作天然已配置。
                val isConfigured = when {
                    instance.credentialType == ProviderCredential.oauth ->
                        OAuthManager.forInstance(context, instance)?.isAuthenticated() == true
                    else -> !apiKey.isNullOrBlank() || instance.allowsEmptyAPIKey
                }
                val lift by animateDpAsState(
                    if (isDragging) 4.dp else 0.dp,
                    label = "provider_drag_lift",
                )
                Surface(
                    shadowElevation = lift,
                    color = Color.Transparent,
                    modifier = Modifier.longPressDraggableHandle(),
                ) {
                    ProviderInstanceLine(
                        instance = instance,
                        modelCount = modelCount,
                        apiKey = apiKey,
                        configured = isConfigured,
                        onClick = { onOpen(instance.id) },
                    )
                }
                if (index < localOrder.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 38.dp, end = 14.dp)
                            .height(NovexDimensions.Hairline)
                            .background(NovexColors.Divider),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderInstanceLine(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    configured: Boolean,
    onClick: () -> Unit,
) {
    val active = configured && instance.isEnabled

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (active) Color(0xFF34C759) else NovexColors.Divider,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(instance.label, style = NovexType.ItemTitle, fontWeight = FontWeight.Medium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.provider_list_api_key),
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                )
                Text("·", style = NovexType.Metadata, color = NovexColors.TertiaryText)
                Text(
                    when {
                        !apiKey.isNullOrBlank() -> maskApiKey(apiKey)
                        instance.allowsEmptyAPIKey -> stringResource(R.string.provider_no_key_required)
                        else -> "No API key"
                    },
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                    maxLines = 1,
                )
            }
            if (modelCount > 0) {
                Text(
                    stringResource(R.string.provider_list_models_count, modelCount),
                    style = NovexType.Metadata,
                    color = NovexColors.TertiaryText,
                )
            }
        }

        if (!instance.isEnabled) {
            Text(
                stringResource(R.string.provider_list_disabled),
                style = NovexType.Metadata.copy(fontWeight = FontWeight.Medium),
                color = NovexColors.SecondaryText,
                modifier = Modifier
                    .background(NovexColors.Surface, RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            NovexIcons.KeyboardArrowRight,
            contentDescription = null,
            tint = NovexColors.TertiaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun maskApiKey(key: String): String =
    if (key.length <= 8) "****" else key.take(6) + "..." + key.takeLast(4)
