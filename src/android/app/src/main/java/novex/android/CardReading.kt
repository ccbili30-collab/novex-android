package novex.android

import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.NovexIcons
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import novex.content.*
import kotlinx.coroutines.CancellationException
import novex.storage.TextPage

internal data class ReadingAnchor(val moduleId:String?,val blockId:String?,val key:String,val offset:Int)
internal class ReadingMemory {
    var scrollIndex=0
    var scrollOffset=0
    var anchor:ReadingAnchor?=null
    val pages=mutableStateMapOf<String,Int>()
    val selected=mutableStateMapOf<String,String>()
    val expanded=mutableStateMapOf<String,Boolean>()
    var capture:(()->Unit)?=null
}

internal fun ContentDocument.introductionModule():ContentModule? =
    modules.firstOrNull {it.name in setOf("简介","人物简介","世界简介") && it.children.isEmpty() && it.characterIds.isEmpty()}

// 简介在主图下的基本信息区编辑和展示，不再重复作为横向页签。
// 不删除或改写原模块，正文、编号、模型采用和导出保持原样。
internal fun ContentDocument.bodyModules():List<ContentModule> {
    val introductionId=introductionModule()?.id
    return modules.filterNot {it.id==introductionId}
}

@Composable internal fun ModuleExcerpt(module:ContentModule?,model:CardSessionModel) {
    val ref=module?.blocks?.filterIsInstance<ContentBlock.Text>()?.firstOrNull()?.content
    var text by remember(ref){mutableStateOf("")}
    LaunchedEffect(ref) {
        text=if(ref==null)"" else try {model.textPage(ref,0,100).text.let(::moduleExcerptText)}
        catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){"内容读取未完成"}
    }
    if(text.isNotEmpty())Text(text,style=NovexType.Metadata,color=NovexColors.SecondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
}

@Composable internal fun CardHero(card:ContentDocument,model:CardSessionModel,editing:Boolean=false,onImage:(()->Unit)?=null,onName:(()->Unit)?=null,onIntroduction:(()->Unit)?=null) {
    val image=card.appearance.coverResourceId?:card.appearance.avatarResourceId
    val shape=RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().then(if(editing)Modifier.padding(horizontal=12.dp,vertical=4.dp).border(1.dp,NovexColors.Primary.copy(alpha=0.4f),shape).padding(8.dp) else Modifier)) {
        if(editing)Row(Modifier.fillMaxWidth().heightIn(min=28.dp)
            .then(if(onImage!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onImage) else Modifier),
            verticalAlignment=Alignment.CenterVertically) {
            Text("图片",style=NovexType.Metadata,color=NovexColors.SecondaryText,modifier=Modifier.weight(1f).padding(start=8.dp))
            Icon(NovexIcons.Edit,"编辑卡片主图",Modifier.padding(end=8.dp).size(18.dp),tint=NovexColors.SecondaryText)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(if(editing)1.85f else 1.9f).then(if(editing)Modifier.clip(shape) else Modifier)
            .background(NovexColors.SurfaceMuted)
            .then(if(onImage!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onImage).semantics {contentDescription="编辑卡片主图"} else Modifier),contentAlignment=Alignment.Center) {
            val resource=card.resources.firstOrNull {it.id==image}
            if(resource!=null)ReadingImage(resource.content,model,Modifier.fillMaxSize(),ContentScale.Crop)
            else com.openminis.app.ui.novex.NovexArtwork(
                kind=if(card.kind==CardKind.WORLD)com.openminis.app.ui.novex.NovexArtworkKind.WORLD else com.openminis.app.ui.novex.NovexArtworkKind.CHARACTER,
                seed=card.id,imageModel=null,contentDescription="默认占位图",modifier=Modifier.fillMaxSize())
        }
        if(editing) {
            CardRow(headlineContent={Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Text(if(card.kind==CardKind.CHARACTER)"姓名" else "名称",color=NovexColors.SecondaryText)
                Text(card.name,maxLines=1,overflow=TextOverflow.Ellipsis)
            }},trailingContent={Icon(NovexIcons.ChevronRight,null,Modifier.size(18.dp))},
                modifier=Modifier.then(if(onName!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onName) else Modifier))
            CardDivider(Modifier.padding(horizontal=8.dp))
            CardRow(headlineContent={Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Text("简介",color=NovexColors.SecondaryText)
                ModuleExcerpt(card.introductionModule(),model)
            }},trailingContent={Icon(NovexIcons.ChevronRight,null,Modifier.size(18.dp))},
                modifier=Modifier.then(if(onIntroduction!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onIntroduction) else Modifier))
        } else Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
            Text(card.name.ifBlank {if(card.kind==CardKind.CHARACTER)"姓名" else "名称"},style=NovexType.PageTitle,color=NovexColors.Text,
                modifier=Modifier.fillMaxWidth().heightIn(min=36.dp).then(if(onName!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onName) else Modifier))
            if(card.introductionModule()==null)Box(Modifier.fillMaxWidth().heightIn(min=32.dp)
                .then(if(onIntroduction!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onIntroduction) else Modifier)) {
                Text("简介",style=NovexType.Metadata,color=NovexColors.TertiaryText)
            }
            // 已有简介由下方同一个惰性正文列表完整呈现，不能用单行摘要代替原文。
        }
    }
}

/** 同一渲染树读取正式内容或草稿；横向选择仅改变展示，不改变采用规则。 */
@Composable fun CardReading(card:ContentDocument,model:CardSessionModel,modifier:Modifier=Modifier,onCharacter:((String)->Unit)?=null,readingScope:String="saved",onModule:((String)->Unit)?=null,onImage:(()->Unit)?=null,onName:(()->Unit)?=null,onEmpty:(()->Unit)?=null,onIntroduction:(()->Unit)?=null) {
    key(card.id,readingScope){CardReadingContent(FolderContents.organize(card),model,modifier,onCharacter,readingScope,onModule,onImage,onName,onEmpty,onIntroduction)}
}

@Composable private fun CardReadingContent(card:ContentDocument,model:CardSessionModel,modifier:Modifier=Modifier,onCharacter:((String)->Unit)?=null,readingScope:String="saved",onModule:((String)->Unit)?=null,onImage:(()->Unit)?=null,onName:(()->Unit)?=null,onEmpty:(()->Unit)?=null,onIntroduction:(()->Unit)?=null) {
    val bodyModules=card.bodyModules()
    val memory=remember(card.id,readingScope){model.readingMemory(card.id,readingScope)}
    val list=rememberLazyListState(memory.scrollIndex,memory.scrollOffset)
    DisposableEffect(list){onDispose {memory.scrollIndex=list.firstVisibleItemIndex;memory.scrollOffset=list.firstVisibleItemScrollOffset}}
    val scope=rememberCoroutineScope()
    val continuous=!card.rootModulesHorizontal()
    fun selectRoot(id:String) {
        memory.selected[card.id]=id
        scope.launch {withFrameNanos { };list.scrollToItem(0)}
    }
    fun LazyListScope.modules(modules:List<ContentModule>,parent:String,horizontal:Boolean,showHeading:Boolean=true,fold:Boolean=false,depth:Int=0) {
        val selected=memory.selected[parent]?.takeIf {id->modules.any {it.id==id}}?:modules.firstOrNull()?.id
        if(horizontal && modules.isNotEmpty())item("tabs-$parent") {
            CardTabStrip(modules,selected){id->if(parent==card.id)selectRoot(id) else memory.selected[parent]=id}
        }
        (if(horizontal)modules.filter {it.id==selected} else modules).forEach {module->
            val collapsible=fold || (!horizontal && module.children.isNotEmpty())
            val open=memory.expanded[module.id]==true
            if(showHeading && (!horizontal || collapsible))item("title-${module.id}") {
                CardRow(headlineContent={Text(module.name.ifBlank {"未命名模块"},style=NovexType.Body)},
                    supportingContent=if(module.blocks.isNotEmpty())({ModuleExcerpt(module,model)}) else null,
                    leadingContent={Icon(if(module.children.isNotEmpty())NovexIcons.Folder else NovexIcons.Description,null,Modifier.size(21.dp),tint=NovexColors.Primary)},
                    modifier=Modifier.padding(start=(depth*16).coerceAtMost(64).dp).then(if(onModule!=null)Modifier.clickable {onModule(module.id)} else Modifier),
                    trailingContent=if(collapsible)({CardAction(if(open)NovexIcons.ExpandLess else NovexIcons.ChevronRight,if(open)"收起" else "展开"){memory.expanded[module.id]=!open}}) else null)
            }
            if(collapsible && !open) {
                item("divider-${module.id}"){CardDivider(Modifier.padding(start=(16+depth*16).coerceAtMost(80).dp,end=16.dp))}
                return@forEach
            }
            fun pages(id:String,ref:ContentRef) {
                val key="$id:${ref.value}"
                repeat(memory.pages[key]?:1){index->item("$key:$index") {
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).then(if(onModule!=null)Modifier.clickable {onModule(module.id)} else Modifier)) {
                        ReadingText(ref,index,model){memory.pages[key]=maxOf(memory.pages[key]?:1,index+2)}
                    }
                }}
            }
            module.blocks.forEach {block->when(block) {
                is ContentBlock.Text->pages(block.id,block.content)
                is ContentBlock.Image->{
                    item(block.id){Column(Modifier.padding(horizontal=16.dp).then(if(onModule!=null)Modifier.clickable {onModule(module.id)} else Modifier)){ReadingImage(card.resources.single {it.id==block.resourceId}.content,model)}}
                    block.caption?.let {pages("caption-${block.id}",it)}
                }
            }}
            module.characterIds.forEach {id->
                val role=card.internalCharacters.single {it.id==id}
                item("character-$id") {
                    CardRow(headlineContent={Text(role.name)},leadingContent={RoleThumbnail(role,model)},modifier=Modifier.then(if(onCharacter!=null)Modifier.clickable {onCharacter(id)} else Modifier))
                }
            }
            modules(module.children,module.id,module.layout==ModuleLayout.HORIZONTAL,fold=true,depth=depth+1)
            item("divider-${module.id}"){CardDivider(Modifier.padding(horizontal=16.dp))}
        }
    }
    LazyColumn(modifier.fillMaxSize().pointerInput(card.id,bodyModules.map {it.id},continuous) {
        if(!continuous && bodyModules.size>1) {
            var distance=0f
            detectHorizontalDragGestures(onDragStart={distance=0f},onDragCancel={distance=0f},onDragEnd={
                if(kotlin.math.abs(distance)>size.width*0.15f) {
                    val current=bodyModules.indexOfFirst {it.id==(memory.selected[card.id]?:bodyModules.first().id)}.coerceAtLeast(0)
                    bodyModules.getOrNull(current+if(distance<0)1 else -1)?.let {selectRoot(it.id)}
                }
            }) {change,amount->distance+=amount;change.consume()}
        }
    },state=list,contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
        item("hero"){CardHero(card,model,onImage=onImage,onName=onName,onIntroduction=onIntroduction)}
        card.introductionModule()?.let {introduction->
            modules(listOf(introduction),"${card.id}-introduction",horizontal=false,showHeading=false)
        }
        if(card.usesMainSlot())item("main-slot") {
            Column { Row(Modifier.fillMaxWidth()){CardTab("主要",true,{})};CardDivider() }
        }
        modules(bodyModules,card.id,!card.usesMainSlot())
        if(bodyModules.isEmpty() && onEmpty!=null)item("empty-module") {
            AddModuleRow(onEmpty,!model.state.busy)
        }
        val placed=card.modules.flattenModules().flatMap {it.characterIds}.toSet()
        if(card.internalCharacters.any {it.id !in placed}) {
            item("characters"){Text("世界中的角色",style=NovexType.SectionTitle,modifier=Modifier.padding(horizontal=16.dp))}
            items(card.internalCharacters.filter {it.id !in placed},key={"character-${it.id}"}) {role->
                CardRow(headlineContent={Text(role.name)},leadingContent={RoleThumbnail(role,model)},modifier=Modifier.then(if(onCharacter!=null)Modifier.clickable {onCharacter(role.id)} else Modifier))
            }
        }
    }
}
private data class ReadingValue<T>(val data:T?=null,val error:String?=null,val loading:Boolean=true)
private const val READING_PAGE_SIZE=4096
@Composable private fun ReadingText(ref:ContentRef,index:Int,model:CardSessionModel,autoLoad:Boolean=true,onMore:()->Unit) {
    var result by remember(ref,index){mutableStateOf(ReadingValue<TextPage>())}
    LaunchedEffect(ref,index) {
        result=try{ReadingValue(data=model.textPage(ref,index.toLong()*READING_PAGE_SIZE,READING_PAGE_SIZE),loading=false)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){ReadingValue(error="正文读取未完成",loading=false)}
        if(autoLoad && result.data?.next!=null)onMore()
    }
    if(result.loading || result.error!=null)Text(if(result.loading) "载入中" else result.error.orEmpty())
    else {
        com.openminis.app.ui.chat.MarkdownBlock(rawText=result.data?.text.orEmpty(),isStreaming=false)
        if(!autoLoad && result.data?.next!=null)TextButton(onClick=onMore){Text("继续展开")}
    }
}
@Composable internal fun ReadingImage(ref:ContentRef,model:CardSessionModel,modifier:Modifier=Modifier.fillMaxWidth(),scale:ContentScale=ContentScale.Fit) {
    var result by remember(ref){mutableStateOf(ReadingValue<android.graphics.Bitmap>())}
    LaunchedEffect(ref) {
        result=try{ReadingValue(data=requireNotNull(model.image(ref)),loading=false)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){ReadingValue(error="图片读取未完成",loading=false)}
    }
    when {
        result.loading->Text("图片载入中")
        result.error!=null->Text(requireNotNull(result.error),color=MaterialTheme.colorScheme.error)
        else->Image(requireNotNull(result.data).asImageBitmap(),"模块图片",modifier,contentScale=scale)
    }
}

/** 单模块展开保持图片与正文顺序；编辑入口独立于右侧展开箭头。 */
@Composable internal fun ModuleExpandedContent(card:ContentDocument,module:ContentModule,model:CardSessionModel,memory:ReadingMemory,enabled:Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        @Composable fun text(ref:ContentRef,blockKey:String) {
            val pageKey="expanded:$blockKey:${ref.value}"
            val count=memory.pages[pageKey]?:1
            repeat(count){index->
                key(pageKey,index){
                    // 已展开的前页不重复提供“继续”，分页进度随编辑返回保留。
                    ReadingText(ref,index,model,autoLoad=index<count-1){memory.pages[pageKey]=maxOf(memory.pages[pageKey]?:1,index+2)}
                }
            }
        }
        module.blocks.forEach {block->key(block.id){
            Column(Modifier.fillMaxWidth().clickable(enabled=enabled){model.module(module.id)}) {
                when(block) {
                    is ContentBlock.Text->text(block.content,block.id)
                    is ContentBlock.Image->{
                        ReadingImage(card.resources.single {it.id==block.resourceId}.content,model)
                        block.caption?.let {text(it,"caption-${block.id}")}
                    }
                }
            }
        }}
        if(module.blocks.isEmpty() && module.children.isEmpty() && module.characterIds.isEmpty())
            Text("暂无内容",style=NovexType.Metadata,color=NovexColors.TertiaryText,
                modifier=Modifier.fillMaxWidth().clickable(enabled=enabled){model.module(module.id)}.padding(vertical=12.dp))
    }
}

/** Compact directory summaries never display editing markup. */
internal fun moduleExcerptText(value:String):String = value
    .replace(Regex("!\\[([^]]*)\\]\\([^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]*)\\]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(Regex("\\s+"), " ").trim()
