package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.ModalBottomSheet
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterCustomAttribute
import com.openminis.app.data.character.CharacterRelationship
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.requireCharacter
import com.openminis.app.novex.domain.requireNativeCard
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexContentSection
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexContentModuleList
import com.openminis.app.ui.novex.NovexSummaryRow
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.rememberNovexWorkspace
import kotlinx.coroutines.launch

private data class CharacterDetailData(
    val aggregate: CharacterAggregate,
    val worlds: Map<String, List<WorldEntity>>,
    val media: Map<String, Map<MediaAssetSlot, MediaAssetEntity>>,
)

internal data class CharacterPageData(
    val rootName: String,
    val version: CharacterVersionEntity,
    val profile: CharacterVersionProfile,
    val worlds: List<WorldEntity>,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
    val modules: List<ContentModuleEntity>,
    val moduleImages: Map<String, MediaAssetEntity>,
    val moduleItemImages: Map<String, Map<String, MediaAssetEntity>>,
    val variantCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogCharacterDetailScreen(
    characterId: String,
    onBack: () -> Unit,
    onEditVersion: (String) -> Unit,
    onHelpCreate: (String) -> Unit,
    onCreateVariant: () -> Unit,
    onDuplicated: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<CharacterDetailData?>(null) }
    var modulesByVersion by remember { mutableStateOf<Map<String, List<ContentModuleEntity>>>(emptyMap()) }
    var moduleImagesByVersion by remember {
        mutableStateOf<Map<String, Map<String, MediaAssetEntity>>>(emptyMap())
    }
    var moduleItemImagesByVersion by remember {
        mutableStateOf<Map<String, Map<String, Map<String, MediaAssetEntity>>>>(emptyMap())
    }
    var missing by remember { mutableStateOf(false) }
    var selectedVersionId by rememberSaveable(characterId) { mutableStateOf<String?>(null) }
    var confirmDeleteRoot by remember { mutableStateOf(false) }
    var confirmDeleteVariant by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var confirmSharedEdit by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(characterId, refresh) {
        val snapshot = novex.character(characterId)
        if (snapshot == null) {
            missing = true
            data = null
        } else {
            val aggregate = snapshot.character
            missing = false
            selectedVersionId = selectedVersionId?.takeIf { id -> aggregate.allVersions.any { it.id == id } }
                ?: aggregate.original.id
            data = CharacterDetailData(
                aggregate = aggregate,
                worlds = snapshot.worldsByVersion,
                media = snapshot.mediaByVersion,
            )
            modulesByVersion = snapshot.modulesByVersion
            moduleImagesByVersion = snapshot.modulesByVersion.mapValues { (_, modules) ->
                modules.mapNotNull { module -> snapshot.moduleImages[module.id]?.let { module.id to it } }.toMap()
            }
            moduleItemImagesByVersion = snapshot.modulesByVersion.mapValues { (_, modules) ->
                modules.mapNotNull { module ->
                    snapshot.moduleItemImages[module.id]?.let { module.id to it }
                }.toMap()
            }
        }
    }
    val current = data
    val selected = current?.aggregate?.allVersions?.firstOrNull { it.id == selectedVersionId }
    val profile = selected?.let { CharacterVersionProfile.fromJson(it.profileJson, current.aggregate.character.name) }
    var versionSheet by remember { mutableStateOf(false) }
    val page = if (current != null && selected != null && profile != null) CharacterPageData(
        rootName = current.aggregate.character.name,
        version = selected,
        profile = profile,
        worlds = current.worlds[selected.id].orEmpty(),
        media = current.media[selected.id].orEmpty(),
        modules = modulesByVersion[selected.id].orEmpty(),
        moduleImages = moduleImagesByVersion[selected.id].orEmpty(),
        moduleItemImages = moduleItemImagesByVersion[selected.id].orEmpty(),
        variantCount = current.aggregate.variants.size,
    ) else null
    NovexDetailScaffold(
        title = current?.aggregate?.character?.name ?: "角色",
        onBack = onBack,
        actions = {
            if (page != null) {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_sparkle,
                    contentDescription = "帮我创作",
                    label = "帮我创作",
                    onClick = { onHelpCreate(page.version.id) },
                )
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_pencil_simple,
                    contentDescription = "编辑角色",
                    label = "编辑",
                    onClick = {
                        if (page.worlds.isEmpty()) onEditVersion(page.version.id)
                        else confirmSharedEdit = page.version
                    },
                )
            }
        },
    ) {
        when {
            missing -> Text("角色不存在或已删除", modifier = Modifier.padding(24.dp))
            page == null -> Box(
                Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                CharacterPrimaryContent(
                    data = page,
                    onChooseVersion = { versionSheet = true },
                    onOpenModule = onOpenModule,
                )
                CharacterManagementActions(
                    isVariant = page.version.kind == CharacterVersionKind.VARIANT,
                    onCreateVariant = onCreateVariant,
                    onExport = {
                        scope.launch {
                            runCatching {
                                novex.apply(NovexCommand.ExportNativeCharacter(characterId)).requireNativeCard()
                            }
                                .onSuccess { shareNovexCardPackage(context, it) }
                                .onFailure { error = it.message }
                        }
                    },
                    onDuplicate = {
                        scope.launch {
                            runCatching {
                                novex.apply(NovexCommand.DuplicateCharacter(characterId)).requireCharacter()
                            }
                                .onSuccess { onDuplicated(it.character.id) }
                                .onFailure { error = it.message }
                        }
                    },
                    onDelete = {
                        if (page.version.kind == CharacterVersionKind.VARIANT) confirmDeleteVariant = page.version
                        else confirmDeleteRoot = true
                    },
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (versionSheet && current != null && selected != null) ModalBottomSheet(
        onDismissRequest = { versionSheet = false },
        containerColor = NovexColors.Background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).width(34.dp).height(4.dp)
                    .clip(CircleShape).background(NovexColors.Divider),
            )
        },
    ) {
        Text(
            "选择角色版本",
            color = NovexColors.Text,
            style = NovexType.SectionTitle,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        current.aggregate.allVersions.forEach { version ->
            CharacterVersionChoice(
                version = version,
                selected = version.id == selected.id,
                worlds = current.worlds[version.id].orEmpty(),
                avatar = current.media[version.id]?.get(MediaAssetSlot.CHARACTER_AVATAR),
                onClick = {
                    selectedVersionId = version.id
                    versionSheet = false
                },
            )
        }
        NovexTextActionRow(
            label = "创建分身",
            onClick = {
                versionSheet = false
                onCreateVariant()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
    if (confirmDeleteRoot && current != null) AlertDialog(
        onDismissRequest = { confirmDeleteRoot = false },
        title = { Text("删除整个角色？") },
        text = { Text("本体、全部分身及其世界关联会一并删除；已有对话中的快照仍保留。") },
        confirmButton = {
            Button(onClick = {
                confirmDeleteRoot = false
                scope.launch { novex.apply(NovexCommand.DeleteCharacter(characterId)); onBack() }
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = { confirmDeleteRoot = false }) { Text("取消") } },
    )
    confirmDeleteVariant?.let { version ->
        AlertDialog(
            onDismissRequest = { confirmDeleteVariant = null },
            title = { Text("删除${version.label}？") },
            text = { Text("这个分身会从所有世界移除，本体和其他分身不受影响。") },
            confirmButton = {
                Button(onClick = {
                    confirmDeleteVariant = null
                    scope.launch {
                        novex.apply(NovexCommand.DeleteVariant(version.id))
                        selectedVersionId = null
                        refresh++
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteVariant = null }) { Text("取消") } },
        )
    }
    confirmSharedEdit?.let { version ->
        val affectedWorlds = current?.worlds?.get(version.id).orEmpty()
        AlertDialog(
            onDismissRequest = { confirmSharedEdit = null },
            title = { Text("编辑共享版本？") },
            text = {
                Text("保存后的修改会同步显示在：${affectedWorlds.joinToString("、") { it.name }}。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSharedEdit = null
                    onEditVersion(version.id)
                }) { Text("继续编辑") }
            },
            dismissButton = { TextButton(onClick = { confirmSharedEdit = null }) { Text("取消") } },
        )
    }
    error?.let { CharacterErrorDialog(it) { error = null } }
}

@Composable
internal fun CharacterPrimaryContent(
    data: CharacterPageData,
    onChooseVersion: (() -> Unit)?,
    onOpenModule: ((String) -> Unit)?,
    mediaModels: Map<MediaAssetSlot, Any?> = emptyMap(),
) {
    CharacterHero(data, onChooseVersion, mediaModels)
    Column(Modifier.fillMaxWidth()) {
        characterOverviewRows(data.profile).forEach { row ->
            NovexSummaryRow(row.title, row.summary)
        }
        if (data.worlds.isNotEmpty()) {
            NovexSummaryRow(
                title = "关联世界",
                summary = data.worlds.joinToString(" · ") { it.name },
            )
        }
    }
    NovexContentModuleList(
        modules = data.modules,
        moduleImages = data.moduleImages.mapValues { it.value.managedPath.existingMediaFile() },
        moduleItemImages = data.moduleItemImages.mapValues { (_, images) ->
            images.mapValues { it.value.managedPath.existingMediaFile() }
        },
        onOpenModule = onOpenModule,
        owner = NovexContentAddress.characterVersion(data.version.id),
    )
}

@Composable
private fun CharacterHero(
    data: CharacterPageData,
    onChooseVersion: (() -> Unit)?,
    mediaModels: Map<MediaAssetSlot, Any?>,
) {
    val name = data.profile.name.ifBlank { data.rootName }
    val avatarModel = mediaModels[MediaAssetSlot.CHARACTER_AVATAR]
        ?: data.media[MediaAssetSlot.CHARACTER_AVATAR]?.managedPath.existingMediaFile()
    val backgroundModel = mediaModels[MediaAssetSlot.CHARACTER_PAGE_BACKGROUND]
        ?: data.media[MediaAssetSlot.CHARACTER_PAGE_BACKGROUND]?.managedPath.existingMediaFile()
    val representativeModel = backgroundModel ?: avatarModel
    Column(
        Modifier.fillMaxWidth().background(NovexColors.Surface)
            .padding(horizontal = NovexDimensions.PageHorizontal),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            NovexArtwork(
                kind = NovexArtworkKind.CHARACTER,
                seed = data.version.id,
                imageModel = representativeModel,
                contentDescription = "$name 代表图",
                modifier = Modifier.width(276.dp).height(216.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = NovexColors.Text,
                    fontSize = com.openminis.app.ui.novex.novexScaledSp(27),
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    Modifier.padding(top = 5.dp)
                        .then(if (onChooseVersion == null) Modifier else Modifier.clickable(onClick = onChooseVersion)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        characterVersionSelectorLabel(data.version.kind, data.version.label, data.variantCount),
                        color = NovexColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (onChooseVersion != null) Icon(
                        painterResource(R.drawable.ic_phosphor_caret_right),
                        contentDescription = "选择角色版本",
                        tint = NovexColors.SecondaryText,
                        modifier = Modifier.padding(start = 3.dp).size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CharacterVersionChoice(
    version: CharacterVersionEntity,
    selected: Boolean,
    worlds: List<WorldEntity>,
    avatar: MediaAssetEntity?,
    onClick: () -> Unit,
) {
    val profile = CharacterVersionProfile.fromJson(version.profileJson, version.label)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovexArtwork(
            kind = NovexArtworkKind.CHARACTER,
            seed = version.id,
            imageModel = avatar?.managedPath.existingMediaFile(),
            contentDescription = "${profile.name}头像",
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (version.kind == CharacterVersionKind.ORIGINAL) "本体 · ${profile.name}" else version.label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                if (worlds.isEmpty()) "尚未加入世界" else worlds.joinToString(" · ") { it.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(22.dp).border(
                width = 1.5.dp,
                color = if (selected) NovexColors.Text else NovexColors.Divider,
                shape = CircleShape,
            ),
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(NovexColors.Text))
        }
    }
}

@Composable
private fun CharacterManagementActions(
    isVariant: Boolean,
    onCreateVariant: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    NovexContentSection("管理") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onCreateVariant) { Text("创建分身") }
            TextButton(onClick = onExport) { Text("导出") }
            TextButton(onClick = onDuplicate) { Text("复制") }
            TextButton(onClick = onDelete) { Text(if (isVariant) "删除分身" else "删除角色") }
        }
    }
}

@Composable
private fun CharacterErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作失败") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

internal fun parseCharacterAttributes(raw: String): List<CharacterCustomAttribute> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split(Regex("[：:]"), limit = 2).map(String::trim)
    if (parts.firstOrNull().isNullOrBlank()) null else CharacterCustomAttribute(parts[0], parts.getOrElse(1) { "" })
}.toList()

internal fun parseCharacterRelationships(raw: String): List<CharacterRelationship> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split('｜').map(String::trim)
    if (parts.firstOrNull().isNullOrBlank()) null else CharacterRelationship(
        characterName = parts[0],
        relationship = parts.getOrElse(1) { "" },
        description = parts.drop(2).joinToString("｜"),
    )
}.toList()
