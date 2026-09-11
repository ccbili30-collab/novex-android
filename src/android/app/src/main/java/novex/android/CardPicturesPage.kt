package novex.android

import com.openminis.app.ui.novex.DropdownMenuItem

import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.DropdownMenu

import novex.content.flattenModules
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.ui.novex.NovexType
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import novex.content.ContentBlock

/** 图片资源属于当前目标卡；选择用途只修改引用，不复制原始图片。 */
@Composable internal fun CardPicturesPage(model:CardSessionModel) {
    val picker=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){uri->uri?.let(model::addPictureResource)}
    val state=model.state
    val card=state.shownDraft?:return
    val resources=card.resources.filter {it.mediaType.startsWith("image/")}
    var removing by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableStateOf<String?>(null)}
    removing?.let {id->
        val count=card.modules.flattenModules().sumOf {m->m.blocks.count {it is ContentBlock.Image && it.resourceId==id}}
        val uses=buildList {if(count>0)add("$count 处模块展示");if(card.appearance.avatarResourceId==id)add("头像");if(card.appearance.coverResourceId==id)add("封面")}
        AlertDialog(onDismissRequest={if(!state.busy)removing=null},title={Text("移除素材？")},text={Column {Text(if(uses.isEmpty())"从当前卡片移除这份备用素材。历史版本保留。" else "同时移除："+uses.joinToString("、")+"。历史版本保留。");state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(enabled=!state.busy,onClick={model.removeResource(id,uses.isNotEmpty()){removing=null}}){Text(if(uses.isEmpty())"移除" else "移除素材及用途")}},dismissButton={TextButton(enabled=!state.busy,onClick={removing=null}){Text("取消")}})
    }
    LazyVerticalGrid(columns=GridCells.Adaptive(140.dp),modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            TextButton(enabled=!state.busy,onClick={picker.launch(arrayOf("image/*"))}){
                Icon(NovexIcons.Add,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("添加图片素材")
            }
        }
        if(resources.isEmpty())item(span={GridItemSpan(maxLineSpan)}) {Text("还没有图片素材",style=NovexType.Metadata,color=NovexColors.SecondaryText)}
        items(resources.size,key={resources[it].id}) {index->
            val resource=resources[index]
            val uses=buildList {
                if(card.appearance.avatarResourceId==resource.id)add("头像")
                if(card.appearance.coverResourceId==resource.id)add("封面")
                val count=card.modules.flattenModules().sumOf {module->module.blocks.count {it is ContentBlock.Image && it.resourceId==resource.id}}
                if(count>0)add("$count 处展示")
            }
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(NovexColors.SurfaceMuted)
                    .clickable(enabled=!state.busy){selected=resource.id},contentAlignment=Alignment.Center) {
                    CardThumbnail(resource.content,model,modifier=Modifier.fillMaxSize(),maxEdge=512)
                }
                Box {
                    CardRow(headlineContent={Text("图片 ${index+1}")},supportingContent={Text(uses.joinToString(" · ").ifEmpty {"备用素材"})},
                        modifier=Modifier.clickable(enabled=!state.busy){selected=resource.id},
                        trailingContent={Icon(NovexIcons.MoreVert,"图片用途",Modifier.size(18.dp))})
                    DropdownMenu(expanded=selected==resource.id,onDismissRequest={selected=null}) {
                        if(state.pictureReturn==CardPage.EDIT)DropdownMenuItem(text={Text("放入当前模块")},onClick={selected=null;model.insertOwnedImage(resource.id)})
                        DropdownMenuItem(text={Text("设为封面")},enabled=card.appearance.coverResourceId!=resource.id,onClick={selected=null;model.useOwnedAppearance(resource.id,true)})
                        DropdownMenuItem(text={Text("设为头像")},enabled=card.appearance.avatarResourceId!=resource.id,onClick={selected=null;model.useOwnedAppearance(resource.id,false)})
                        DropdownMenuItem(text={Text("移除素材")},onClick={selected=null;removing=resource.id})
                    }
                }
            }
        }
    }
}
