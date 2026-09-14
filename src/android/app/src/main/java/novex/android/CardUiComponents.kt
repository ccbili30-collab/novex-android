package novex.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.novex.*

/** Card-specific compositions of the original application's typography, icons and controls. */
@Composable internal fun CardAction(icon:ImageVector,label:String,enabled:Boolean=true,onClick:()->Unit) {
    IconButton(enabled=enabled,onClick=onClick,modifier=Modifier.size(NovexDimensions.MinimumTouch)) {
        Icon(icon,label,Modifier.size(NovexDimensions.HeaderActionIconSize),tint=if(enabled)NovexColors.Text else NovexColors.TertiaryText)
    }
}

@Composable internal fun CardRow(
    headlineContent:@Composable ()->Unit,
    modifier:Modifier=Modifier,
    supportingContent:(@Composable ()->Unit)?=null,
    leadingContent:(@Composable ()->Unit)?=null,
    trailingContent:(@Composable ()->Unit)?=null,
) {
    com.openminis.app.ui.novex.ListItem(
        headlineContent=headlineContent,modifier=modifier,supportingContent=supportingContent,
        leadingContent=leadingContent,trailingContent=trailingContent,
        contentPadding=PaddingValues(horizontal=16.dp,vertical=0.dp),minimumHeight=NovexDimensions.MinimumTouch,
    )
}

@Composable internal fun CardDivider(modifier:Modifier=Modifier) {
    HorizontalDivider(modifier,thickness=NovexDimensions.Hairline,color=NovexColors.Divider)
}

/** A text tab, not a chip. The whole hit area participates in drag, selection and accessibility. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun CardTab(label:String,active:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Column(modifier.width(IntrinsicSize.Max).widthIn(min=88.dp,max=220.dp).semantics {selected=active}.clickable(enabled=enabled,role=Role.Tab,onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.heightIn(min=48.dp).padding(horizontal=16.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
            Text(label,color=if(active)NovexColors.Primary else NovexColors.SecondaryText,style=NovexType.Body,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if(active)NovexColors.Primary else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable internal fun CardTabStrip(modules:List<novex.content.ContentModule>,selected:String?,onSelect:(String)->Unit) {
    val scroll=rememberScrollState()
    val bounds=remember {mutableMapOf<String,Rect>()}
    var viewport by remember {mutableStateOf(Rect.Zero)}
    LaunchedEffect(selected,viewport.width) {
        withFrameNanos { }
        bounds[selected]?.let {tab->
            val delta=when {tab.left<viewport.left->tab.left-viewport.left;tab.right>viewport.right->tab.right-viewport.right;else->0f}
            if(viewport.width>0 && delta!=0f)scroll.scrollTo((scroll.value+delta.toInt()).coerceAtLeast(0))
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().onGloballyPositioned {viewport=it.boundsInRoot()}.horizontalScroll(scroll)) {
            modules.forEach {module->key(module.id){
                CardTab(module.name.ifBlank {"未命名模块"},module.id==selected,{onSelect(module.id)},Modifier.onGloballyPositioned {bounds[module.id]=Rect(it.positionInRoot(),it.size.toSize())})
                DisposableEffect(module.id){onDispose {bounds.remove(module.id)}}
            }}
        }
        CardDivider()
    }
}

@Composable internal fun AddModuleRow(onClick:()->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled,onClick=onClick).padding(horizontal=16.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(NovexIcons.Add,null,Modifier.size(20.dp),tint=NovexColors.Primary)
        Text("新增模块",style=NovexType.Metadata,color=NovexColors.Primary)
    }
}
