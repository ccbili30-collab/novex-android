package com.openminis.app.ui.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexType
import novex.android.ui.OutlinedTextField
import novex.android.ui.Scaffold
import novex.android.ui.TopAppBar

private const val MAX_PICKS = 3

/**
 * 引导第二步：从已配置的 Provider 里挑 1–3 个模型建成「Default Models」组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingModelSelectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }

    // 进场时刷新所有启用实例的模型目录，展示的是实时清单而非占位种子。
    LaunchedEffect(Unit) {
        config.instances.filter { it.isEnabled }.forEach { instance ->
            launch(Dispatchers.IO) { providerRepository.refreshModels(instance) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_select_models_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            novex.android.ui.NovexIcons.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) {
                        Text(stringResource(R.string.common_skip))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = NovexDimensions.PageHorizontal),
        ) {
            Text(
                stringResource(R.string.onboarding_select_models_subtitle),
                style = NovexType.Body,
                color = NovexColors.SecondaryText,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.onboarding_filter_models)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(50),
            )
            Spacer(Modifier.height(8.dp))

            val sections = pickableSections(config, query)
            if (!hasPickableEntries(config)) {
                LoadingState(Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((instanceLabel, entries) in sections) {
                        item(key = "hdr_$instanceLabel") {
                            Text(
                                instanceLabel,
                                style = NovexType.SectionTitle,
                                color = NovexColors.Primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            PickableModelRow(
                                entry = entry,
                                selectionOrder = selected.indexOf(entry.id),
                                enabled = selected.size < MAX_PICKS || entry.id in selected,
                                onToggle = {
                                    if (entry.id in selected) selected.remove(entry.id)
                                    else if (selected.size < MAX_PICKS) selected.add(entry.id)
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            MinisButton(
                onClick = {
                    if (selected.isNotEmpty()) {
                        val group = ModelGroup(name = "Default Models")
                        group.memberEntryIds.addAll(selected)
                        providerRepository.addGroup(group)
                        if (config.defaultPrimaryGroupId == null) {
                            providerRepository.defaultPrimaryGroupId = group.id
                        }
                    }
                    onBack()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.onboarding_done_count, selected.size))
            }
        }
    }
}

/** 是否有任何可挑条目（不过滤搜索词）——决定列表区是 spinner 还是列表。 */
private fun hasPickableEntries(config: ProviderConfig): Boolean {
    val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    return config.modelEntries.any { it.providerInstanceId in enabledIds && !it.isHidden }
}

private fun pickableSections(
    config: ProviderConfig,
    query: String,
): List<Pair<String, List<ModelEntry>>> {
    val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val q = query.lowercase()
    return config.modelEntries
        .filter { it.providerInstanceId in enabledIds && !it.isHidden }
        .filter {
            q.isBlank() ||
                it.model.displayName.lowercase().contains(q) ||
                it.model.id.lowercase().contains(q)
        }
        .groupBy { it.providerInstanceId }
        .map { (instanceId, entries) ->
            val label = config.instances.find { it.id == instanceId }?.label ?: instanceId
            label to entries
        }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_loading_models),
                style = NovexType.Metadata,
                color = NovexColors.SecondaryText,
            )
        }
    }
}

@Composable
private fun PickableModelRow(
    entry: ModelEntry,
    selectionOrder: Int,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val picked = selectionOrder >= 0
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (picked) NovexColors.Primary else NovexColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) {
                Text(
                    "${selectionOrder + 1}",
                    color = NovexColors.Background,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.model.displayName, style = NovexType.ItemTitle)
            Text(
                entry.model.id,
                style = NovexType.Metadata,
                color = NovexColors.SecondaryText,
            )
        }
    }
}
