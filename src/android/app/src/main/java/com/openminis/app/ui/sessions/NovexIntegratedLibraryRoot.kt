package com.openminis.app.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.MinisApp
import com.openminis.app.cards.*
import com.openminis.app.novex.domain.NovexWorkGroupSnapshot
import com.openminis.app.ui.novex.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.FileTransferModel
import novex.android.LibraryModel
import novex.android.CardSessionModel
import novex.android.CardThumbnail
import novex.content.*
import novex.storage.CardSummary
import sh.calvin.reorderable.*

/** 复用原首页框架、分类、搜索与作品视觉；目录和所有操作只使用新卡片。 */
@Composable internal fun NovexIntegratedLibraryRoot(kind:CardKind,onOpen:(String)->Unit,onCreate:()->Unit,
    onSettings:()->Unit,onConfigure:(String)->Unit,onImport:(android.net.Uri)->Unit) {
    val app=LocalContext.current.applicationContext as MinisApp
    val library:LibraryModel=viewModel(key="public-card-library-${kind.name}")
    val reader:CardSessionModel=viewModel(key="public-card-images-${kind.name}")
    val files:FileTransferModel=viewModel(key="public-card-export-${kind.name}")
    var exportId by rememberSaveable {mutableStateOf<String?>(null)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        val id=exportId;exportId=null
        if(uri!=null && id!=null)files.export(id,uri)
    }
    val groups=rememberNovexWorkGroups()
    val group by groups.snapshots.collectAsState(initial=null)
    val search=rememberNovexLibrarySearchState()
    val query by search.applied.collectAsState()
    var searching by rememberSaveable {mutableStateOf(false)}
    var migrationError by remember {mutableStateOf<String?>(null)}
    var ready by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    var deleting by remember {mutableStateOf<CardSummary?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(onImport)}
    val orderStore=remember(app){NovexManualOrderStore(app)}
    val orderKind=if(kind==CardKind.WORLD)NovexManualOrderKind.WORLDS else NovexManualOrderKind.CHARACTERS
    var order by remember(kind){mutableStateOf(orderStore.read(orderKind))}
    val rows=library.state.cards.filter {it.kind==kind}
    val byId=rows.associateBy {it.id}
    val ordered=mergeNovexManualOrder(rows.map {it.id},order).mapNotNull(byId::get)
    val filtered=ordered.filter {group?.includes(IntegratedCatalog.address(it))==true && it.name.contains(query,true)}
    val list=rememberLazyListState()
    val reorder=rememberReorderableLazyListState(list){from,to->
        val ids=ordered.map {it.id}.toMutableList();val a=ids.indexOf(from.key);val b=ids.indexOf(to.key)
        if(a>=0 && b>=0){ids.add(b,ids.removeAt(a));order=ids;orderStore.write(orderKind,ids)}
    }
    LaunchedEffect(retry){
        migrationError=null
        try {val failures=withContext(Dispatchers.IO){LegacyCards(app).migrate(force=retry>0)}
            migrationError=failures.takeIf {it.isNotEmpty()}?.joinToString("\n");ready=true;library.refresh()
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){migrationError=e.message}
    }
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner,library){
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)library.refresh()}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
    }
    BackHandler(enabled=searching){searching=false;search.clear()}
    val label=if(kind==CardKind.WORLD)"世界" else "角色"
    NovexLibraryFrame(group,if(kind==CardKind.WORLD)NovexRootSpace.WORLDS else NovexRootSpace.CHARACTERS,
        searching,search,"搜索$label",{searching=!searching;if(!searching)search.clear()},onSettings,onConfigure,
        listOf(NovexCreateMenuItem("新建$label",onCreate),NovexCreateMenuItem("导入${label}卡"){picker.launch(arrayOf("*/*"))})) {manage->
        if(ready && !library.state.busy && library.state.error == null && migrationError == null && filtered.isEmpty() && group!=null && group?.selection!=NovexWorkGroupSnapshot.ALL && query.isBlank())
            NovexEmptyWorkGroup(requireNotNull(group),label,manage,onCreate)
        else LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=novexPagePadding(bottom=NovexDimensions.RootBottomInset),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            migrationError?.let {error->item {Text(error);TextButton(onClick={retry++}){Text("重试迁入")}}}
            if(files.state.busy)item {Text("正在导出")}
            files.state.message?.let {message->item {Text(message)}}
            files.state.error?.let {error->item {Text("导出未完成：$error")}}
            library.state.error?.let {error->item {Text(error);TextButton(onClick=library::refresh){Text("重新读取")}}}
            if (migrationError == null && library.state.error == null) {
                if (!ready || group == null || library.state.busy) item { Text("正在读取作品", color = NovexColors.SecondaryText) }
                else if (filtered.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (query.isNotBlank()) "没有找到匹配的$label" else "还没有$label", color = NovexColors.SecondaryText)
                        if (query.isBlank()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onCreate) { Text("新建$label") }
                            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入${label}卡") }
                        }
                    }
                }
            }
            items(filtered,key={it.id}) {card->ReorderableItem(reorder,key=card.id){_->
                Box {
                    PublicCardRow(card,reader,{onOpen(card.id)},Modifier.longPressDraggableHandle())
                    var more by remember(card.id){mutableStateOf(false)}
                    Box(Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick={more=true},enabled=!library.state.busy && !files.state.busy,modifier=Modifier.size(48.dp)) {
                            Icon(NovexIcons.MoreVert,"${card.name}的更多操作",tint=if(kind==CardKind.WORLD)Color.White else NovexColors.Text)
                        }
                        NovexActionMenu(expanded=more,onDismissRequest={more=false},actions=listOf(
                            NovexMenuAction(label="导出",icon=com.openminis.app.R.drawable.ic_phosphor_download_simple,onClick={more=false;exportId=card.id;exporter.launch("作品.novex.zip")}),
                            NovexMenuAction(label="删除",icon=com.openminis.app.R.drawable.ic_phosphor_trash,destructive=true,onClick={more=false;deleting=card})
                        ))
                    }
                }
            }}
        }
    }
    deleting?.let {card->AlertDialog(onDismissRequest={deleting=null},title={Text("删除「${card.name}」？")},
        text={Text("从库中移除卡片及其内部角色。对话保留，引用此卡时将提示不可用。历史和原始资源保留。")},
        confirmButton={TextButton(enabled=!library.state.busy,onClick={deleting=null;library.delete(card)}){Text("删除")}},
        dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

@Composable private fun PublicCardRow(summary:CardSummary,reader:CardSessionModel,onOpen:()->Unit,modifier:Modifier) {
    val app=LocalContext.current.applicationContext as MinisApp
    val card by produceState<ContentDocument?>(null,summary.id,summary.revision){value=withContext(Dispatchers.IO){try {IntegratedCards(app).store.open(summary.id)?.content}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;null}}}
    val current=card
    val imageId=current?.appearance?.let {it.coverResourceId?:it.avatarResourceId}
    val image=current?.resources?.firstOrNull {it.id==imageId}
    @Composable fun artwork(modifier:Modifier){
        if(image!=null)CardThumbnail(image.content,reader,"${summary.name}图片",modifier,maxEdge=640)
        else NovexArtwork(if(summary.kind==CardKind.WORLD)NovexArtworkKind.WORLD else NovexArtworkKind.CHARACTER,summary.id,null,"默认占位图",modifier)
    }
    val count=current?.modules?.flattenModules()?.size?:0
    if(summary.kind==CardKind.WORLD)Box(modifier.fillMaxWidth().height(154.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick=onOpen)) {
        artwork(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.78f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)){
            Text(summary.name,color=Color.White,style=NovexType.ItemTitle,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("角色 ${current?.internalCharacters?.size?:0} · 模块 $count",color=Color.White,style=NovexType.Metadata)
        }
    } else Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick=onOpen).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        artwork(Modifier.size(88.dp,104.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(start=14.dp,end=54.dp)){
            Text(summary.name,style=NovexType.ItemTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("$count 个模块",style=NovexType.Metadata,color=NovexColors.SecondaryText)
        }
    }
}
