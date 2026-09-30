package com.openminis.app.cards

import com.openminis.app.ui.novex.NovexPageTopBar
import com.openminis.app.ui.novex.NovexColors

import com.openminis.app.ui.novex.Scaffold

import com.openminis.app.ui.novex.RadioButton

import com.openminis.app.ui.novex.Checkbox

import com.openminis.app.ui.novex.TextButton

import com.openminis.app.ui.novex.Button

import com.openminis.app.ui.novex.AlertDialog

import novex.content.flattenModules
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import novex.runtime.*
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexType

/**
 * 卡绑定设置：同一 CardBinding 的三个语义页（§9d）。
 * page ∈ answer | background | manage，各页只写自己的字段。
 */
enum class CardBindingPage(val routeKey: String, val title: String, val footer: String) {
    ANSWER("answer", "回答身份", "选一个回答者：世界作为 GM 叙述（讲述世界并扮演其中人物），角色以该角色身份回答。不选则由 Nova 默认回答。"),
    BACKGROUND("background", "背景资料", "多选。选中的卡作为设定注入上下文；展开「模块携带」可控制单模块的默认/必带/不带。"),
    MANAGE("manage", "可管理内容", "多选。被管理的卡允许 AI 在对话中修改；未选中的卡只能读。管理不等于背景。"),
    ;

    companion object {
        fun fromRoute(value: String?): CardBindingPage =
            entries.firstOrNull { it.routeKey == value } ?: ANSWER
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun IntegratedCardSettings(chat:String,page:String?=null,onBack:()->Unit) {
    val bindingPage = CardBindingPage.fromRoute(page)
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val app=context.applicationContext as com.openminis.app.MinisApp
    val model:com.openminis.app.ui.chat.ChatViewModel=androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner=com.openminis.app.ui.chat.ChatViewModelStore.ownerFor(chat),
        factory=com.openminis.app.ui.chat.ChatViewModel.factory(chat,app.chatRepository,app.providerRepository,
            appContext=app,memoryRepository=app.memoryRepository,skillRepository=app.skillRepository,mcpRepository=app.mcpRepository))
    val ready by model.conversationSettingsReady.collectAsState()
    val cards=remember {IntegratedCards(context)}
    val saver=remember {Saver<CardBinding?,String>(save={it?.encode()?:"null"},restore={if(it=="null")null else CardBinding.decode(it)})}
    var baseline by rememberSaveable(chat,stateSaver=saver) {mutableStateOf<CardBinding?>(null)}
    var draft by rememberSaveable(chat,stateSaver=saver) {mutableStateOf<CardBinding?>(null)}
    var hydrated by rememberSaveable(chat){mutableStateOf(false)}
    var discard by remember {mutableStateOf(false)}
    var choices by remember {mutableStateOf<List<Triple<SourceSelection,String,String?>>>(emptyList())}
    var modules by remember {mutableStateOf<Map<SourceSelection,List<Pair<String,String>>>>(emptyMap())}
    var expanded by remember {mutableStateOf<SourceSelection?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(true)}
    LaunchedEffect(chat,ready) {
        if(!ready)return@LaunchedEffect
        try {
            val loaded=withContext(Dispatchers.IO){
                run {LegacyCards(app).migrate();model.integratedCardBinding()} to cards.store.list().flatMap {summary->
                    val root=requireNotNull(cards.store.open(summary.id)).content
                    // [T-interaction-semantics-ui] 互动语义标签：世界根=GM 叙述（讲述世界并扮演其中人物），
                    // 角色根/世界内角色=扮演（以该角色身份回答）
                    val rootLabel=if(root.kind==novex.content.CardKind.WORLD)"GM 叙述" else "扮演"
                    listOf(Triple(SourceSelection(root.id),root.name,rootLabel))+root.internalCharacters.map {Triple(SourceSelection(root.id,it.id),"${root.name} · ${it.name}","扮演")}
                }
            };if(!hydrated){baseline=loaded.first;draft=loaded.first?:CardBinding();hydrated=true}
            val known=loaded.second.map {it.first}.toSet()
            val all=listOfNotNull(loaded.first?.primary)+loaded.first?.backgrounds.orEmpty()+loaded.first?.managed.orEmpty().map {SourceSelection(it.rootId,it.targetId)}
            choices=loaded.second+(all.distinct()-known).map {Triple(it,"作品未找到（可取消关联）",null)}
            modules=withContext(Dispatchers.IO){loaded.second.associate {(source,_)->
                val card=novex.content.ContentTargets.find(requireNotNull(cards.store.open(source.rootId)).content,source.targetId)
                source to (listOf(card)+card.internalCharacters).flatMap {c->c.modules.flattenModules().map {it.id to "${c.name} · ${it.name.ifBlank {"未命名模块"}}"}}
            }}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message}finally{busy=false}
    }
    val back:()->Unit={if(!busy){if(draft!=null && draft!=(baseline?:CardBinding()))discard=true else onBack()}}
    // 与 CardPages 同理：isImeVisible 在隐藏键盘后仍可能返回 true（IME 残留
    // inset hint），改用实际占位高度判断。
    androidx.activity.compose.BackHandler(enabled=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)==0){back()}
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未保存的设置？")},
        confirmButton={TextButton(onClick={discard=false;onBack()}){Text("放弃更改")}},
        dismissButton={TextButton(onClick={discard=false}){Text("继续设置")}})
    Scaffold(containerColor=NovexColors.Canvas,topBar={NovexPageTopBar(title=bindingPage.title,onBack=back)},bottomBar={
        Button(enabled=!busy && draft!=null,onClick={busy=true;scope.launch {
            try {model.saveIntegratedCardBinding(requireNotNull(draft),baseline);onBack()}
            catch(failure:Exception){error=failure.message}finally{busy=false}
        }},modifier=Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)){Text("保存")}
    }) {padding->LazyColumn(Modifier.fillMaxSize().padding(padding)) {
        error?.let {item {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp))}}
        item {
            NovexEditorSection(header = bindingPage.title, footer = bindingPage.footer) {
                when (bindingPage) {
                    CardBindingPage.ANSWER -> {
                        // Nova 默认项：primary = null
                        CardChoiceRow(
                            name = "Nova（默认）",
                            badge = "内置",
                            trailing = {
                                RadioButton(
                                    selected = draft?.primary == null,
                                    enabled = !busy,
                                    onClick = { draft = draft?.copy(primary = null) },
                                )
                            },
                            onClick = { if (!busy) draft = draft?.copy(primary = null) },
                        )
                        choices.forEach { (target, name, interactLabel) ->
                            CardChoiceRow(
                                name = name,
                                badge = interactLabel?.let { if (it == "GM 叙述") "世界 · $it" else "角色 · $it" },
                                trailing = {
                                    RadioButton(
                                        selected = draft?.primary == target,
                                        enabled = !busy,
                                        onClick = { draft = draft?.copy(primary = target) },
                                    )
                                },
                                onClick = { if (!busy) draft = draft?.copy(primary = target) },
                            )
                        }
                    }
                    CardBindingPage.BACKGROUND -> {
                        choices.forEach { (target, name, interactLabel) ->
                            val selected = draft?.backgrounds?.contains(target) == true
                            Column {
                                CardChoiceRow(
                                    name = name,
                                    badge = interactLabel?.let { if (it == "GM 叙述") "世界" else "角色" },
                                    trailing = {
                                        Checkbox(
                                            checked = selected,
                                            enabled = !busy,
                                            onCheckedChange = { yes ->
                                                draft = draft?.copy(backgrounds = if (yes) (draft!!.backgrounds + target).distinct() else draft!!.backgrounds - target)
                                            },
                                        )
                                    },
                                    onClick = {
                                        if (!busy) draft = draft?.let { d ->
                                            d.copy(backgrounds = if (selected) d.backgrounds - target else (d.backgrounds + target).distinct())
                                        }
                                    },
                                )
                                if (selected) {
                                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                                        TextButton(onClick = { expanded = if (expanded == target) null else target }) {
                                            Text(if (expanded == target) "收起模块携带" else "模块携带")
                                        }
                                        if (expanded == target) {
                                            modules[target].orEmpty().forEach { (id, label) ->
                                                ModuleRuleRow(
                                                    label = label,
                                                    current = draft?.overrides?.get(id),
                                                    enabled = !busy,
                                                    onPick = { rule ->
                                                        draft = draft?.let { d ->
                                                            d.copy(overrides = if (rule == null) d.overrides - id else d.overrides + (id to rule))
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    CardBindingPage.MANAGE -> {
                        choices.forEach { (target, name, interactLabel) ->
                            val managed = ManagementTarget(target.rootId, target.targetId)
                            val selected = draft?.managed?.contains(managed) == true
                            CardChoiceRow(
                                name = name,
                                badge = interactLabel?.let { if (it == "GM 叙述") "世界" else "角色" },
                                trailing = {
                                    Checkbox(
                                        checked = selected,
                                        enabled = !busy,
                                        onCheckedChange = { yes ->
                                            draft = draft?.copy(managed = if (yes) draft!!.managed + managed else draft!!.managed - managed)
                                        },
                                    )
                                },
                                onClick = {
                                    if (!busy) draft = draft?.let { d ->
                                        d.copy(managed = if (selected) d.managed - managed else d.managed + managed)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }}
}

/** 三仓页共用行：卡名 + 类型徽标 + 右端控件；整行可点。 */
@Composable
private fun CardChoiceRow(
    name: String,
    badge: String?,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = NovexType.Body, color = NovexColors.Text)
            badge?.let {
                Text(it, style = NovexType.Metadata, color = NovexColors.SecondaryText)
            }
        }
        trailing()
    }
}

/**
 * 模块携带三态段控：默认 | 必带 | 不带。
 * C 档新组件——选中块 accent 填充。
 */
@Composable
private fun ModuleRuleRow(
    label: String,
    current: Boolean?,
    enabled: Boolean,
    onPick: (Boolean?) -> Unit,
) {
    val options = listOf<Pair<Boolean?, String>>(null to "默认", true to "必带", false to "不带")
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = NovexType.Metadata,
            color = NovexColors.Text,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                .background(NovexColors.SurfaceMuted),
        ) {
            options.forEach { (rule, title) ->
                val active = current == rule
                Box(
                    Modifier
                        .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                        .background(if (active) NovexColors.Primary else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = enabled) { onPick(rule) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        title,
                        style = NovexType.Metadata,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else NovexColors.SecondaryText,
                    )
                }
            }
        }
    }
}
