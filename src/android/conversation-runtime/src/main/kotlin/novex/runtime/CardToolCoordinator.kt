package novex.runtime

import novex.storage.*
import novex.content.ContentTargets
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

enum class ToolPermission { READ_ONLY, APPROVAL, FREE }
data class ManagementTarget(val rootId:String,val targetId:String=rootId)
data class CardToolPolicy(val targets:Set<ManagementTarget>,val permission:ToolPermission=ToolPermission.FREE,val readTargets:Set<ManagementTarget> = targets)
sealed interface CardToolEdit {
    data class Create(val kind:novex.content.CardKind,val name:String):CardToolEdit
    data class ConversationImage(val imageId:String,val moduleId:String?,val afterBlockId:String?):CardToolEdit
    data class Shared(val command:EditorCommand,val canonical:String):CardToolEdit
    data class RemoveModule(val moduleId:String):CardToolEdit
    data class RemoveBlock(val moduleId:String,val blockId:String):CardToolEdit
    data class Options(val moduleId:String,val tags:List<String>,val use:novex.content.ModuleUse?):CardToolEdit
    data class Rename(val name:String):CardToolEdit
    data class AddModule(val name:String):CardToolEdit
    data class Appearance(val resourceId:String,val cover:Boolean):CardToolEdit
    data class InsertOwnedImage(val moduleId:String,val resourceId:String,val afterBlockId:String?):CardToolEdit
    data class ReplaceBlockImage(val moduleId:String,val blockId:String,val resourceId:String):CardToolEdit
    data class MoveModule(val moduleId:String,val beforeId:String?):CardToolEdit
    data class MoveBlock(val moduleId:String,val blockId:String,val beforeId:String?):CardToolEdit
    data class ReplaceTextRange(val moduleId:String,val blockId:String,val content:novex.content.ContentRef,val start:Long,val end:Long,val text:String):CardToolEdit
    data class WriteText(val moduleId:String,val blockId:String?,val name:String,val text:String):CardToolEdit
}
data class CardToolRequest(val chatId:String,val callId:String,val target:ManagementTarget,val draftVersion:String,val edit:CardToolEdit)
sealed interface CardToolResult {
    data class Read(val content:String):CardToolResult { init { JSONObject(content) } }
    data object Denied:CardToolResult
    data object AwaitingApproval:CardToolResult
    data object Unconfirmed:CardToolResult
    data class CheckpointSaved(val conversationId:String,val checkpointId:String):CardToolResult
    data class StateSaved(val conversationId:String,val eventId:String):CardToolResult
    data class Registered(val conversationId:String,val registrationId:String):CardToolResult
    data class Saved(val rootId:String,val targetId:String,val revision:String):CardToolResult
    data class Stopped(val preservedDraft:String?):CardToolResult
    data class Failed(val reason:String,val preservedDraft:String?=null):CardToolResult
}
class ToolStop { private val stopped=AtomicBoolean();fun stop(){stopped.set(true)};internal fun isStopped()=stopped.get() }

/** 授权与执行适配；通过共同编辑命令执行实际保存，单独维护审批与幂等记录。 */
class CardToolCoordinator(private val store:CardStore,private val journal:TurnJournal,private val conversationImage:((String)->java.io.InputStream)?=null,private val onCreated:((ManagementTarget)->Unit)?=null) {
    private var retainEdit=false
    private var activeTarget:ManagementTarget?=null
    private var activeLease:CardEditLease?=null
    /** Hosts keep the active target occupied until their tool loop ends or is cancelled. */
    suspend fun <T> editingSession(action:suspend ()->T):T {
        beginEditing()
        return try{action()}finally{endEditing()}
    }
    internal fun beginEditing(){check(!retainEdit);retainEdit=true}
    internal fun endEditing(){try{activeLease?.close()}finally{activeLease=null;activeTarget=null;retainEdit=false}}
    private inline fun <T> editing(target:ManagementTarget,owner:String,action:(CardEditLease)->T):T {
        if(!retainEdit)return store.acquireEdit(target.rootId,target.targetId,owner).use(action)
        if(activeTarget!=target) {
            activeLease?.close();activeLease=null;activeTarget=null
            activeLease=store.acquireEdit(target.rootId,target.targetId,owner);activeTarget=target
        }
        return action(requireNotNull(activeLease))
    }
    internal fun completed(chatId:String,call:novex.model.PendingTool):CardToolResult? {
        val record=journal.read(chatId,call.id)?:return null
        val raw=journal.text(record.input)
        val input=JSONObject(raw)
        val request=if(input.has("root"))CardToolProtocol.parse(chatId,call) else null
        if(request!=null)require(raw==encode(request)){"工具编号已对应其他操作"}
        else {
            require(call.name in setOf("register_controls","update_playthrough_state","save_conversation_checkpoint"))
            val chat=input.getString("chat");val message=input.getString("message")
            require(chatId=="${chat.length}:$chat${message.length}:$message" && input.getString("arguments")==call.arguments){"工具编号已对应其他操作"}
        }
        if(record.state==TurnState.RUNNING && request!=null)
            return journal.exclusiveExecution(chatId,call.id){recorded(request,requireNotNull(journal.read(chatId,call.id)))}?:CardToolResult.Unconfirmed
        return record.outcome?.let { ToolResultCodec.decode(JSONObject(journal.text(it))) }?.also {if(request!=null)grantCreated(request,it)}
    }
    fun availableTools(policy:CardToolPolicy):List<String> = CardToolProtocol.definitions(policy).map {it.name}
    fun submit(request:CardToolRequest,policy:CardToolPolicy,stop:ToolStop=ToolStop()):CardToolResult =
        journal.exclusiveExecution(request.chatId,request.callId){submitExclusive(request,policy,stop)}?:CardToolResult.Unconfirmed
    private fun submitExclusive(request:CardToolRequest,policy:CardToolPolicy,stop:ToolStop):CardToolResult {
        if(!allowed(request,policy))return CardToolResult.Denied
        val record=journal.enqueue(request.chatId,request.callId,encode(request))
        if(record.state!=TurnState.QUEUED)return recorded(request,record)
        return if(policy.permission==ToolPermission.APPROVAL)CardToolResult.AwaitingApproval else execute(request,stop)
    }
    /** 仅供用户选择入口调用，不作为模型工具暴露。 */
    fun select(request:CardToolRequest,selected:Boolean) {
        val record=requireNotNull(journal.read(request.chatId,request.callId))
        require(journal.text(record.input)==encode(request)){"选择的操作内容不一致"}
        journal.select(request.chatId,request.callId,selected)
    }
    /** 明确确认入口；执行前再次核对当前权限与管理范围。 */
    fun confirm(request:CardToolRequest,policy:CardToolPolicy,stop:ToolStop=ToolStop()):CardToolResult =
        journal.exclusiveExecution(request.chatId,request.callId){confirmExclusive(request,policy,stop)}?:CardToolResult.Unconfirmed
    private fun confirmExclusive(request:CardToolRequest,policy:CardToolPolicy,stop:ToolStop):CardToolResult {
        val record=requireNotNull(journal.read(request.chatId,request.callId))
        require(journal.text(record.input)==encode(request)){"待确认操作已改变"}
        if(record.state!=TurnState.QUEUED)return recorded(request,record)
        if(!allowed(request,policy))return rejectExclusive(request)
        if(policy.permission==ToolPermission.APPROVAL && !record.selected)return CardToolResult.AwaitingApproval
        return execute(request,stop)
    }
    /** 用户拒绝不依赖当前管理权限；已经开始的操作不能冒称已取消。 */
    fun reject(request:CardToolRequest):CardToolResult =
        journal.exclusiveExecution(request.chatId,request.callId){rejectExclusive(request)}?:CardToolResult.Unconfirmed
    private fun rejectExclusive(request:CardToolRequest):CardToolResult {
        val record=journal.finishQueued(request.chatId,request.callId,encode(request),JSONObject().put("kind","denied"))
        if(record.state==TurnState.RUNNING)return CardToolResult.Unconfirmed
        return recorded(request,record)
    }
    private fun allowed(request:CardToolRequest,policy:CardToolPolicy):Boolean {
        if(policy.permission==ToolPermission.READ_ONLY)return false
        if(request.edit is CardToolEdit.Create)return true
        if(request.target !in policy.targets)return false
        val command=(request.edit as? CardToolEdit.Shared)?.command
        return command !is EditorCommand.CopyCharacter || ManagementTarget(command.sourceId) in policy.readTargets
    }
    private fun execute(request:CardToolRequest,stop:ToolStop):CardToolResult {
        journal.claim(request.chatId,request.callId)?:return recorded(request,requireNotNull(journal.read(request.chatId,request.callId)))
        val creation=request.edit as? CardToolEdit.Create
        if(creation!=null) {
            if(stop.isStopped())return finish(request,CardToolResult.Stopped(null))
            val operation="create:${request.target.rootId}"
            try {
                val created=CardCreation(store).create(request.target.rootId,creation.name,creation.kind,ChangeSource.AI,operation)
                return finish(request,CardToolResult.Saved(request.target.rootId,request.target.rootId,created.revision))
            }catch(failure:Exception){
                val actual=store.history(request.target.rootId,operation)
                if(actual!=null)return finish(request,CardToolResult.Saved(request.target.rootId,request.target.rootId,actual.revision))
                return finish(request,CardToolResult.Failed(failure.message?:"创建未完成"))
            }
        }
        var updated:CardDraft?=null
        var saved:SavedCard?=null
        try {
            if(stop.isStopped())return finish(request,CardToolResult.Stopped(null))
            editing(request.target,"tool:${request.callId}") { lease ->
                if(stop.isStopped())return finish(request,CardToolResult.Stopped(null))
                val command=when(val edit=request.edit) {
                    is CardToolEdit.Create->error("创建操作不属于已有卡编辑")
                    is CardToolEdit.ConversationImage->EditorCommand.AdoptImage(CardImages(store).receive(requireNotNull(conversationImage){"当前入口没有对话图片来源"}(edit.imageId)),edit.moduleId,edit.afterBlockId)
                    is CardToolEdit.Shared->edit.command
                    is CardToolEdit.RemoveModule->EditorCommand.RemoveModule(edit.moduleId)
                    is CardToolEdit.RemoveBlock->EditorCommand.RemoveBlock(edit.moduleId,edit.blockId)
                    is CardToolEdit.Options->EditorCommand.ModuleOptions(edit.moduleId,edit.tags,edit.use)
                    is CardToolEdit.Rename->EditorCommand.Rename(edit.name)
                    is CardToolEdit.AddModule->EditorCommand.AddModule(edit.name)
                    is CardToolEdit.Appearance->EditorCommand.Appearance(edit.resourceId,edit.cover)
                    is CardToolEdit.InsertOwnedImage->EditorCommand.InsertOwnedImage(edit.moduleId,edit.resourceId,edit.afterBlockId)
                    is CardToolEdit.ReplaceBlockImage->EditorCommand.ReplaceBlockImage(edit.moduleId,edit.blockId,edit.resourceId)
                    is CardToolEdit.MoveModule->EditorCommand.MoveModule(edit.moduleId,edit.beforeId)
                    is CardToolEdit.MoveBlock->EditorCommand.MoveBlock(edit.moduleId,edit.blockId,edit.beforeId)
                    is CardToolEdit.ReplaceTextRange->EditorCommand.ReplaceTextRange(edit.moduleId,edit.blockId,edit.content,edit.start,edit.end,edit.text,EditorPosition())
                    is CardToolEdit.WriteText->EditorCommand.WriteText(edit.moduleId,edit.blockId,edit.name,edit.text,EditorPosition())
                }
                val expected=if(request.draftVersion.startsWith("saved:")){
                    val actual=requireNotNull(store.open(request.target.rootId)){"卡片不存在"}
                    if(actual.revision!=request.draftVersion.removePrefix("saved:"))throw DraftConflict()
                    val draft=CardDrafts(store).begin(request.target.rootId)
                    if(draft.baseRevision!=actual.revision || draft.content!=actual.content)throw DraftConflict()
                    draft.version
                } else request.draftVersion
                val beforeEdit=requireNotNull(CardDrafts(store).read(request.target.rootId))
                updated=CardEditor(store).apply(request.target.rootId,expected,request.target.targetId,command,ChangeSource.AI,lease)
                journal.prepared(request.chatId,request.callId,JSONObject().put("commit",updated!!.version))
                if(stop.isStopped())return finish(request,CardToolResult.Stopped(updated!!.version))
                saved=CardDrafts(store).commitTarget(request.target.rootId,updated!!.version,request.target.targetId,beforeEdit,lease)
            }
            return finish(request,CardToolResult.Saved(request.target.rootId,request.target.targetId,saved!!.revision))
        } catch(failure:Exception) {
            // 原子提交之后记录结果前若发生异常，以真实修订核对是否已保存。
            val actual=saved?:updated?.let { store.history(request.target.rootId,it.version) }
            if(actual!=null)return finish(request,CardToolResult.Saved(request.target.rootId,request.target.targetId,actual.revision))
            return finish(request,CardToolResult.Failed(failure.message?:"编辑未完成，保留已有内容",updated?.version))
        }
    }
    private fun recorded(request:CardToolRequest,record:StoredTurn):CardToolResult {
        if(record.state==TurnState.FINISHED)return ToolResultCodec.decode(JSONObject(journal.text(record.outcome!!))).also {grantCreated(request,it)}
        if(request.edit is CardToolEdit.Create)store.history(request.target.rootId,"create:${request.target.rootId}")?.let {return finish(request,CardToolResult.Saved(request.target.rootId,request.target.rootId,it.revision))}
        val commit=record.trace?.let { JSONObject(journal.text(it)).optString("commit") }?.takeIf { it.isNotBlank() }
        val saved=commit?.let { store.history(request.target.rootId,it) }
        if(saved!=null){ContentTargets.find(saved.content,request.target.targetId);return finish(request,CardToolResult.Saved(request.target.rootId,request.target.targetId,saved.revision))}
        if(record.state==TurnState.RUNNING) {
            val preserved=CardDrafts(store).read(request.target.rootId)?.version
            return finish(request,CardToolResult.Failed("上次编辑中断，未发现已保存回执；保留现有草稿。请先读取最新内容再决定下一步，不重复执行旧操作。",preserved))
        }
        return CardToolResult.Unconfirmed
    }
    private fun finish(request:CardToolRequest,result:CardToolResult):CardToolResult {
        val value=when(result) {
            is CardToolResult.Saved->JSONObject().put("kind","saved").put("root",result.rootId).put("target",result.targetId).put("revision",result.revision)
            is CardToolResult.Stopped->JSONObject().put("kind","stopped").put("draft",result.preservedDraft?:JSONObject.NULL)
            is CardToolResult.Failed->JSONObject().put("kind","failed").put("reason",result.reason).put("draft",result.preservedDraft?:JSONObject.NULL)
            else->error("结果不是已执行操作的结束状态")
        }
        journal.finish(request.chatId,request.callId,value);grantCreated(request,result);return result
    }
    private fun grantCreated(request:CardToolRequest,result:CardToolResult) {
        if(result !is CardToolResult.Saved)return
        if(request.edit is CardToolEdit.Create)onCreated?.invoke(request.target)
        val command=(request.edit as? CardToolEdit.Shared)?.command
        if(command is EditorCommand.AddCharacter || command is EditorCommand.CopyCharacter) {
            val saved=requireNotNull(store.history(result.rootId,result.revision))
            val role=ContentTargets.find(saved.content,request.target.targetId).internalCharacters.last()
            onCreated?.invoke(ManagementTarget(result.rootId,role.id))
        }
    }
    private fun encode(request:CardToolRequest):String {
        val edit=when(val command=request.edit) {
            is CardToolEdit.Create->JSONObject().put("kind","create").put("cardKind",command.kind.name).put("name",command.name)
            is CardToolEdit.ConversationImage->JSONObject().put("kind","conversation_image").put("image",command.imageId).put("module",command.moduleId?:JSONObject.NULL).put("after",command.afterBlockId?:JSONObject.NULL)
            is CardToolEdit.Shared->JSONObject(command.canonical)
            is CardToolEdit.RemoveModule->JSONObject().put("kind","remove_module").put("module",command.moduleId)
            is CardToolEdit.RemoveBlock->JSONObject().put("kind","remove_block").put("module",command.moduleId).put("block",command.blockId)
            is CardToolEdit.Options->JSONObject().put("kind","options").put("module",command.moduleId).put("tags",org.json.JSONArray(command.tags)).put("rule",ModuleOptionsProtocol.encode(command.use))
            is CardToolEdit.Rename->JSONObject().put("kind","rename").put("name",command.name)
            is CardToolEdit.Appearance->JSONObject().put("kind","appearance").put("resource",command.resourceId).put("cover",command.cover)
            is CardToolEdit.InsertOwnedImage->JSONObject().put("kind","insert_image").put("module",command.moduleId).put("resource",command.resourceId).put("after",command.afterBlockId?:JSONObject.NULL)
            is CardToolEdit.ReplaceBlockImage->JSONObject().put("kind","block_image").put("module",command.moduleId).put("block",command.blockId).put("resource",command.resourceId)
            is CardToolEdit.MoveModule->JSONObject().put("kind","move_module").put("module",command.moduleId).put("before",command.beforeId?:JSONObject.NULL)
            is CardToolEdit.MoveBlock->JSONObject().put("kind","move_block").put("module",command.moduleId).put("block",command.blockId).put("before",command.beforeId?:JSONObject.NULL)
            is CardToolEdit.AddModule->JSONObject().put("kind","add_module").put("name",command.name)
            is CardToolEdit.ReplaceTextRange->JSONObject().put("kind","text_range").put("module",command.moduleId).put("block",command.blockId).put("content",command.content.value).put("start",command.start).put("end",command.end).put("text",command.text)
            is CardToolEdit.WriteText->JSONObject().put("kind","text").put("module",command.moduleId).put("block",command.blockId?:JSONObject.NULL).put("name",command.name).put("text",command.text)
        }
        return JSONObject().put("root",request.target.rootId).put("target",request.target.targetId).put("version",request.draftVersion).put("edit",edit).toString()
    }
}
