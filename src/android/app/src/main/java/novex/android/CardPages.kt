package novex.android

import com.openminis.app.ui.novex.DropdownMenuItem

import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.OutlinedTextField
import com.openminis.app.ui.novex.Scaffold
import com.openminis.app.ui.novex.DropdownMenu

import com.openminis.app.ui.novex.NovexPageTopBar
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.NovexIcons
import novex.content.flattenModules
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import novex.content.ContentBlock
import novex.content.CardKind
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun CardPages(model:CardSessionModel,files:FileTransferModel,exitTarget:String?=null,onExport:()->Unit,onInteract:()->Unit,onManage:()->Unit={},onExisting:(Boolean)->Unit={},onExit:()->Unit) {
    val state=model.state
    var roleMenu by remember {mutableStateOf<String?>(null)}
    var addIntroduction by remember {mutableStateOf(false)}
    var introductionText by remember {mutableStateOf("")}
    val openIntroduction:()->Unit={
        val displayed=if(state.page==CardPage.DETAIL)state.shownSaved else state.shownDraft
        if(displayed?.introductionModule()!=null)model.introduction()
        else {introductionText="";addIntroduction=true}
    }
    var placeRole by remember {mutableStateOf<String?>(null)}
    var removeRole by remember {mutableStateOf<String?>(null)}
    var leaveDecision by remember {mutableStateOf(false)}
    var discard by remember {mutableStateOf(false)}
    var renameCard by remember {mutableStateOf(false)}
    var cardName by remember {mutableStateOf("")}
    var removeChoice by remember {mutableStateOf<String?>(null)}
    var editMore by remember {mutableStateOf(false)}
    var newCharacter by remember {mutableStateOf(false)}
    var copyCharacter by remember {mutableStateOf(false)}
    var more by remember {mutableStateOf(false)}
    var deleteCard by remember {mutableStateOf(false)}
    var moduleOptions by remember {mutableStateOf(false)}
    var imageOptions by remember {mutableStateOf(false)}
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(model::addImage)}
    val replacementPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(model::replaceImage)}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardVisible=WindowInsets.isImeVisible
    val back:()->Unit = {
        if (keyboardVisible) {
            keyboard?.hide()
            focus.clearFocus()
        } else when (state.page) {
            CardPage.DETAIL -> {
                if (state.targetId != null && state.targetId != state.saved?.id && state.targetId != exitTarget)
                    model.showTarget(requireNotNull(state.saved).id)
                else onExit()
            }
            CardPage.MODULES -> {
                if (state.targetId != state.saved?.id && state.targetId != state.editorEntryTarget)
                    model.editTarget(requireNotNull(state.saved).id)
                else model.requestLeave { leaveDecision = true }
            }
            CardPage.EDIT -> model.modules()
            CardPage.PICTURES -> model.returnFromPictures()
            CardPage.BLOCKS -> model.returnFromBlockOrder()
            CardPage.PREVIEW -> model.previewBack()
        }
    }
    if(deleteCard)AlertDialog(onDismissRequest={if(!state.busy)deleteCard=false},title={Text("删除这张卡片？")},
        text={Text("从库中移除这张卡片及其内部角色。已有对话不会被删除；引用此卡的对话将无法继续读取它。历史与原始资源保留。")},
        confirmButton={TextButton(enabled=!state.busy,onClick={deleteCard=false;model.deleteCard(onExit)}){Text("删除")}},
        dismissButton={TextButton(enabled=!state.busy,onClick={deleteCard=false}){Text("取消")}})
    BackHandler(enabled=!WindowInsets.isImeVisible){if(!state.busy || state.page==CardPage.DETAIL)back()}
    Scaffold(
        bottomBar={if(state.page==CardPage.DETAIL)Button(enabled=!state.busy && state.saved!=null,onClick=onInteract,modifier=Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)){Text("开始互动")}},
        containerColor=NovexColors.Canvas,
        topBar={NovexPageTopBar(title=when(state.page){
            CardPage.DETAIL->""
            CardPage.MODULES->if(state.shownDraft?.kind==CardKind.WORLD)"编辑世界" else "编辑角色"
            CardPage.EDIT->"编辑模块"
            CardPage.PICTURES->"图片素材"
            CardPage.BLOCKS->"图文顺序"
            CardPage.PREVIEW->"预览"
        },onBack={if(!state.busy || state.page==CardPage.DETAIL)back()},actions={when(state.page) {
                CardPage.DETAIL->{
                    Box {CardAction(NovexIcons.MoreVert,"更多操作",!state.busy && !files.state.busy && state.saved!=null){more=true}
                        DropdownMenu(expanded=more,onDismissRequest={more=false}){
                            DropdownMenuItem(text={Text("用于已有对话")},onClick={more=false;onExisting(false)})
                            DropdownMenuItem(text={Text("导出")},onClick={more=false;onExport()})
                            if(state.targetId==null || state.targetId==state.saved?.id)DropdownMenuItem(text={Text("删除")},onClick={more=false;deleteCard=true})
                        }}
                    CardAction(NovexIcons.Edit,"编辑卡片",!state.busy && state.saved!=null){model.edit()}
                }
                CardPage.MODULES->{
                    Box {
                        CardAction(NovexIcons.MoreVert,"编辑操作",!state.busy){editMore=true}
                        DropdownMenu(expanded=editMore,onDismissRequest={editMore=false}) {
                            DropdownMenuItem(text={Text("交给 AI")},onClick={editMore=false;model.prepareAi(onManage)})
                            DropdownMenuItem(text={Text("交给已有对话")},onClick={editMore=false;model.prepareAi {onExisting(true)}})
                            DropdownMenuItem(text={Text("改名")},onClick={editMore=false;cardName=state.shownDraft?.name.orEmpty();renameCard=true})
                            DropdownMenuItem(text={Text("默认连续阅读")},onClick={editMore=false;model.setReadingLayout(novex.content.ReadingLayout.CONTINUOUS)})
                            DropdownMenuItem(text={Text("默认模块翻页")},onClick={editMore=false;model.setReadingLayout(novex.content.ReadingLayout.PAGED)})
                            if(state.shownDraft?.appearance?.coverResourceId!=null)DropdownMenuItem(text={Text("清除封面")},onClick={editMore=false;model.clearAppearance(true)})
                            if(state.shownDraft?.appearance?.avatarResourceId!=null)DropdownMenuItem(text={Text("清除头像")},onClick={editMore=false;model.clearAppearance(false)})
                            DropdownMenuItem(text={Text("放弃本次草稿")},onClick={editMore=false;discard=true})
                        }
                    }
                    PreviewButton(!state.busy,model::preview)
                }
                CardPage.EDIT->{
                    Box {
                        CardAction(NovexIcons.MoreVert,"编辑操作",!state.busy){editMore=true}
                        DropdownMenu(expanded=editMore,onDismissRequest={editMore=false}) {
                            DropdownMenuItem(text={Text("排列与嵌套")},onClick={editMore=false;model.arrangeModule()})
                            DropdownMenuItem(text={Text("携带与标签")},onClick={editMore=false;moduleOptions=true})
                            if(state.blockId!=null)DropdownMenuItem(text={Text("移除当前内容块")},onClick={editMore=false;removeChoice="block"})
                            DropdownMenuItem(text={Text("移除整个模块")},onClick={editMore=false;removeChoice="module"})
                        }
                    }
                    Box {
                        CardAction(NovexIcons.Image,"插入图片",!state.busy){imageOptions=true}
                        DropdownMenu(expanded=imageOptions,onDismissRequest={imageOptions=false}) {
                            DropdownMenuItem(text={Text("从文件添加")},onClick={imageOptions=false;imagePicker.launch(arrayOf("image/*"))})
                            DropdownMenuItem(text={Text("使用已有图片")},onClick={imageOptions=false;model.pictures()})
                        }
                    }
                    PreviewButton(!state.busy,model::preview)
                }
                CardPage.PICTURES->TextButton(onClick=model::returnFromPictures,enabled=!state.busy){Text("完成")}
                CardPage.BLOCKS->TextButton(onClick=model::returnFromBlockOrder,enabled=!state.busy){Text("完成")}
                CardPage.PREVIEW->CardAction(NovexIcons.Edit,"继续编辑",!state.busy){model.returnFromPreview()}
            }})}
    ){padding->Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        if(files.state.exportTarget==state.saved?.id && files.state.exportTarget!=null) {
            if(files.state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            files.state.message?.let {Text(it,modifier=Modifier.padding(horizontal=20.dp))}
            files.state.error?.let {Text("导出未完成：$it",color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=20.dp))}
        }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {Column {
            Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=20.dp))
            if(state.draft!=null)Row {
                TextButton(enabled=!state.busy,onClick=model::reloadPreservingInput){Text("保留输入并重新读取")}
                TextButton(enabled=!state.busy,onClick={discard=true}){Text("放弃草稿")}
            }
        }}
        when(state.page) {
            CardPage.DETAIL->state.shownSaved?.let {card->
                CardReading(card,model,onCharacter=model::showTarget,onModule={model.editPart(it)},onImage={model.edit()},onName={model.edit()},onEmpty={model.edit()},onIntroduction={model.edit()})
            }
            CardPage.PREVIEW->state.draft?.let {CardReading(novex.content.ContentTargets.find(it.content,state.previewTargetId?:it.content.id),model,onCharacter=model::previewCharacter,readingScope="draft")}
            CardPage.MODULES->state.shownDraft?.let {card->ModuleTreeEditor(card,model,header={
                CardHero(card,model,editing=true,onImage=model::pictures,onName={cardName=card.name;renameCard=true},onIntroduction=openIntroduction)
            },footer={
                if(card.kind==CardKind.WORLD) {
                    CardDivider();Text("世界中的角色",style=NovexType.SectionTitle,modifier=Modifier.padding(horizontal=16.dp,vertical=12.dp))
                    val placed=card.modules.flattenModules().flatMap {it.characterIds}.toSet()
                    card.internalCharacters.filter {it.id !in placed}.forEach {role->
                        CardRow(headlineContent={Text(role.name)},leadingContent={RoleThumbnail(role,model)},trailingContent={Box {
                            CardAction(NovexIcons.MoreVert,"角色操作",!state.busy){roleMenu=role.id}
                            DropdownMenu(expanded=roleMenu==role.id,onDismissRequest={roleMenu=null}) {
                                DropdownMenuItem(text={Text("放入模块")},onClick={roleMenu=null;placeRole=role.id})
                                DropdownMenuItem(text={Text("移除角色")},onClick={roleMenu=null;removeRole=role.id})
                            }
                        }},modifier=Modifier.clickable(enabled=!state.busy){model.editTarget(role.id)})
                    }
                    Row {
                        TextButton(enabled=!state.busy,onClick={newCharacter=true}){Text("新建角色")}
                        TextButton(enabled=!state.busy,onClick={copyCharacter=true}){Text("从角色库复制")}
                    }
                }
            })}
            CardPage.PICTURES->CardPicturesPage(model)
            CardPage.BLOCKS->BlockOrderPage(model)
            CardPage.EDIT->Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
                BasicTextField(value=state.title,onValueChange=model::changeTitle,singleLine=true,enabled=!state.busy,cursorBrush=androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle=NovexType.PageTitle.copy(color=NovexColors.Text),
                    modifier=Modifier.fillMaxWidth().padding(vertical=14.dp).semantics {contentDescription="模块名称"},
                    decorationBox={inner->if(state.title.isEmpty())Text("名称",color=MaterialTheme.colorScheme.outline);inner()})
                HorizontalDivider(color=NovexColors.Primary,thickness=1.dp)
                if(state.textStart>0 || state.textMore)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick=model::previousTextRange,enabled=!state.busy && state.textStart>0){Text("前一段")}
                    TextButton(onClick=model::nextTextRange,enabled=!state.busy && state.textMore){Text("后一段")}
                }
                key(state.saved?.id,state.moduleId,state.textStart) {
                    val scrollKey="${state.saved?.id}/${state.moduleId}/${state.textStart}"
                    val textScroll=rememberScrollState(model.editorScrollOffsets[scrollKey]?:0)
                    DisposableEffect(scrollKey,textScroll){onDispose {model.editorScrollOffsets[scrollKey]=textScroll.value}}
                    BasicTextField(value=state.text,onValueChange=model::changeText,enabled=!state.busy,cursorBrush=androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle=MaterialTheme.typography.bodyLarge.copy(color=NovexColors.Text),
                        modifier=Modifier.fillMaxWidth().weight(1f).padding(vertical=16.dp).verticalScroll(textScroll).semantics {contentDescription="模块正文"},
                        decorationBox={inner->if(state.text.text.isEmpty())Text("填写内容",color=MaterialTheme.colorScheme.outline);inner()})
                }
                if(state.dirty && state.error!=null)Text("草稿未保存",color=NovexColors.Danger,style=NovexType.Metadata)

            }
        }
    }}
    placeRole?.let {id->AlertDialog(onDismissRequest={placeRole=null},title={Text("角色显示在哪个模块？")},text={Column() {
        val modules=state.shownDraft?.modules?.flattenModules().orEmpty()
        if(modules.isEmpty())Text("先新增一个模块，再选择展示位置。")
        modules.forEach {module->TextButton(enabled=!state.busy,onClick={model.placeCharacter(id,module.id);placeRole=null}){Text(module.name.ifBlank {"未命名模块"})}}
    }},confirmButton={TextButton(onClick={placeRole=null}){Text("取消")}})}
    if(leaveDecision)AlertDialog(onDismissRequest={if(!state.busy)leaveDecision=false},title={Text("保存修改？")},text={Column {Text("保存后更新卡片；不保存会放弃本次作品草稿。");state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},
        confirmButton={TextButton(enabled=!state.busy,onClick={model.save {leaveDecision=false}}){Text("保存")}},
        dismissButton={Row {TextButton(enabled=!state.busy,onClick={leaveDecision=false}){Text("取消")};TextButton(enabled=!state.busy,onClick={model.discardDraft {leaveDecision=false}}){Text("不保存")}}})
    state.recoveryInput?.let {input->AlertDialog(onDismissRequest={},title={Text("未保存的输入")},text={
        androidx.compose.foundation.text.selection.SelectionContainer {Text(input)}
    },confirmButton={TextButton(onClick=model::dismissRecoveryInput){Text("已处理，关闭")}})}
    if(discard)AlertDialog(onDismissRequest={if(!state.busy)discard=false},title={Text("放弃整个作品的草稿？")},text={Column {Text("正式版本保留。世界草稿包含内部角色尚未保存的修改。");state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(enabled=!state.busy,onClick={model.discardDraft{discard=false}}){Text("放弃草稿")}},dismissButton={TextButton(enabled=!state.busy,onClick={discard=false}){Text("取消")}})
    removeRole?.let {id->AlertDialog(onDismissRequest={if(!state.busy)removeRole=null},title={Text("移除世界中的角色？")},text={Column {Text("只修改当前世界，来源角色和历史版本保留。使用该内部角色的对话将无法继续读取它。");state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(enabled=!state.busy,onClick={model.removeCharacter(id){removeRole=null}}){Text("移除")}},dismissButton={TextButton(enabled=!state.busy,onClick={removeRole=null}){Text("取消")}})}
    if(addIntroduction)AlertDialog(onDismissRequest={if(!state.busy)addIntroduction=false},title={Text("简介")},text={
        Column {
            OutlinedTextField(value=introductionText,onValueChange={introductionText=it},placeholder={Text("填写简介")},minLines=3,enabled=!state.busy)
            state.error?.let {Text(it,color=NovexColors.Danger)}
        }
    },confirmButton={TextButton(enabled=!state.busy && introductionText.isNotBlank(),onClick={model.introduction(introductionText){addIntroduction=false}}){Text("应用")}},
        dismissButton={TextButton(enabled=!state.busy,onClick={addIntroduction=false}){Text("取消")}})
    if(renameCard)AlertDialog(onDismissRequest={if(!state.busy)renameCard=false},title={Text("修改名称")},text={Column {OutlinedTextField(cardName,{cardName=it},label={Text("名称")},enabled=!state.busy);state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(enabled=cardName.isNotBlank() && !state.busy,onClick={model.renameCard(cardName){renameCard=false}}){Text("应用")}},dismissButton={TextButton(enabled=!state.busy,onClick={renameCard=false}){Text("取消")}})
    removeChoice?.let {choice->AlertDialog(onDismissRequest={if(!state.busy)removeChoice=null},title={Text(if(choice=="module")"移除整个模块？" else "移除当前内容块？")},text={Column {Text("更改先保留在草稿中，保存后生效。图片素材和历史版本保留。");state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(enabled=!state.busy,onClick={if(choice=="module")model.removeModule{removeChoice=null} else model.removeCurrentBlock{removeChoice=null}}){Text("移除")}},dismissButton={TextButton(enabled=!state.busy,onClick={removeChoice=null}){Text("取消")}})}
    if(newCharacter)NewInternalCharacterDialog(state.busy,state.error,onDismiss={newCharacter=false}){name->model.createCharacter(name){newCharacter=false}}
    if(copyCharacter)CopyInternalCharacterDialog(model,onDismiss={copyCharacter=false}){id->model.copyCharacter(id){copyCharacter=false}}
    if(moduleOptions)state.shownDraft?.modules?.flattenModules()?.find {it.id==state.moduleId}?.let {module->
        ModuleOptionsDialog(module,state.busy,state.error,onDismiss={moduleOptions=false}){tags,use->model.moduleOptions(tags,use){moduleOptions=false}}
    }
}

@Composable private fun PreviewButton(enabled:Boolean,onClick:()->Unit) {
    CardAction(NovexIcons.Visibility,"预览草稿",enabled,onClick)
}
