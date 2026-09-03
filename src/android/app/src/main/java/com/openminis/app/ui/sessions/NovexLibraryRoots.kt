package com.openminis.app.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexSearchField
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.rememberNovexWorkspace
import com.openminis.app.ui.settings.existingMediaFile
import com.openminis.app.ui.settings.rememberNovexNativeCardImporter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val NovexRootColors = NovexColors

private data class WorldRootRow(
    val world: WorldEntity,
    val imagePath: String?,
    val characterCount: Int,
    val moduleCount: Int,
)

private data class CharacterRootRow(
    val character: CharacterEntity,
    val profile: CharacterVersionProfile,
    val imagePath: String?,
    val variantCount: Int,
)

@Composable
internal fun NovexWorldLibraryRoot(
    onOpenWorld: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val novex = rememberNovexWorkspace()
    val context = LocalContext.current
    val orderStore = remember(context) { NovexManualOrderStore(context) }
    var rows by remember { mutableStateOf<List<WorldRootRow>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchState = rememberNovexLibrarySearchState()
    val query by searchState.applied.collectAsState()
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = (from.key as? String)?.removePrefix("world:")
            ?: return@rememberReorderableLazyListState
        val toId = (to.key as? String)?.removePrefix("world:")
            ?: return@rememberReorderableLazyListState
        val fromIndex = rows.indexOfFirst { it.world.id == fromId }
        val toIndex = rows.indexOfFirst { it.world.id == toId }
        if (fromIndex !in rows.indices || toIndex !in rows.indices) return@rememberReorderableLazyListState
        rows = rows.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        orderStore.write(NovexManualOrderKind.WORLDS, rows.map { it.world.id })
    }

    BackHandler(enabled = searching) {
        searching = false
        searchState.clear()
    }

    val importer = rememberNovexNativeCardImporter(NovexCardKind.WORLD) { importedId ->
        refresh++
        onOpenWorld(importedId)
    }
    val resumeRevision = rememberNovexCatalogResumeRevision()

    LaunchedEffect(refresh, resumeRevision) {
        val loadedRows = novex.worlds().map { card ->
            WorldRootRow(
                world = card.world,
                imagePath = card.image?.managedPath,
                characterCount = card.characterCount,
                moduleCount = card.moduleCount,
            )
        }
        val byId = loadedRows.associateBy { it.world.id }
        rows = mergeNovexManualOrder(
            sourceIds = loadedRows.map { it.world.id },
            savedIds = orderStore.read(NovexManualOrderKind.WORLDS),
        ).mapNotNull(byId::get)
        loaded = true
    }
    val filtered = remember(rows, query) {
        rows.filter { row ->
            query.isBlank() || row.world.name.contains(query, ignoreCase = true) ||
                row.world.overview.contains(query, ignoreCase = true)
        }
    }

    NovexLibraryFrame(
        space = NovexRootSpace.WORLDS,
        searching = searching,
        searchState = searchState,
        searchDescription = "搜索世界",
        onSearchToggle = {
            searching = !searching
            if (!searching) searchState.clear()
        },
        onOpenSettings = onOpenSettings,
        createItems = listOf(
            NovexCreateMenuItem("新建世界", onCreateWorld),
            NovexCreateMenuItem("导入世界卡", importer.launch),
        ),
    ) {
        when {
            !loaded || importer.importing -> NovexLoading()
            filtered.isEmpty() && query.isNotBlank() -> NovexEmptyMessage("没有找到匹配的世界")
            rows.isEmpty() -> NovexEmptyWorldLibrary(onCreateWorld, importer.launch)
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "world:${it.world.id}" }) { row ->
                    ReorderableItem(reorderState, key = "world:${row.world.id}") { _ ->
                        NovexWorldCard(
                            row = row,
                            onClick = { onOpenWorld(row.world.id) },
                            modifier = Modifier.longPressDraggableHandle(),
                        )
                    }
                }
                item(key = "create_world") {
                    NovexCreateRow(label = "新建世界", onClick = onCreateWorld)
                }
                item(key = "import_world") {
                    NovexImportRow(label = "导入世界卡", onClick = importer.launch)
                }
            }
        }
    }
}

@Composable
internal fun NovexCharacterLibraryRoot(
    onOpenCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val novex = rememberNovexWorkspace()
    val context = LocalContext.current
    val orderStore = remember(context) { NovexManualOrderStore(context) }
    var rows by remember { mutableStateOf<List<CharacterRootRow>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchState = rememberNovexLibrarySearchState()
    val query by searchState.applied.collectAsState()
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = (from.key as? String)?.removePrefix("character:")
            ?: return@rememberReorderableLazyListState
        val toId = (to.key as? String)?.removePrefix("character:")
            ?: return@rememberReorderableLazyListState
        val fromIndex = rows.indexOfFirst { it.character.id == fromId }
        val toIndex = rows.indexOfFirst { it.character.id == toId }
        if (fromIndex !in rows.indices || toIndex !in rows.indices) return@rememberReorderableLazyListState
        rows = rows.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        orderStore.write(NovexManualOrderKind.CHARACTERS, rows.map { it.character.id })
    }

    BackHandler(enabled = searching) {
        searching = false
        searchState.clear()
    }

    val importer = rememberNovexNativeCardImporter(NovexCardKind.CHARACTER) { importedId ->
        refresh++
        onOpenCharacter(importedId)
    }
    val resumeRevision = rememberNovexCatalogResumeRevision()

    LaunchedEffect(refresh, resumeRevision) {
        val loadedRows = novex.characters().map { card ->
            val aggregate = card.character
            val character = aggregate.character
            val profile = CharacterVersionProfile.fromJson(aggregate.original.profileJson, character.name)
            CharacterRootRow(character, profile, card.avatar?.managedPath, aggregate.variants.size)
        }
        val byId = loadedRows.associateBy { it.character.id }
        rows = mergeNovexManualOrder(
            sourceIds = loadedRows.map { it.character.id },
            savedIds = orderStore.read(NovexManualOrderKind.CHARACTERS),
        ).mapNotNull(byId::get)
        loaded = true
    }
    val filtered = remember(rows, query) {
        rows.filter { row ->
            query.isBlank() || row.character.name.contains(query, ignoreCase = true) ||
                row.profile.summary.contains(query, ignoreCase = true)
        }
    }

    NovexLibraryFrame(
        space = NovexRootSpace.CHARACTERS,
        searching = searching,
        searchState = searchState,
        searchDescription = "搜索角色",
        onSearchToggle = {
            searching = !searching
            if (!searching) searchState.clear()
        },
        onOpenSettings = onOpenSettings,
        createItems = listOf(
            NovexCreateMenuItem("新建角色", onCreateCharacter),
            NovexCreateMenuItem("导入角色卡", importer.launch),
        ),
    ) {
        when {
            !loaded || importer.importing -> NovexLoading()
            filtered.isEmpty() && query.isNotBlank() -> NovexEmptyMessage("没有找到匹配的角色")
            rows.isEmpty() -> NovexEmptyCharacterLibrary(onCreateCharacter, importer.launch)
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "character:${it.character.id}" }) { row ->
                    ReorderableItem(reorderState, key = "character:${row.character.id}") { _ ->
                        NovexCharacterRow(
                            row = row,
                            onClick = { onOpenCharacter(row.character.id) },
                            modifier = Modifier.longPressDraggableHandle(),
                        )
                    }
                }
                item(key = "create_character") {
                    NovexCreateRow(label = "新建角色", onClick = onCreateCharacter)
                }
                item(key = "import_character") {
                    NovexImportRow(label = "导入角色卡", onClick = importer.launch)
                }
            }
        }
    }
}

@Composable
private fun NovexLibraryFrame(
    space: NovexRootSpace,
    searching: Boolean,
    searchState: NovexLibrarySearchState,
    searchDescription: String,
    onSearchToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    createItems: List<NovexCreateMenuItem>,
    content: @Composable () -> Unit,
) {
    val headerHost = LocalNovexRootHeaderHost.current
    RegisterNovexRootHeader(
        space,
        NovexRootHeaderConfig(
            searching = searching,
            searchDescription = searchDescription,
            onSettings = onOpenSettings,
            onSearchToggle = onSearchToggle,
            createItems = createItems,
        ),
    )
    Column(
        (if (headerHost == null) Modifier.statusBarsPadding() else Modifier)
            .fillMaxSize()
            .background(NovexRootColors.Background),
    ) {
        if (headerHost == null) {
            NovexRootPageHeader(
                space = space,
                searching = searching,
                searchDescription = searchDescription,
                onSettings = onOpenSettings,
                onSearchToggle = onSearchToggle,
                createItems = createItems,
            )
        }
        if (searching) {
            NovexLibrarySearchInput(searchState, searchDescription)
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Reloads both catalogs whenever a detail/editor Activity returns to the root Activity. */
@Composable
private fun rememberNovexCatalogResumeRevision(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return revision
}

@Composable
internal fun rememberNovexLibrarySearchState(): NovexLibrarySearchState {
    val scope = rememberCoroutineScope()
    return remember(scope) { NovexLibrarySearchState(scope = scope) }
}

/** Keeps per-keystroke state below the catalog frame so a long list is not recomposed for every character. */
@Composable
private fun NovexLibrarySearchInput(state: NovexLibrarySearchState, placeholder: String) {
    val value by state.input.collectAsState()
    NovexSearchField(
        value = value,
        onValueChange = state::update,
        placeholder = placeholder,
    )
}

@Composable
private fun NovexWorldCard(
    row: WorldRootRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(154.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
    ) {
        NovexArtwork(
            kind = NovexArtworkKind.WORLD,
            seed = row.world.id,
            imageModel = row.imagePath.existingMediaFile(),
            contentDescription = "${row.world.name}封面",
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                row.world.name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.world.overview.isNotBlank()) {
                Text(
                    row.world.overview,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "角色 ${row.characterCount}  ·  模块 ${row.moduleCount}",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun NovexCharacterRow(
    row: CharacterRootRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(width = 88.dp, height = 104.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            NovexArtwork(
                kind = NovexArtworkKind.CHARACTER,
                seed = row.character.id,
                imageModel = row.imagePath.existingMediaFile(),
                contentDescription = "${row.character.name}头像",
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                row.character.name,
                color = NovexRootColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.profile.summary.isNotBlank()) {
                Text(
                    row.profile.summary,
                    color = NovexRootColors.SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "本体${if (row.variantCount > 0) " · ${row.variantCount} 个分身" else ""}",
                color = NovexRootColors.SecondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
        Icon(
            painterResource(R.drawable.ic_phosphor_more_vertical),
            contentDescription = "${row.character.name}更多操作",
            tint = NovexRootColors.Text,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun NovexCreateRow(label: String, onClick: () -> Unit) {
    NovexLibraryActionRow(
        label = label,
        iconRes = R.drawable.ic_phosphor_plus,
        onClick = onClick,
    )
}

@Composable
private fun NovexImportRow(label: String, onClick: () -> Unit) {
    NovexLibraryActionRow(
        label = label,
        iconRes = R.drawable.ic_phosphor_arrow_up,
        onClick = onClick,
    )
}

@Composable
private fun NovexLibraryActionRow(label: String, iconRes: Int, onClick: () -> Unit) {
    NovexTextActionRow(label = label, icon = iconRes, onClick = onClick)
}

@Composable
private fun NovexEmptyWorldLibrary(onCreateWorld: () -> Unit, onImportWorld: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "empty_world_visual") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(154.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onCreateWorld),
            ) {
                NovexDefaultWorldArtwork(seed = "novex-empty-world")
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.08f),
                                0.46f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.7f),
                            ),
                        ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text("建立第一个世界", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "只需一个名称，封面和内容都可以稍后添加",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        item(key = "create_world") {
            NovexCreateRow(label = "新建世界", onClick = onCreateWorld)
        }
        item(key = "import_world") {
            NovexImportRow(label = "导入世界卡", onClick = onImportWorld)
        }
    }
}

@Composable
private fun NovexEmptyCharacterLibrary(onCreateCharacter: () -> Unit, onImportCharacter: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "empty_character_visual") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCreateCharacter)
                    .padding(vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 88.dp, height = 104.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    NovexDefaultCharacterArtwork(seed = "novex-empty-character")
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        "创建第一个角色",
                        color = NovexRootColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "先写下名字，头像、背景和角色模块都可以留空",
                        color = NovexRootColors.SecondaryText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "本体",
                        color = NovexRootColors.SecondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
        item(key = "create_character") {
            NovexCreateRow(label = "新建角色", onClick = onCreateCharacter)
        }
        item(key = "import_character") {
            NovexImportRow(label = "导入角色卡", onClick = onImportCharacter)
        }
    }
}

@Composable
private fun NovexLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NovexRootColors.Primary, strokeWidth = 2.dp)
    }
}

@Composable
private fun NovexEmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = NovexRootColors.SecondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun NovexDefaultWorldArtwork(seed: String) {
    NovexArtwork(
        kind = NovexArtworkKind.WORLD,
        seed = seed,
        imageModel = null,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NovexDefaultCharacterArtwork(seed: String) {
    NovexArtwork(
        kind = NovexArtworkKind.CHARACTER,
        seed = seed,
        imageModel = null,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}
