package novex.android

import com.openminis.app.ui.novex.NovexPageTopBar
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.ui.novex.NovexSearchField

import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.OutlinedTextField
import com.openminis.app.ui.novex.Scaffold

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import novex.content.CardKind

/** 原应用提供会话与导航；这里只持有卡片编辑状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun IntegratedCardLibrary(kind:CardKind?=null,initialRoot:String?=null,initialTarget:String?=null,
    onUse:(String,String,Boolean)->Unit,onBack:()->Unit={},onSurface:(Boolean)->Unit={},initialImportUri:String?=null,showBack:Boolean=false,createOnly:Boolean=false,
    onOpenCard:((String)->Unit)?=null,onExisting:(String,String,Boolean)->Unit={_,_,_->}) {
    val key="integrated-cards-${kind?.name}-$initialRoot-$initialTarget"
    val library:LibraryModel=viewModel(key="$key-library")
    val session:CardSessionModel=viewModel(key="$key-editor")
    val files:FileTransferModel=viewModel(key="$key-files")
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,session,library) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME){session.refreshSaved();library.refresh()}
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    var selected by rememberSaveable(key){mutableStateOf(initialRoot)}
    var creating by rememberSaveable(key){mutableStateOf(createOnly)}
    var query by rememberSaveable(key){mutableStateOf("")}
    var name by rememberSaveable(key){mutableStateOf("")}
    var exportRoot by rememberSaveable(key){mutableStateOf<String?>(null)}
    var exportTarget by rememberSaveable(key){mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let {uri->files.prepare(uri,kind?:CardKind.CHARACTER)}}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        val root=exportRoot;if(uri!=null && root!=null)files.export(root,uri,exportTarget?:root)
        exportRoot=null;exportTarget=null
    }
    LaunchedEffect(initialImportUri){initialImportUri?.let {files.prepare(android.net.Uri.parse(it),kind?:CardKind.CHARACTER)}}
    val reportSurface by rememberUpdatedState(onSurface)
    LaunchedEffect(selected,files.state.visible){reportSurface(selected==null && !files.state.visible)}
    fun openCard(id:String) {
        if(onOpenCard!=null)onOpenCard(id) else selected=id
    }
    LaunchedEffect(selected){selected?.let {session.open(it,if(it==initialRoot)initialTarget?:it else it)}}
    LaunchedEffect(library.state.createdId){library.state.createdId?.let {creating=false;name="";library.acknowledgeCreation();openCard(it)}}
    LaunchedEffect(files.state.importedId){files.state.importedId?.let {files.acknowledge();library.refresh();openCard(it)}}
    if(files.state.visible){ImportPage(files,session){library.refresh()};return}
    val exit:()->Unit={session.dismissOpening();if(initialRoot!=null || showBack)onBack() else {selected=null;library.refresh()}}
    if(selected!=null) {
        val opening=session.opening
        if(opening!=null || session.state.saved?.id!=selected)CardOpeningPage(opening?.error,session.state.busy || opening==null,
            {session.open(requireNotNull(selected),opening?.target?:initialTarget?:requireNotNull(selected))},
            if(opening?.error!=null && session.state.saved!=null)({session.dismissOpening();selected=session.state.saved?.id}) else null,exit)
        else CardPages(session,files,exitTarget=initialTarget?:initialRoot,onExport={
            exportRoot=requireNotNull(session.state.saved).id;exportTarget=session.state.targetId;exporter.launch("作品.novex.zip")
        },onInteract={val root=requireNotNull(session.state.saved).id;onUse(root,session.state.targetId?:root,false)},
            onExisting={manage->val root=requireNotNull(session.state.saved).id;onExisting(root,session.state.targetId?:root,manage)},
            onManage={val root=requireNotNull(session.state.saved).id;onUse(root,session.state.targetId?:root,true)},onExit=exit)
        return
    }
    Scaffold(containerColor=NovexColors.Canvas,topBar={NovexPageTopBar(onBack=if(showBack)onBack else null,title=if(createOnly)"新建${if(kind==CardKind.WORLD)"世界" else "角色"}" else if(kind==CardKind.WORLD)"世界" else "角色",actions={if(!createOnly) {
        TextButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!files.state.busy){Text("导入")}
        CardAction(NovexIcons.Add,"新建",!library.state.busy){creating=true}
    }})}) {padding->LazyColumn(Modifier.fillMaxSize().padding(padding),state=listState,contentPadding=PaddingValues(bottom=80.dp)) {
        if(!createOnly) {
        item {NovexSearchField(query,{query=it},"搜索作品",onClear={query=""})}
        library.state.error?.let {item {Text(it);TextButton(onClick=library::refresh){Text("重新读取")}}}
        items(library.state.imports,key={"pending-${it.id}"}){draft->CardRow(headlineContent={Text(draft.name)},supportingContent={Text("待确认导入")},modifier=Modifier.clickable {files.resume(draft.id)})}
        items(library.state.cards.filter {kind==null || it.kind==kind}.filter {it.name.contains(query,ignoreCase=true)},key={it.id}){card->CardRow(headlineContent={Text(card.name)},leadingContent={Icon(if(card.kind==CardKind.WORLD)NovexIcons.Public else NovexIcons.Person,null,Modifier.size(24.dp),tint=NovexColors.Primary)},trailingContent={Icon(NovexIcons.ChevronRight,null,Modifier.size(18.dp))},modifier=Modifier.clickable {openCard(card.id)});CardDivider(Modifier.padding(horizontal=16.dp))}
    }}}
    if(creating)AlertDialog(onDismissRequest={if(!library.state.busy){creating=false;if(createOnly)onBack()}},title={Text("新建作品")},text={Column {
        OutlinedTextField(value=name,onValueChange={name=it},label={Text("名称")},enabled=!library.state.busy)
        library.state.error?.let {Text(it)}
    }},confirmButton={TextButton(enabled=!library.state.busy && name.isNotBlank(),onClick={library.create(name,kind?:CardKind.CHARACTER)}){Text("创建")}},dismissButton={TextButton(enabled=!library.state.busy,onClick={creating=false;if(createOnly)onBack()}){Text("取消")}})
}
