package novex.android

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.noven.NovenColors
import com.openminis.app.ui.theme.LocalChatPalette
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

/** 卡片类型徽标：深色小胶囊，中性色不承担动作语义。 */
@Composable internal fun CardKindBadge(kind:CardKind,modifier:Modifier=Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(NovenColors.ChipSelectedBg).padding(horizontal=8.dp,vertical=3.dp)) {
        Text(if(kind==CardKind.WORLD)"世界" else "角色",color=NovenColors.ChipSelectedText,
            style=NovexType.Metadata,fontWeight=FontWeight.SemiBold)
    }
}

private val CardOverlap=22.dp
private val CardMargin=12.dp
private val CardPadding=18.dp

/**
 * 阅读态头图：有封面时满宽 4:3 裁切；无封面角色卡退化为纯排版 hero
 * （徽标 + 大字标题 + 薄荷重音线，头像作为附加槽位，不占封面位）。
 */
@Composable internal fun CardHero(card:ContentDocument,model:CardSessionModel,editing:Boolean=false,onImage:(()->Unit)?=null,onName:(()->Unit)?=null,onIntroduction:(()->Unit)?=null,chromeOverlay:Boolean=false) {
    if(editing) {
        val image=card.appearance.coverResourceId?:card.appearance.avatarResourceId
        val shape=RoundedCornerShape(8.dp)
        // [A3a] 基础信息区：虚线薄荷框标出"可编辑"范围（editor-v1/01）。
        val dashColor=NovenColors.Mint
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)
            .drawBehind {
                val stroke=androidx.compose.ui.graphics.drawscope.Stroke(
                    width=1.dp.toPx(),
                    pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(),4.dp.toPx()),0f))
                drawRoundRect(color=dashColor.copy(alpha=0.55f),
                    cornerRadius=androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(),8.dp.toPx()),
                    style=stroke)
            }.padding(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=28.dp)
                .then(if(onImage!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onImage) else Modifier),
                verticalAlignment=Alignment.CenterVertically) {
                Text("图片",style=NovexType.Metadata,color=NovexColors.SecondaryText,modifier=Modifier.weight(1f).padding(start=8.dp))
                Icon(NovexIcons.Edit,"编辑卡片主图",Modifier.padding(end=8.dp).size(18.dp),tint=NovexColors.SecondaryText)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1.85f).clip(shape)
                .background(NovexColors.SurfaceMuted)
                .then(if(onImage!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onImage).semantics{contentDescription="编辑卡片主图"} else Modifier),contentAlignment=Alignment.Center) {
                val resource=card.resources.firstOrNull {it.id==image}
                if(resource!=null)ReadingImage(resource.content,model,Modifier.fillMaxSize(),ContentScale.Crop)
                else NovexArtwork(
                    kind=if(card.kind==CardKind.WORLD)NovexArtworkKind.WORLD else NovexArtworkKind.CHARACTER,
                    seed=card.id,imageModel=null,contentDescription="默认占位图",modifier=Modifier.fillMaxSize())
            }
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
        }
        return
    }
    val cover=card.appearance.coverResourceId?.let {id->card.resources.firstOrNull {it.id==id}}
    if(cover!=null || card.kind==CardKind.WORLD) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f/3f)) {
            if(cover!=null)ReadingImage(cover.content,model,Modifier.fillMaxSize(),ContentScale.Crop)
            else NovexArtwork(NovexArtworkKind.WORLD,card.id,null,"世界封面",Modifier.fillMaxSize())
        }
    } else {
        // 无封面排版：DETAIL 页悬浮圆钮会压在内容顶上，预留铬高度让徽章/标题让位。
        Column(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,top=if(chromeOverlay)76.dp else 4.dp,bottom=20.dp)) {
            val avatar=card.appearance.avatarResourceId?.let {id->card.resources.firstOrNull {it.id==id}}
            if(avatar!=null) {
                RoleAvatarImage(avatar.content,model,72.dp)
                Spacer(Modifier.height(14.dp))
            }
            CardKindBadge(card.kind)
            Spacer(Modifier.height(12.dp))
            Text(card.name.ifBlank {"未命名"},color=NovenColors.Text,
                style=MaterialTheme.typography.displaySmall.copy(fontWeight=FontWeight.Bold),
                modifier=Modifier.then(if(onName!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onName) else Modifier))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(NovenColors.Mint))
        }
    }
}

/** 同一渲染树读取正式内容或草稿；横向选择仅改变展示，不改变采用规则。 */
@Composable fun CardReading(card:ContentDocument,model:CardSessionModel,modifier:Modifier=Modifier,onCharacter:((String)->Unit)?=null,readingScope:String="saved",onModule:((String)->Unit)?=null,onImage:(()->Unit)?=null,onName:(()->Unit)?=null,onEmpty:(()->Unit)?=null,onIntroduction:(()->Unit)?=null,byline:(@Composable ()->Unit)?=null,trailing:(@Composable ()->Unit)?=null,chromeOverlay:Boolean=false) {
    key(card.id,readingScope){CardReadingContent(FolderContents.organize(card),model,modifier,onCharacter,readingScope,onModule,onImage,onName,onEmpty,onIntroduction,byline,trailing,chromeOverlay)}
}

@Composable private fun CardReadingContent(card:ContentDocument,model:CardSessionModel,modifier:Modifier=Modifier,onCharacter:((String)->Unit)?=null,readingScope:String="saved",onModule:((String)->Unit)?=null,onImage:(()->Unit)?=null,onName:(()->Unit)?=null,onEmpty:(()->Unit)?=null,onIntroduction:(()->Unit)?=null,byline:(@Composable ()->Unit)?=null,trailing:(@Composable ()->Unit)?=null,chromeOverlay:Boolean=false) {
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
    val coverRes=card.appearance.coverResourceId?.let {id->card.resources.firstOrNull {it.id==id}}
    val hasCover=coverRes!=null || card.kind==CardKind.WORLD
    val avatarRes=if(card.kind==CardKind.CHARACTER)card.appearance.avatarResourceId?.let {id->card.resources.firstOrNull {it.id==id}} else null
    val hasCoverHero=hasCover

    /** 卡段：同一白卡表面的一行内容；首段圆角、末段圆角、封面存在时整体上移压住 hero。 */
    @Composable fun Modifier.cardSegment(first:Boolean=false,last:Boolean=false):Modifier =
        then(if(hasCoverHero)Modifier.offset(y=(-CardOverlap)) else Modifier)
            .padding(horizontal=CardMargin)
            .clip(RoundedCornerShape(
                topStart=if(first)20.dp else 0.dp,topEnd=if(first)20.dp else 0.dp,
                bottomStart=if(last)20.dp else 0.dp,bottomEnd=if(last)20.dp else 0.dp))
            .background(NovenColors.Surface)

    fun LazyListScope.segment(key:Any,first:Boolean=false,last:Boolean=false,content:@Composable ColumnScope.()->Unit) {
        item(key){Column(Modifier.fillMaxWidth().cardSegment(first,last),content=content)}
    }

    fun LazyListScope.modules(modules:List<ContentModule>,parent:String,horizontal:Boolean,showHeading:Boolean=true,fold:Boolean=false,depth:Int=0,mutedText:Boolean=false) {
        val selected=memory.selected[parent]?.takeIf {id->modules.any {it.id==id}}?:modules.firstOrNull()?.id
        if(horizontal && modules.isNotEmpty())segment("tabs-$parent") {
            CardTabStrip(modules,selected){id->if(parent==card.id)selectRoot(id) else memory.selected[parent]=id}
        }
        (if(horizontal)modules.filter {it.id==selected} else modules).forEach {module->
            // 横向页签的选中模块正文直接内联（页签即标题）；纵向模块带下级或为嵌套子级时可折叠。
            val collapsible=!horizontal && (fold || module.children.isNotEmpty())
            val open=memory.expanded[module.id]==true
            if(showHeading && (!horizontal || collapsible))segment("title-${module.id}") {
                CardRow(headlineContent={Text(module.name.ifBlank {"未命名模块"},style=NovexType.ItemTitle)},
                    supportingContent=if(module.blocks.isNotEmpty() && collapsible)({ModuleExcerpt(module,model)}) else null,
                    leadingContent={Icon(if(module.children.isNotEmpty())NovexIcons.Folder else NovexIcons.Description,null,Modifier.size(20.dp),tint=NovexColors.SecondaryText)},
                    modifier=Modifier.padding(start=(depth*16).coerceAtMost(64).dp).then(if(onModule!=null)Modifier.clickable {onModule(module.id)} else Modifier),
                    trailingContent=if(collapsible)({CardAction(if(open)NovexIcons.ExpandLess else NovexIcons.ChevronRight,if(open)"收起" else "展开"){memory.expanded[module.id]=!open}}) else null)
            }
            if(collapsible && !open) {
                segment("divider-${module.id}"){CardDivider(Modifier.padding(start=(16+depth*16).coerceAtMost(80).dp,end=16.dp))}
                return@forEach
            }
            fun pages(id:String,ref:ContentRef,muted:Boolean=mutedText) {
                val key="$id:${ref.value}"
                repeat(memory.pages[key]?:1){index->segment("$key:$index") {
                    Column(Modifier.fillMaxWidth().padding(horizontal=CardPadding,vertical=4.dp).then(if(onModule!=null)Modifier.clickable {onModule(module.id)} else Modifier)) {
                        ReadingText(ref,index,model,muted=muted){memory.pages[key]=maxOf(memory.pages[key]?:1,index+2)}
                    }
                }}
            }
            module.blocks.forEach {block->when(block) {
                is ContentBlock.Text->pages(block.id,block.content)
                is ContentBlock.Image->{
                    segment(block.id){Column(Modifier.padding(horizontal=CardPadding,vertical=8.dp).then(if(onModule!=null)Modifier.clickable {onModule(module.id)} else Modifier)){
                        ReadingImage(card.resources.single {it.id==block.resourceId}.content,model,Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),ContentScale.Crop)}}
                    block.caption?.let {pages("caption-${block.id}",it,muted=true)}
                }
            }}
            module.characterIds.forEach {id->
                val role=card.internalCharacters.single {it.id==id}
                segment("character-$id") {
                    CardRow(headlineContent={Text(role.name)},leadingContent={RoleAvatar(role,model)},
                        trailingContent={Icon(NovexIcons.ChevronRight,null,Modifier.size(18.dp),tint=NovexColors.TertiaryText)},
                        modifier=Modifier.padding(start=(depth*16).coerceAtMost(64).dp).then(if(onCharacter!=null)Modifier.clickable {onCharacter(id)} else Modifier))
                }
            }
            modules(module.children,module.id,module.layout==ModuleLayout.HORIZONTAL,fold=true,depth=depth+1)
            segment("divider-${module.id}"){CardDivider(Modifier.padding(horizontal=16.dp))}
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
        item("hero"){CardHero(card,model,onImage=onImage,onName=onName,onIntroduction=onIntroduction,chromeOverlay=chromeOverlay)}
        // 卡顶条：恒定发出，提供圆角与封面重叠；角色卡的叠放头像槽位也在这里。
        segment("card-top",first=true) {
            if(avatarRes!=null && hasCoverHero)Box(Modifier.fillMaxWidth().height(44.dp)) {
                RoleAvatarImage(avatarRes.content,model,76.dp,
                    Modifier.align(Alignment.TopStart).padding(start=CardPadding).offset(y=(-38).dp).border(3.dp,NovenColors.Surface,CircleShape))
            } else Spacer(Modifier.height(if(hasCoverHero)12.dp else 14.dp))
        }
        if(hasCoverHero)segment("head") {
            Column(Modifier.fillMaxWidth().padding(horizontal=CardPadding).padding(bottom=10.dp)) {
                CardKindBadge(card.kind)
                Spacer(Modifier.height(8.dp))
                Text(card.name.ifBlank {if(card.kind==CardKind.CHARACTER)"未命名角色" else "未命名世界"},
                    style=NovexType.PageTitle,color=NovenColors.Text,
                    modifier=Modifier.fillMaxWidth().then(if(onName!=null)Modifier.clickable(enabled=!model.state.busy,onClick=onName) else Modifier))
            }
        }
        card.introductionModule()?.let {introduction->
            modules(listOf(introduction),"${card.id}-introduction",horizontal=false,showHeading=false,mutedText=true)
        }
        if(byline!=null)segment("byline") {
            Box(Modifier.fillMaxWidth().padding(horizontal=CardPadding,vertical=4.dp)){byline()}
            CardDivider(Modifier.padding(start=CardPadding,end=CardPadding,top=6.dp))
        }
        if(card.usesMainSlot())segment("main-slot") {
            Column { Row(Modifier.fillMaxWidth()){CardTab("主要",true,{})};CardDivider() }
        }
        modules(bodyModules,card.id,!card.usesMainSlot())
        if(bodyModules.isEmpty() && onEmpty!=null)segment("empty-module") {
            AddModuleRow(onEmpty,!model.state.busy)
        }
        val placed=card.modules.flattenModules().flatMap {it.characterIds}.toSet()
        val unplaced=card.internalCharacters.filter {it.id !in placed}
        if(unplaced.isNotEmpty()) {
            segment("characters-heading") {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(NovexIcons.Groups,null,Modifier.size(20.dp),tint=NovexColors.SecondaryText)
                    Text("世界中的角色",style=NovexType.ItemTitle,color=NovenColors.Text)
                }
            }
            unplaced.forEach {role->segment("unplaced-${role.id}") {
                CardRow(headlineContent={Text(role.name)},leadingContent={RoleAvatar(role,model)},
                    trailingContent={Icon(NovexIcons.ChevronRight,null,Modifier.size(18.dp),tint=NovexColors.TertiaryText)},
                    modifier=Modifier.then(if(onCharacter!=null)Modifier.clickable {onCharacter(role.id)} else Modifier))
            }}
        }
        // 卡底条：恒定发出，提供圆角收尾与可选的卡内动作区。
        segment("card-tail",last=true) {
            Column(Modifier.fillMaxWidth().padding(horizontal=CardPadding).padding(top=6.dp,bottom=18.dp)) {
                if(trailing!=null)trailing() else Spacer(Modifier.height(2.dp))
            }
        }
    }
}
private data class ReadingValue<T>(val data:T?=null,val error:String?=null,val loading:Boolean=true)
private const val READING_PAGE_SIZE=4096
@Composable private fun ReadingText(ref:ContentRef,index:Int,model:CardSessionModel,autoLoad:Boolean=true,muted:Boolean=false,onMore:()->Unit) {
    var result by remember(ref,index){mutableStateOf(ReadingValue<TextPage>())}
    LaunchedEffect(ref,index) {
        result=try{ReadingValue(data=model.textPage(ref,index.toLong()*READING_PAGE_SIZE,READING_PAGE_SIZE),loading=false)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){ReadingValue(error="正文读取未完成",loading=false)}
        if(autoLoad && result.data?.next!=null)onMore()
    }
    if(result.loading || result.error!=null)Text(if(result.loading) "载入中" else result.error.orEmpty())
    else {
        val palette=LocalChatPalette.current
        val content:@Composable ()->Unit={com.openminis.app.ui.chat.MarkdownBlock(rawText=result.data?.text.orEmpty(),isStreaming=false)}
        if(muted)CompositionLocalProvider(LocalChatPalette provides palette.copy(primaryText=palette.secondaryText)){content()}
        else content()
        if(!autoLoad && result.data?.next!=null)TextButton(onClick=onMore){Text("继续展开",color=NovenColors.Mint)}
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
