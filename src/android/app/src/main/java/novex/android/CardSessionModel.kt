package novex.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import novex.content.*
import novex.storage.*

data class CardOpening(val root:String,val target:String,val error:String?=null)

enum class CardPage { DETAIL, MODULES, EDIT, PREVIEW, BLOCKS, PICTURES }
data class CardSession(val page:CardPage=CardPage.DETAIL,val previewReturn:CardPage=CardPage.EDIT,val pictureReturn:CardPage=CardPage.MODULES,val saved:ContentDocument?=null,val draft:CardDraft?=null,
    val moduleId:String?=null,val blockId:String?=null,val title:String="",val text:TextFieldValue=TextFieldValue(),
    val markdownRef:ContentRef?=null,val textStart:Long=0,val textEnd:Long=0,val textMore:Boolean=false,val loadedText:String="",
    val editorEntryTarget:String?=null,val previewTargetId:String?=null,val recoveryInput:String?=null,val dirty:Boolean=false,val busy:Boolean=false,val error:String?=null,val targetId:String?=null) {
    val shownSaved:ContentDocument? get()=saved?.let {ContentTargets.find(it,targetId?:it.id)}
    val shownDraft:ContentDocument? get()=draft?.content?.let {ContentTargets.find(it,targetId?:it.id)}
}

/** 只协调手机编辑会话；内容变更及提交都交给共同编辑入口。 */
class CardSessionModel(application:Application):AndroidViewModel(application) {
    private val store by lazy {CardStore(application.filesDir.toPath().resolve("rewrite-content"))}
    private val pageReader by lazy {TextPages(store.contents)}
    internal val editorScrollOffsets=mutableMapOf<String,Int>()
    internal var arrangementTarget by mutableStateOf<String?>(null)
    fun arrangeModule()=action {flush();arrangementTarget=state.moduleId;state=state.copy(page=CardPage.MODULES)}
    private val readings=mutableMapOf<Pair<String,String>,ReadingMemory>()
    internal fun readingMemory(id:String,source:String)=readings.getOrPut(id to source){ReadingMemory()}
    private val mutex=Mutex()
    private val changes=Channel<Unit>(Channel.CONFLATED)
    private var sequence=0L
    var state by mutableStateOf(CardSession())
        private set
    init {viewModelScope.launch {for(signal in changes){delay(300);while(changes.tryReceive().isSuccess){};try{mutex.withLock{flush()}}catch(failure:Exception){state=state.copy(error=failure.message?:"草稿保存未完成")}}}}
    var opening by mutableStateOf<CardOpening?>(null)
        private set
    fun dismissOpening(){if(!state.busy)opening=null}
    fun open(id:String,targetId:String=id) {
        if(state.busy)return
        opening=CardOpening(id,targetId)
        action(onSuccess={opening=null}) {
            try {
                flush()
                val loaded=withContext(Dispatchers.IO){requireNotNull(store.open(id)){"作品不存在"}.content to CardDrafts(store).read(id)}
                ContentTargets.find(loaded.first,targetId)
                state=CardSession(saved=loaded.first,draft=loaded.second,busy=true,targetId=targetId,recoveryInput=state.recoveryInput)
            } catch(failure:Exception) {
                opening=CardOpening(id,targetId,failure.message?:"作品读取未完成")
                throw failure
            }
        }
    }
    /** Returning from original chat reloads committed content without resetting an active editor. */
    fun refreshSaved() {
        if(state.busy || state.dirty || state.page!=CardPage.DETAIL)return
        val id=state.saved?.id?:return
        action {
            val loaded=withContext(Dispatchers.IO){requireNotNull(store.open(id)){"作品不存在"}}
            val target=state.targetId?:id
            val targetExists=target==id || loaded.content.internalCharacters.any{it.id==target}
            state=state.copy(saved=loaded.content,draft=withContext(Dispatchers.IO){CardDrafts(store).read(id)},
                targetId=if(targetExists)target else id,
                error=if(targetExists)null else "该内部角色已移除，已返回所属世界")
        }
    }
    fun showTarget(id:String)=action {
        flush();ContentTargets.find(requireNotNull(state.saved),id)
        state=state.copy(targetId=id,page=CardPage.DETAIL,moduleId=null,blockId=null,dirty=false)
    }
    private suspend fun beginEditing():CardDraft=withContext(Dispatchers.IO) {
        val draft=CardDrafts(store).begin(requireNotNull(state.saved).id)
        organizeDraft(draft,state.targetId?:draft.content.id)
    }
    private fun organizeDraft(draft:CardDraft,target:String):CardDraft {
        val shown=ContentTargets.find(draft.content,target)
        return if(FolderContents.organize(shown)==shown)draft
        else CardEditor(store).apply(draft.content.id,draft.version,target,EditorCommand.OrganizeFolders)
    }
    fun promoteModule(id:String)=applyComposition(EditorCommand.PromoteModule(id,state.shownDraft?.introductionModule()?.id))
    fun edit()=action {
        val draft=beginEditing()
        val requested=state.targetId?:draft.content.id
        require(requested==draft.content.id || draft.content.internalCharacters.any {it.id==requested}) {"该角色已在草稿中移除，请先返回世界处理草稿"}
        state=state.copy(page=CardPage.MODULES,draft=draft,editorEntryTarget=state.targetId?:draft.content.id,targetId=state.targetId?.takeIf {id->id==draft.content.id || draft.content.internalCharacters.any {it.id==id}}?:draft.content.id)
    }
    fun editTarget(id:String)=action {
        flush();val draft=withContext(Dispatchers.IO){organizeDraft(requireNotNull(state.draft),id)}
        state=state.copy(draft=draft,targetId=id,page=CardPage.MODULES,moduleId=null,blockId=null,dirty=false)
    }
    fun createCharacter(name:String,onSaved:()->Unit={})=changeCharacters(onSaved) {draft->CardCharacters(store).create(draft.content.id,draft.version,name)}
    fun copyCharacter(sourceId:String,onSaved:()->Unit={})=changeCharacters(onSaved) {draft->CardCharacters(store).copy(draft.content.id,draft.version,sourceId)}
    private fun changeCharacters(onSaved:()->Unit,change:(CardDraft)->CardDraft)=action(onSaved) {
        flush();val current=requireNotNull(state.draft)
        require(state.targetId==current.content.id){"请在世界中添加角色"}
        val created=withContext(Dispatchers.IO){change(current)}
        val added=created.content.internalCharacters.single {candidate->current.content.internalCharacters.none {it.id==candidate.id}}
        val next=withContext(Dispatchers.IO){organizeDraft(created,added.id)}
        state=state.copy(draft=next,targetId=added.id,page=CardPage.MODULES,moduleId=null,blockId=null)
    }
    suspend fun sourceCharacters():List<CardSummary> = withContext(Dispatchers.IO){store.list().filter {it.kind==CardKind.CHARACTER}}
    fun moveModule(moduleId:String,beforeId:String?)=action {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.MoveModule(moduleId,beforeId))}
        state=state.copy(draft=next)
    }
    fun placeCharacter(id:String,moduleId:String?)=applyComposition(EditorCommand.PlaceCharacter(id,moduleId))
    fun placeModule(id:String,parentId:String?,beforeId:String?)=applyComposition(EditorCommand.PlaceModule(id,parentId,beforeId))
    fun moduleLayout(id:String,layout:ModuleLayout)=applyComposition(EditorCommand.ModuleLayoutChange(id,layout))
    fun editPart(moduleId:String)=action() {
        val draft=beginEditing()
        val requested=state.targetId?:draft.content.id
        require(requested==draft.content.id || draft.content.internalCharacters.any {it.id==requested}) {"该角色已在草稿中移除，请先返回世界处理草稿"}
        state=state.copy(draft=draft,page=CardPage.MODULES,editorEntryTarget=state.targetId?:draft.content.id)
        // 展示页只进入整卡编辑，定位对应模块；再次点击才进入模块正文。
        focusModule(requireNotNull(state.shownDraft),moduleId,"editor")
    }
    fun introduction(initialText:String?=null,onReady:()->Unit={})=action(onReady) {
        flush()
        if(state.page==CardPage.DETAIL) {
            val draft=beginEditing()
            val target=state.targetId?:draft.content.id
            require(target==draft.content.id || draft.content.internalCharacters.any {it.id==target}) {"该角色已在草稿中移除，请先返回世界处理草稿"}
            state=state.copy(draft=draft,page=CardPage.MODULES,editorEntryTarget=target)
        }
        val existing=requireNotNull(state.shownDraft).introductionModule()
        if(existing!=null)loadModule(existing.id,null)
        else {
            require(!initialText.isNullOrBlank()) {"请填写简介"}
            val draft=requireNotNull(state.draft)
            val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.AddModule("简介"))}
            state=state.copy(draft=next)
            loadModule(requireNotNull(next.position.moduleId),null)
        }
        if(!initialText.isNullOrBlank()){changeText(TextFieldValue(initialText));flush()}
    }
    fun addModule(parentId:String?=null,layout:ModuleLayout=ModuleLayout.VERTICAL)=action {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.AddModule("",parentId,layout))}
        state=state.copy(draft=next)
        loadModule(requireNotNull(next.position.moduleId),null)
    }
    fun addPresentedModule(horizontal:Boolean)=action {
        flush();val draft=requireNotNull(state.draft)
        val command=EditorCommand.AddPresentedModule(horizontal,requireNotNull(state.shownDraft).introductionModule()?.id)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,command)}
        state=state.copy(draft=next)
        loadModule(requireNotNull(next.position.moduleId),null)
    }
    fun module(id:String,block:String?=null)=action {flush();loadModule(id,block)}
    private suspend fun loadModule(id:String,block:String?,start:Long?=null) {
        val draft=requireNotNull(state.draft);val module=requireNotNull(state.shownDraft).modules.flattenModules().single {it.id==id}
        focusModule(requireNotNull(state.shownDraft),id,"editor")
        val position=draft.position
        val same=position.moduleId==id && position.blockId==null
        val offset=start?:if(same)position.textOffset else 0L
        val ref=withContext(Dispatchers.IO){ModuleMarkdown(store).compose(module)}
        val page=withContext(Dispatchers.IO){pageReader.read(ref,offset,if(same && start==null)position.textLength else 4096)}
        val text=page.text
        val selection=if(same && start==null)TextRange(position.selectionStart.coerceIn(0,text.length),position.selectionEnd.coerceIn(0,text.length))else TextRange.Zero
        val nextPosition=EditorPosition(id,null,selection.min,selection.max,textOffset=offset,textLength=maxOf(4096,text.codePointCount(0,text.length)))
        val updated=if(nextPosition==position)draft else withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.Position(nextPosition))}
        state=state.copy(page=CardPage.EDIT,draft=updated,moduleId=id,blockId=null,title=module.name,text=TextFieldValue(text,selection),dirty=false,
            markdownRef=ref,textStart=offset,textEnd=offset+text.codePointCount(0,text.length),textMore=page.next!=null,loadedText=text)
    }

    fun nextTextRange()=action {
        flush()
        if(state.textMore)loadModule(requireNotNull(state.moduleId),state.blockId,state.textEnd)
    }
    fun previousTextRange()=action {
        flush()
        if(state.textStart>0)loadModule(requireNotNull(state.moduleId),state.blockId,(state.textStart-4096).coerceAtLeast(0))
    }

    fun changeTitle(value:String){state=state.copy(title=value,dirty=true);sequence++;changes.trySend(Unit)}
    fun changeText(value:TextFieldValue){state=state.copy(text=value,dirty=true);sequence++;changes.trySend(Unit)}
    private suspend fun flush() {
        if(!state.dirty)return
        val snapshot=state;val generation=sequence;val draft=requireNotNull(snapshot.draft)
        val position=EditorPosition(snapshot.moduleId,snapshot.blockId,snapshot.text.selection.min,snapshot.text.selection.max,textOffset=snapshot.textStart,textLength=maxOf(4096,snapshot.text.text.codePointCount(0,snapshot.text.text.length)))
        val original=requireNotNull(snapshot.shownDraft).modules.flattenModules().single {it.id==snapshot.moduleId}
        val textUnchanged=snapshot.loadedText==snapshot.text.text
        val unchanged=textUnchanged && snapshot.title==original.name
        val nextRef=if(textUnchanged)snapshot.markdownRef else withContext(Dispatchers.IO) {
            ModuleMarkdown(store).replaceRange(requireNotNull(snapshot.markdownRef),snapshot.textStart,snapshot.textEnd,snapshot.text.text)
        }
        val command=if(unchanged)EditorCommand.Position(position)
            else if(textUnchanged)EditorCommand.RenameModule(original.id,snapshot.title)
            else EditorCommand.WriteMarkdown(original.id,snapshot.title,requireNotNull(nextRef),position)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,snapshot.targetId?:draft.content.id,command)}
        state=state.copy(draft=next,blockId=null,markdownRef=nextRef,dirty=sequence!=generation,error=null,
            textEnd=snapshot.textStart+snapshot.text.text.codePointCount(0,snapshot.text.text.length),loadedText=snapshot.text.text)
    }

    fun blockOrder()=action {flush();state=state.copy(page=CardPage.BLOCKS)}
    fun returnFromBlockOrder(){state=state.copy(page=CardPage.EDIT)}
    fun moveBlock(blockId:String,beforeId:String?)=action {
        flush();val draft=requireNotNull(state.draft)
        val command=EditorCommand.MoveBlock(requireNotNull(state.moduleId),blockId,beforeId)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,command)}
        state=state.copy(draft=next)
    }
    private fun focusModule(card:ContentDocument,id:String,source:String) {
        val memory=readingMemory(card.id,source)
        var child:String?=id
        while(child!=null) {
            val parent=card.modules.parentOfModule(child)
            memory.selected[parent?:card.id]=child
            if(parent!=null)memory.expanded[parent]=true
            child=parent
        }
    }
    fun preview()=action {
        flush()
        val card=requireNotNull(state.shownDraft)
        state.moduleId?.takeIf {card.modules.findModule(it)!=null}?.let {focusModule(card,it,"draft")}
        state=state.copy(previewReturn=state.page,previewTargetId=state.targetId,page=CardPage.PREVIEW)
    }
    fun previewCharacter(id:String) {
        ContentTargets.find(requireNotNull(state.draft).content,id)
        state=state.copy(previewTargetId=id)
    }
    fun previewBack() {
        val root=requireNotNull(state.draft).content.id
        if(state.previewTargetId!=state.targetId && state.previewTargetId!=root)state=state.copy(previewTargetId=root)
        else returnFromPreview()
    }
    fun reloadPreservingInput()=action {
        val old=state;val root=requireNotNull(old.saved).id
        val preserved=if(old.dirty)listOf(old.recoveryInput,old.title,old.text.text).filterNotNull().joinToString("\n\n") else old.recoveryInput
        val loaded=withContext(Dispatchers.IO){requireNotNull(store.open(root)).content to CardDrafts(store).read(root)}
        sequence++
        state=CardSession(saved=loaded.first,draft=loaded.second,targetId=root,recoveryInput=preserved,busy=true)
    }
    fun dismissRecoveryInput(){state=state.copy(recoveryInput=null)}
    fun moduleOptions(tags:List<String>,use:ModuleUse?,onSaved:()->Unit={})=action(onSaved) {
        flush();val draft=requireNotNull(state.draft)
        val command=EditorCommand.ModuleOptions(requireNotNull(state.moduleId),tags,use)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,command)}
        state=state.copy(draft=next)
    }
    fun addText()=action {
        flush();val draft=requireNotNull(state.draft);val moduleId=requireNotNull(state.moduleId)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.AddText(moduleId,state.blockId))}
        state=state.copy(draft=next)
        loadModule(moduleId,next.position.blockId)
    }
    fun addImage(uri:android.net.Uri)=action {
        flush();val draft=requireNotNull(state.draft)
        val resource=withContext(Dispatchers.IO){
            val input=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)){"无法读取所选图片"}
            CardImages(store).receive(input)
        }
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.AdoptImage(resource,null,null))}
        state=state.copy(draft=next)
        insertMarkdownImage(resource.id)
        flush()
    }
    private fun insertMarkdownImage(resourceId:String) {
        val input=state.text
        val marker="\n"+ModuleMarkdown(store).marker(resourceId)+"\n"
        val text=input.text.replaceRange(input.selection.min,input.selection.max,marker)
        changeText(TextFieldValue(text,TextRange(input.selection.min+marker.length)))
    }
    fun addPictureResource(uri:android.net.Uri)=action {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO) {
            val input=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)){"无法读取所选图片"}
            val resource=CardImages(store).receive(input)
            CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.AdoptImage(resource,null,null))
        }
        state=state.copy(draft=next)
    }
    fun setCover()=setImageAppearance(true)
    fun setAvatar()=setImageAppearance(false)
    private fun setImageAppearance(cover:Boolean)=action {
        flush();val draft=requireNotNull(state.draft)
        val image=requireNotNull(state.shownDraft).modules.flattenModules().single {it.id==state.moduleId}.blocks.single {it.id==state.blockId} as ContentBlock.Image
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.Appearance(image.resourceId,cover))}
        state=state.copy(draft=next)
    }
    fun pictures()=action {flush();state=state.copy(pictureReturn=state.page,page=CardPage.PICTURES)}
    fun returnFromPictures()=action {
        if(state.pictureReturn==CardPage.EDIT)loadModule(requireNotNull(state.moduleId),state.draft?.position?.blockId)
        else state=state.copy(page=state.pictureReturn)
    }
    fun useOwnedAppearance(resourceId:String,cover:Boolean)=action {
        val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.Appearance(resourceId,cover))}
        state=state.copy(draft=next)
    }
    fun insertOwnedImage(resourceId:String)=action {
        require(state.pictureReturn==CardPage.EDIT){"请先打开要放入图片的模块"}
        require(state.shownDraft?.resources?.any {it.id==resourceId}==true){"图片不属于当前卡片"}
        state=state.copy(page=CardPage.EDIT)
        insertMarkdownImage(resourceId);flush()
    }
    fun clearAppearance(cover:Boolean)=action {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.Appearance(null,cover))}
        state=state.copy(draft=next)
    }
    fun replaceImage(uri:android.net.Uri)=action {
        flush();val draft=requireNotNull(state.draft)
        val moduleId=requireNotNull(state.moduleId);val blockId=requireNotNull(state.blockId)
        val next=withContext(Dispatchers.IO) {
            val input=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)){"无法读取所选图片"}
            CardImages(store).replace(draft.content.id,draft.version,moduleId,blockId,input,state.targetId)
        }
        state=state.copy(draft=next)
    }
    fun removeImageDisplay()=action {
        flush();val draft=requireNotNull(state.draft);val moduleId=requireNotNull(state.moduleId)
        val image=requireNotNull(state.shownDraft).modules.flattenModules().single {it.id==moduleId}.blocks.single {it.id==state.blockId} as ContentBlock.Image
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.RemoveBlock(moduleId,image.id))}
        state=state.copy(draft=next)
        loadModule(moduleId,next.position.blockId)
    }
    fun setReadingLayout(layout:ReadingLayout)=applyComposition(EditorCommand.Layout(layout))
    fun removeResource(id:String,removeUses:Boolean,onSaved:()->Unit={})=applyComposition(EditorCommand.RemoveResource(id,removeUses),onSaved)
    fun removeCharacter(id:String,onSaved:()->Unit={})=applyComposition(EditorCommand.RemoveCharacter(id),onSaved)
    private fun applyComposition(command:EditorCommand,onSaved:()->Unit={})=action(onSaved) {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,command)}
        state=state.copy(draft=next)
    }
    fun prepareAi(onReady:()->Unit)=action {
        flush();val draft=requireNotNull(state.draft)
        val saved=withContext(Dispatchers.IO){CardDrafts(store).commit(draft.content.id,draft.version)}
        state=state.copy(saved=saved.content,draft=null,page=CardPage.DETAIL)
        onReady()
    }
    fun discardDraft(onDiscarded:()->Unit={})=action(onDiscarded) {
        val draft=requireNotNull(state.draft)
        // 明确放弃不先写入待放弃的输入；版本冲突时保留别人更新的草稿。
        val newer=withContext(Dispatchers.IO){
            try {CardDrafts(store).discard(draft.content.id,draft.version);null}
            catch(_:DraftConflict){CardDrafts(store).read(draft.content.id)}
        }
        val saved=withContext(Dispatchers.IO){requireNotNull(store.open(draft.content.id)).content}
        sequence++;state=state.copy(saved=saved,draft=newer,page=CardPage.DETAIL,dirty=false,moduleId=null,blockId=null,text=TextFieldValue(),loadedText="",error=if(newer!=null)"本地输入已放弃，其他更新的草稿仍保留" else null,targetId=state.targetId?.takeIf {it==saved.id || saved.internalCharacters.any {role->role.id==it}}?:saved.id)
    }
    fun renameCard(name:String,onSaved:()->Unit={})=action(onSaved) {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.Rename(name))}
        state=state.copy(draft=next)
    }
    fun deleteCard(onDeleted:()->Unit)=action(onDeleted) {
        val root=requireNotNull(state.saved).id
        require(state.targetId==null || state.targetId==root){"内部角色请在所属世界中移除"}
        withContext(Dispatchers.IO) {
            val saved=requireNotNull(store.open(root)){"卡片不存在"}
            require(saved.content==state.saved){"卡片已更新，请重新打开后删除"}
            store.delete(root,saved.revision)
        }
    }
    fun deleteModule(id:String)=action {
        flush();val draft=requireNotNull(state.draft)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.RemoveModule(id))}
        state=state.copy(draft=next,page=CardPage.MODULES,moduleId=null,blockId=null,dirty=false)
    }
    fun removeModule(onSaved:()->Unit={})=action(onSaved) {
        flush();val draft=requireNotNull(state.draft);val moduleId=requireNotNull(state.moduleId)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.RemoveModule(moduleId))}
        state=state.copy(draft=next,page=CardPage.MODULES,moduleId=null,blockId=null,dirty=false)
    }
    fun removeCurrentBlock(onSaved:()->Unit={})=action {
        flush();val draft=requireNotNull(state.draft);val moduleId=requireNotNull(state.moduleId);val blockId=requireNotNull(state.blockId)
        val next=withContext(Dispatchers.IO){CardEditor(store).apply(draft.content.id,draft.version,state.targetId?:draft.content.id,EditorCommand.RemoveBlock(moduleId,blockId))}
        state=state.copy(draft=next,page=CardPage.MODULES,moduleId=null,blockId=null,dirty=false)
        onSaved()
        loadModule(moduleId,next.position.blockId)
    }
    fun returnFromPreview() {state=state.copy(page=state.previewReturn)}
    fun modules()=action {flush();state=state.copy(page=CardPage.MODULES)}
    fun detail()=action {flush();state=state.copy(page=CardPage.DETAIL)}
    fun requestLeave(onChanged:()->Unit)=action {
        flush()
        val changed=withContext(Dispatchers.IO){
            val saved=requireNotNull(state.saved);val draft=state.draft?.content?:saved
            !CardContentEquality(store).same(FolderContents.tree(saved),FolderContents.tree(draft))
        }
        if(changed)onChanged() else state=state.copy(page=CardPage.DETAIL,moduleId=null,blockId=null)
    }
    fun save(onSaved:()->Unit={})=action(onSaved) {
        flush();val draft=requireNotNull(state.draft)
        val saved=withContext(Dispatchers.IO){CardDrafts(store).commit(draft.content.id,draft.version)}
        state=state.copy(saved=saved.content,draft=null,page=CardPage.DETAIL)
    }
    suspend fun snapshot(rootId:String,targetId:String):ContentDocument=withContext(Dispatchers.IO) {
        val content=CardDrafts(store).read(rootId)?.content?:requireNotNull(store.open(rootId)){"作品不存在"}.content
        ContentTargets.find(content,targetId)
    }
    suspend fun text(ref:ContentRef):String=withContext(Dispatchers.IO){read(ref)}
    suspend fun textPage(ref:ContentRef,start:Long,count:Int):TextPage=withContext(Dispatchers.IO){pageReader.read(ref,start,count)}
    suspend fun image(ref:ContentRef,maxEdge:Int=1800):android.graphics.Bitmap?=withContext(Dispatchers.IO) {
        require(maxEdge in 1..4096){"图片读取尺寸无效"}
        val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
        store.contents.open(ref).use {android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
        var sample=1
        while((bounds.outWidth.toLong()+sample-1)/sample>maxEdge || (bounds.outHeight.toLong()+sample-1)/sample>maxEdge)sample*=2
        store.contents.open(ref).use {android.graphics.BitmapFactory.decodeStream(it,null,android.graphics.BitmapFactory.Options().apply {inSampleSize=sample})}
    }
    private fun read(ref:ContentRef)=store.contents.open(ref).bufferedReader(Charsets.UTF_8).use {it.readText()}
    private fun action(onSuccess:()->Unit={},block:suspend ()->Unit) {
        if(state.busy)return
        state=state.copy(busy=true,error=null)
        viewModelScope.launch {
            try{mutex.withLock{block()};onSuccess()}
            catch(failure:Exception){state=state.copy(error=failure.message?:"操作未完成，编辑内容已保留")}
            finally{state=state.copy(busy=false)}
        }
    }
}
