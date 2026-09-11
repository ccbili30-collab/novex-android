package novex.android

import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.ui.novex.NovexType
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import novex.content.*

private data class ModuleHit(val id:String,val parent:String?,val horizontal:Boolean,val bounds:Rect)
private data class HorizontalTrack(val bounds:Rect,val scroll:ScrollState)
private data class ModuleDrop(val parent:String?,val before:String?,val inside:String?=null,val promote:Boolean=false)

/** 拖动只产生父级/顺序意图；真正变更由共同编辑器完成。离开或取消手势不写入。 */
@Composable internal fun ModuleTreeEditor(card:ContentDocument,model:CardSessionModel,header:@Composable ()->Unit,footer:@Composable ()->Unit) {
    key(card.id){ModuleTreeEditorContent(card,model,header,footer)}
}

@Composable private fun ModuleTreeEditorContent(card:ContentDocument,model:CardSessionModel,header:@Composable ()->Unit,footer:@Composable ()->Unit) {
    val bodyModules=card.bodyModules()
    val enabled=!model.state.busy
    val memory=model.readingMemory(card.id,"editor")
    val vertical=rememberScrollState(memory.scrollOffset)
    DisposableEffect(card.id,vertical){onDispose {memory.scrollOffset=vertical.value}}
    val expanded=memory.expanded
    val hits=remember {mutableMapOf<String,ModuleHit>()}
    val tracks=remember {mutableMapOf<String,HorizontalTrack>()}
    var origin by remember {mutableStateOf(Offset.Zero)}
    var dragged by remember {mutableStateOf<String?>(null)}
    var point by remember {mutableStateOf(Offset.Zero)}
    var drop by remember {mutableStateOf<ModuleDrop?>(null)}
    var menuTarget by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(model.arrangementTarget) {
        model.arrangementTarget?.let {menuTarget=it;model.arrangementTarget=null}
    }
    var deleteTarget by remember {mutableStateOf<String?>(null)}
    var moveTarget by remember {mutableStateOf<String?>(null)}
    val latestCard by rememberUpdatedState(card)
    val latestModel by rememberUpdatedState(model)
    var nestingCandidate by remember {mutableStateOf<String?>(null)}
    var nestingSince by remember {mutableStateOf(0L)}
    fun destination(at:Offset):ModuleDrop? {
        val moving=dragged?.let {latestCard.modules.findModule(it)}?:return null
        val forbidden=(moving.children.flattenModules().map {it.id}+moving.id).toSet()
        val hit=hits.values.filter {it.id !in forbidden && it.bounds.contains(at) && (!it.horizontal || tracks[it.parent?:card.id]?.bounds?.contains(at)==true)}.minByOrNull {it.bounds.width*it.bounds.height}
        if(hit==null) {
            nestingCandidate=null
            val track=tracks.entries.filter {it.value.bounds.contains(at) && it.key !in forbidden}.minByOrNull {it.value.bounds.height}?:return null
            return if(track.key==card.id)ModuleDrop(null,null,promote=true) else ModuleDrop(track.key,null)
        }
        val fraction=if(hit.horizontal)(at.x-hit.bounds.left)/hit.bounds.width else (at.y-hit.bounds.top)/hit.bounds.height
        val siblings=if(hit.parent==null)latestCard.modules else latestCard.modules.findModule(hit.parent)?.children.orEmpty()
        if(fraction in 0.35f..0.65f) {
            if(nestingCandidate!=hit.id){nestingCandidate=hit.id;nestingSince=android.os.SystemClock.uptimeMillis()}
            if(android.os.SystemClock.uptimeMillis()-nestingSince>=700)
                return ModuleDrop(hit.id,latestCard.modules.findModule(hit.id)?.children?.firstOrNull {it.id!=moving.id}?.id,hit.id)
        } else nestingCandidate=null
        return if(fraction<0.5f)ModuleDrop(hit.parent,hit.id)
        else ModuleDrop(hit.parent,siblings.dropWhile {it.id!=hit.id}.drop(1).firstOrNull {it.id!=moving.id}?.id)

    }
    var viewport by remember {mutableStateOf(Rect.Zero)}
    LaunchedEffect(dragged) {
        if(dragged!=null)while(true) {
            withFrameNanos { }
            drop=destination(point)
            val amount=when {point.y<viewport.top+56->-12f;point.y>viewport.bottom-56->12f;else->0f}
            if(amount!=0f){vertical.scrollBy(amount);drop=destination(point)}
            tracks.values.filter {point.y in it.bounds.top..it.bounds.bottom}.minByOrNull {it.bounds.height}?.let {track->
                val dx=when {point.x<track.bounds.left+40->-10f;point.x>track.bounds.right-40->10f;else->0f}
                if(dx!=0f){track.scroll.scrollBy(dx);drop=destination(point)}
            }
        }
    }
    fun selected(items:List<ContentModule>,parent:String):String? =
        memory.selected[parent]?.takeIf {id->items.any {it.id==id}}?:items.firstOrNull()?.id
    val hoveredTab=drop?.inside?.takeIf {id->hits[id]?.horizontal==true}
    LaunchedEffect(hoveredTab) {
        if(hoveredTab!=null){delay(450);hits[hoveredTab]?.let {memory.selected[it.parent?:card.id]=hoveredTab}}
    }
    fun marker(id:String,parent:String?,horizontal:Boolean):Modifier = Modifier
        .onGloballyPositioned {hits[id]=ModuleHit(id,parent,horizontal,Rect(it.positionInRoot(),it.size.toSize()))}
    @Composable fun highlight(id:String,horizontal:Boolean):Modifier {
        val primary=NovexColors.Primary
        val soft=NovexColors.PrimarySoft
        val target=drop
        val info=hits[id]
        val siblings=if(info?.parent==null)card.modules else card.modules.findModule(info.parent)?.children.orEmpty()
        val end=target!=null && target.inside==null && target.before==null && target.parent==info?.parent && siblings.lastOrNull {it.id!=dragged}?.id==id
        return Modifier.drawBehind {
            if(target?.inside==id || dragged==id)drawRect(soft)
            if(end){if(horizontal)drawLine(primary,Offset(size.width,0f),Offset(size.width,size.height),3.dp.toPx()) else drawLine(primary,Offset(0f,size.height),Offset(size.width,size.height),2.dp.toPx())}
            if(target?.inside==null && target?.before==id) {
                if(horizontal)drawLine(primary,Offset.Zero,Offset(0f,size.height),3.dp.toPx())
                else drawLine(primary,Offset.Zero,Offset(size.width,0f),2.dp.toPx())
            }
        }
    }
    @Composable fun moduleRow(module:ContentModule,parent:String?,register:Boolean=true) {
        val group=module.children.isNotEmpty()
        val open=expanded[module.id]==true
        CardRow(headlineContent={Text(module.name.ifBlank {"未命名模块"},maxLines=1,overflow=TextOverflow.Ellipsis)},
            supportingContent=if(dragged!=module.id && module.blocks.isNotEmpty())({ModuleExcerpt(module,model)}) else null,
            leadingContent={Icon(if(group)NovexIcons.Folder else NovexIcons.Description,null,Modifier.size(21.dp),tint=NovexColors.Primary)},
            trailingContent={Row(verticalAlignment=Alignment.CenterVertically) {
                CardAction(NovexIcons.MoreVert,"模块操作",enabled){menuTarget=module.id}
                CardAction(if(open)NovexIcons.ExpandLess else NovexIcons.ChevronRight,
                    if(open)"收起模块内容" else "展开模块内容",enabled){expanded[module.id]=!open}
            }},
            modifier=Modifier.then(if(register)marker(module.id,parent,false) else Modifier).then(highlight(module.id,false))
                .clickable(enabled=enabled && dragged==null){model.module(module.id)})
        if(register)DisposableEffect(module.id){onDispose {hits.remove(module.id)}}
        CardDivider(Modifier.padding(start=16.dp))
    }
    @Composable fun tabs(items:List<ContentModule>,parent:String?) {
        val containerKey=parent?:card.id
        val scroll=rememberScrollState()
        DisposableEffect(containerKey){onDispose {tracks.remove(containerKey)}}
        val chosen=selected(items,containerKey)
        LaunchedEffect(chosen) {
            withFrameNanos { }
            val track=tracks[containerKey]
            val tab=hits[chosen]?.bounds
            if(track!=null && tab!=null) {
                val delta=when {tab.left<track.bounds.left->tab.left-track.bounds.left;tab.right>track.bounds.right->tab.right-track.bounds.right;else->0f}
                if(delta!=0f)scroll.scrollBy(delta)
            }
        }
        Row(Modifier.fillMaxWidth().onGloballyPositioned {tracks[containerKey]=HorizontalTrack(it.boundsInRoot(),scroll)}.background(if(parent==null && drop?.promote==true)NovexColors.PrimarySoft else Color.Transparent).horizontalScroll(scroll)) {
            items.forEach {module->key(module.id){
                CardTab(module.name.ifBlank {"未命名模块"},module.id==selected(items,containerKey),{memory.selected[containerKey]=module.id},
                    modifier=marker(module.id,parent,true).then(highlight(module.id,true)),enabled=enabled)
                if(module.id==chosen)CardAction(NovexIcons.MoreVert,"模块操作",enabled){menuTarget=module.id}
                DisposableEffect(module.id){onDispose {hits.remove(module.id)}}
            }}
            CardAction(NovexIcons.Add,if(parent==null)"新增横向模块" else "新增子模块",enabled){if(parent==null)model.addPresentedModule(true) else model.addModule(parent)}
        }
        CardDivider()
    }
    @Composable fun contents(module:ContentModule) {
        if(dragged==module.id)return
        // 展开的正文与预览采用同一个 Markdown 渲染器；逐页展开，避免一次加载整张大卡。
        ModuleExpandedContent(card,module,model,memory,enabled)
        module.characterIds.forEach {id->
            val role=card.internalCharacters.single {it.id==id}
            key(id) {
                var roleMore by remember {mutableStateOf(false)}
                CardRow(headlineContent={Text(role.name,maxLines=1,overflow=TextOverflow.Ellipsis)},leadingContent={RoleThumbnail(role,model)},
                    trailingContent={Box {
                        CardAction(NovexIcons.MoreVert,"角色操作",enabled){roleMore=true}
                        com.openminis.app.ui.novex.NovexActionMenu(expanded=roleMore,onDismissRequest={roleMore=false},actions=listOf(
                            com.openminis.app.ui.novex.NovexMenuAction(label="编辑角色",icon=com.openminis.app.R.drawable.ic_phosphor_pencil_simple,onClick={model.editTarget(id)}),
                            com.openminis.app.ui.novex.NovexMenuAction(label="移出此模块",icon=com.openminis.app.R.drawable.ic_phosphor_caret_right,onClick={model.placeCharacter(id,null)})
                        ))
                    }},modifier=Modifier.clickable(enabled=enabled){model.editTarget(id)})
            }
        }
        if(module.layout==ModuleLayout.HORIZONTAL && module.children.isNotEmpty()) {
            tabs(module.children,module.id)
            module.children.firstOrNull {it.id==selected(module.children,module.id)}?.let {child->
                contents(child)
            }
        } else {
            module.children.forEach {child->key(child.id){
                moduleRow(child,module.id)
                if(expanded[child.id]==true) {
                    val rail=NovexColors.Divider
                    Column(Modifier.padding(start=16.dp).drawBehind {drawLine(rail,Offset.Zero,Offset(0f,size.height),1.dp.toPx())}){contents(child)}
                }
            }}
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().onGloballyPositioned {origin=it.boundsInRoot().topLeft;viewport=it.boundsInRoot()}.pointerInput(enabled) {
        if(enabled)awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            val held=awaitLongPressOrCancellation(down.id)?:return@awaitEachGesture
            val hit=hits.values.firstOrNull {it.bounds.contains(held.position+origin)}?:return@awaitEachGesture
            dragged=hit.id;point=held.position+origin
            try {
                while(true) {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    val change=event.changes.firstOrNull {it.id==down.id}?:break
                    point=change.position+origin;drop=destination(point)
                    if(!change.pressed) {
                        if(!change.isConsumed) {
                            change.consume()
                            val id=dragged;val result=drop
                            if(id!=null && result!=null) {
                                if(result.promote)latestModel.promoteModule(id) else latestModel.placeModule(id,result.parent,result.before)
                            }
                        }
                        break
                    }
                    change.consume()
                }
            } finally {dragged=null;drop=null;nestingCandidate=null}
        }
    }.verticalScroll(vertical),verticalArrangement=Arrangement.spacedBy(0.dp)) {
        header()
        if(card.usesMainSlot()) {
            val mainScroll=rememberScrollState()
            DisposableEffect(card.id){onDispose {tracks.remove(card.id)}}
            Row(Modifier.fillMaxWidth().onGloballyPositioned {tracks[card.id]=HorizontalTrack(it.boundsInRoot(),mainScroll)}
                .background(if(drop?.promote==true)NovexColors.PrimarySoft else Color.Transparent).horizontalScroll(mainScroll)) {
                CardTab("主要",true,{},enabled=enabled)
                CardAction(NovexIcons.Add,"新增横向模块",enabled){model.addPresentedModule(true)}
            }
            CardDivider()
            bodyModules.forEach {root->key(root.id) {
                moduleRow(root,null)
                if(expanded[root.id]==true)contents(root)
            }}
            AddModuleRow({model.addPresentedModule(false)},enabled)
        } else {
            tabs(bodyModules,null)
            bodyModules.firstOrNull {it.id==selected(bodyModules,card.id)}?.let {root->key(root.id){
                contents(root)
                if(root.layout==ModuleLayout.VERTICAL || root.children.isEmpty())AddModuleRow({model.addModule(root.id)},enabled)
            }}
        }
        footer()
    }
        dragged?.let {id->card.modules.findModule(id)?.let {moving->
            Column(Modifier.widthIn(max=220.dp).padding(horizontal=12.dp).offset {
                IntOffset(0,(point.y-origin.y-78.dp.toPx()).roundToInt().coerceIn(0,(viewport.height-80.dp.toPx()).roundToInt().coerceAtLeast(0)))
            }.shadow(3.dp,RoundedCornerShape(6.dp)).background(NovexColors.Surface,RoundedCornerShape(6.dp))
                .border(1.dp,NovexColors.Primary,RoundedCornerShape(6.dp)).padding(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Icon(if(moving.children.isEmpty())NovexIcons.Description else NovexIcons.Folder,null,Modifier.size(16.dp),tint=NovexColors.Primary)
                    Text(moving.name.ifBlank {"未命名模块"},style=NovexType.Metadata,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                val target=drop?.inside?.let {card.modules.findModule(it)?.name?.ifBlank {"未命名模块"}}
                Text(when {target!=null->"放入「$target」";drop?.promote==true->"移到横向栏";drop!=null->"同级排序";else->"拖动到目标位置"},style=NovexType.Metadata,color=NovexColors.Primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }}
    }
    menuTarget?.let {id->card.modules.findModule(id)?.let {module->
        AlertDialog(onDismissRequest={menuTarget=null},title={Text(module.name.ifBlank {"模块"})},text={Column {
            TextButton(onClick={menuTarget=null;model.module(id)}){Text("编辑")}
            TextButton(onClick={menuTarget=null;model.addModule(id)}){Text("新增子模块")}
            TextButton(onClick={menuTarget=null;model.moduleLayout(id,if(module.layout==ModuleLayout.HORIZONTAL)ModuleLayout.VERTICAL else ModuleLayout.HORIZONTAL)}){Text(if(module.layout==ModuleLayout.HORIZONTAL)"子模块竖向排列" else "子模块横向排列")}
            TextButton(onClick={menuTarget=null;moveTarget=id}){Text("移动到…")}
            val parent=card.modules.parentOfModule(id)
            val siblings=if(parent==null)card.modules else card.modules.findModule(parent)!!.children
            val at=siblings.indexOfFirst {it.id==id}
            if(at>0)TextButton(onClick={menuTarget=null;model.placeModule(id,parent,siblings[at-1].id)}){Text("移到前一项")}
            if(at<siblings.lastIndex)TextButton(onClick={menuTarget=null;model.placeModule(id,parent,siblings.getOrNull(at+2)?.id)}){Text("移到后一项")}
            TextButton(onClick={menuTarget=null;deleteTarget=id}){Text("删除")}
        }},confirmButton={TextButton(onClick={menuTarget=null}){Text("关闭")}})
    }}
    deleteTarget?.let {id->
        AlertDialog(onDismissRequest={deleteTarget=null},title={Text("删除模块及其子模块？")},
            text={Text("从本次草稿中移除此模块及全部下属内容，其他模块和图片素材保留。退出编辑保存后生效。")},
            confirmButton={TextButton(enabled=enabled,onClick={deleteTarget=null;model.deleteModule(id)}){Text("删除")}},
            dismissButton={TextButton(onClick={deleteTarget=null}){Text("取消")}})
    }
    moveTarget?.let {id->
        val module=card.modules.findModule(id)
        val forbidden=module?.children?.flattenModules()?.map {it.id}.orEmpty()+id
        AlertDialog(onDismissRequest={moveTarget=null},title={Text("放入模块")},text={Column() {
            TextButton(onClick={moveTarget=null;model.promoteModule(id)}){Text("移到顶部横向栏")}
            card.modules.flattenModules().filter {it.id !in forbidden}.forEach {parent->TextButton(onClick={moveTarget=null;model.placeModule(id,parent.id,parent.children.firstOrNull {it.id!=id}?.id)}){Text(parent.name.ifBlank {"未命名模块"})}}
        }},confirmButton={TextButton(onClick={moveTarget=null}){Text("取消")}})
    }
}
