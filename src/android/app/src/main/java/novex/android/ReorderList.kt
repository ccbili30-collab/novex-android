package novex.android

import com.openminis.app.ui.novex.NovexIcons

import com.openminis.app.ui.novex.DropdownMenuItem

import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.DropdownMenu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class OrderEntry(val id:String,val name:String)

/** 列表持有手势，浮动条目独立于可回收行；松手才保存，取消不改变内容。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun ReorderList(modules:List<OrderEntry>,enabled:Boolean,onOpen:(String)->Unit,onMove:(String,String?)->Unit,description:@Composable (String)->Unit,operationLabel:String,leading:(@Composable (String)->Unit)?=null,hasLeading:(String)->Boolean={true},footer:LazyListScope.()->Unit={}) {
    val list=rememberLazyListState()
    var dragged by remember {mutableStateOf<String?>(null)}
    var pointerY by remember {mutableFloatStateOf(0f)}
    var grabOffset by remember {mutableFloatStateOf(0f)}
    var rowHeight by remember {mutableIntStateOf(0)}
    val latestModules by rememberUpdatedState(modules)
    val latestMove by rememberUpdatedState(onMove)
    val density=LocalDensity.current
    val edge=with(density){32.dp.toPx()}
    val speed=with(density){6.dp.toPx()}
    fun destination():String? {
        val candidates=list.layoutInfo.visibleItemsInfo.filter {item->item.key!=dragged && latestModules.any {it.id==item.key}}
        return candidates.firstOrNull {pointerY<it.offset+it.size/2f}?.key as? String
            ?:candidates.lastOrNull()?.let {last->latestModules.dropWhile {it.id!=last.key}.drop(1).firstOrNull {it.id!=dragged}?.id}
    }
    LaunchedEffect(dragged) {
        if(dragged!=null)while(true) {
            withFrameNanos { }
            val layout=list.layoutInfo
            val amount=when {pointerY<layout.viewportStartOffset+edge->-speed;pointerY>layout.viewportEndOffset-edge->speed;else->0f}
            if(amount!=0f)list.scrollBy(amount)
        }
    }
    @Composable fun row(entry:OrderEntry,active:Boolean) {
        var menu by remember(entry.id){mutableStateOf(false)}
        CardRow(leadingContent=if(hasLeading(entry.id))leading?.let {content->{content(entry.id)}} else null,
            headlineContent={Text(entry.name.ifBlank {"未命名模块"})},
            supportingContent={if(active)Text("松手调整顺序") else description(entry.id)},
            
            modifier=Modifier.clickable(enabled=enabled){if(dragged==null)onOpen(entry.id)},
            trailingContent={Box {
                CardAction(NovexIcons.MoreVert,"$operationLabel：${entry.name.ifBlank {"未命名模块"}}",enabled){if(dragged==null)menu=true}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    val index=modules.indexOfFirst {it.id==entry.id}
                    DropdownMenuItem(text={Text("向上移动")},enabled=index>0,onClick={menu=false;onMove(entry.id,modules[index-1].id)})
                    DropdownMenuItem(text={Text("向下移动")},enabled=index<modules.lastIndex,onClick={menu=false;onMove(entry.id,modules.getOrNull(index+2)?.id)})
                }
            }})
        HorizontalDivider()
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        LazyColumn(Modifier.fillMaxSize().pointerInput(enabled) {
            if(enabled)awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                val held=awaitLongPressOrCancellation(down.id)?:return@awaitEachGesture
                val y=held.position.y+list.layoutInfo.viewportStartOffset
                val item=list.layoutInfo.visibleItemsInfo.firstOrNull {y>=it.offset && y<it.offset+it.size && latestModules.any {entry->entry.id==it.key}}?:return@awaitEachGesture
                dragged=item.key as String;grabOffset=y-item.offset;pointerY=y;rowHeight=item.size
                try {
                    while(true) {
                        // 长按已经取得排序权，先于列表普通滑动处理后续移动。
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        val change=event.changes.firstOrNull {it.id==down.id}?:break
                        if(!change.pressed) {
                            if(change.isConsumed)break // 系统取消会发送已消费的释放，不能保存。
                            change.consume()
                            val id=dragged;val before=destination();dragged=null
                            if(id!=null)latestMove(id,before)
                            break
                        }
                        pointerY+=change.position.y-change.previousPosition.y
                        change.consume()
                    }
                } finally {dragged=null}
            }
        },state=list,contentPadding=PaddingValues(12.dp)) {
            items(modules,key={it.id}) {entry->
                val hidden=dragged==entry.id
                Column(Modifier.graphicsLayer {alpha=if(hidden)0f else 1f}.semantics {if(hidden)invisibleToUser()}){row(entry,false)}
            }
            footer()
        }
        modules.firstOrNull {it.id==dragged}?.let {entry->
            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp).height(with(density){rowHeight.toDp()})
                .graphicsLayer {translationY=pointerY-grabOffset-list.layoutInfo.viewportStartOffset}) {row(entry,true)}
        }
    }
}
