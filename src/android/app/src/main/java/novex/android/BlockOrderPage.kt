package novex.android

import novex.content.flattenModules
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import novex.content.ContentBlock

@Composable internal fun BlockOrderPage(model:CardSessionModel) {
    val state=model.state
    val blocks=state.shownDraft?.modules?.flattenModules()?.singleOrNull {it.id==state.moduleId}?.blocks.orEmpty()
    ReorderList(blocks.mapIndexed {index,block->OrderEntry(block.id,"${if(block is ContentBlock.Image)"图片" else "文字"} ${index+1}")},
        !state.busy,{model.module(requireNotNull(state.moduleId),it)},model::moveBlock,operationLabel="内容操作",
        hasLeading={id->blocks.single {it.id==id} is ContentBlock.Image},
        leading={id->
            val block=blocks.single {it.id==id}
            if(block is ContentBlock.Image) {
                val resource=requireNotNull(state.shownDraft).resources.single {it.id==block.resourceId}
                CardThumbnail(resource.content,model)
            }
        },
        description={id->
            val block=blocks.single {it.id==id}
            val ref=when(block){is ContentBlock.Text->block.content;is ContentBlock.Image->block.caption}
            var summary by remember(ref){mutableStateOf(if(ref==null)"没有图片说明" else "读取中")}
            LaunchedEffect(ref) {
                if(ref!=null)summary=try{model.textPage(ref,0,80).text.ifBlank {"空白内容"}}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){"内容暂时无法读取"}
            }
            Text(summary,maxLines=2,overflow=TextOverflow.Ellipsis)
        })
}
