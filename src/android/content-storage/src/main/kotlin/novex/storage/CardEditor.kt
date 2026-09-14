package novex.storage

import novex.content.*
import java.io.ByteArrayInputStream
import java.util.UUID

sealed interface EditorCommand {
    data object OrganizeFolders:EditorCommand
    data class PromoteModule(val moduleId:String,val introductionId:String?=null):EditorCommand
    /** 在展示槽中新增：只在用户新增时将连续内容归入主要，且一次更新整个草稿。 */
    data class AddPresentedModule(val horizontal:Boolean,val introductionId:String?=null):EditorCommand
    data class WriteMarkdownText(val moduleId:String,val name:String,val text:String):EditorCommand
    data class WriteMarkdown(val moduleId:String,val name:String,val content:ContentRef,val position:EditorPosition):EditorCommand
    data class ReplaceImage(val resource:CardResource,val moduleId:String,val blockId:String):EditorCommand
    data class AdoptImage(val resource:CardResource,val moduleId:String?,val afterBlockId:String?):EditorCommand
    data class SetCaption(val moduleId:String,val blockId:String,val text:String):EditorCommand
    data class Layout(val layout:ReadingLayout):EditorCommand
    data class RemoveResource(val resourceId:String,val removeUses:Boolean):EditorCommand
    data class PlaceCharacter(val characterId:String,val moduleId:String?):EditorCommand
    data class AddCharacter(val name:String):EditorCommand
    data class CopyCharacter(val sourceId:String):EditorCommand
    data class RemoveCharacter(val characterId:String):EditorCommand
    data class RenameModule(val moduleId:String,val name:String):EditorCommand
    data class Rename(val name:String):EditorCommand
    data class Appearance(val resourceId:String?,val cover:Boolean):EditorCommand
    data class InsertOwnedImage(val moduleId:String,val resourceId:String,val afterBlockId:String?):EditorCommand
    data class ReplaceBlockImage(val moduleId:String,val blockId:String,val resourceId:String):EditorCommand
    data class RemoveModule(val moduleId:String):EditorCommand
    data class RemoveBlock(val moduleId:String,val blockId:String):EditorCommand
    data class Position(val value:EditorPosition):EditorCommand
    data class AddModule(val name:String,val parentId:String?=null,val layout:ModuleLayout=ModuleLayout.VERTICAL):EditorCommand
    data class PlaceModule(val moduleId:String,val parentId:String?,val beforeId:String?):EditorCommand
    data class ModuleLayoutChange(val moduleId:String,val layout:ModuleLayout):EditorCommand
    data class MoveBlock(val moduleId:String,val blockId:String,val beforeBlockId:String?):EditorCommand
    data class MoveModule(val moduleId:String,val beforeId:String?):EditorCommand
    data class ModuleOptions(val moduleId:String,val tags:List<String>,val use:ModuleUse?):EditorCommand
    data class AddText(val moduleId:String,val afterBlockId:String?):EditorCommand
    data class WriteText(val moduleId:String,val blockId:String?,val moduleName:String,val text:String,val position:EditorPosition):EditorCommand
    data class ReplaceTextRange(val moduleId:String,val blockId:String,val expectedContent:ContentRef,val start:Long,val end:Long,val text:String,val position:EditorPosition,val moduleName:String?=null):EditorCommand
    data class WriteCaption(val moduleId:String,val blockId:String,val moduleName:String,val text:String,val position:EditorPosition):EditorCommand
}

/** 与页面及模型协议无关的共同编辑入口；调用方负责工具权限和编辑占用。 */
class CardEditor(private val store:CardStore) {
    private val drafts=CardDrafts(store)
    fun apply(cardId:String,expectedVersion:String,targetId:String,command:EditorCommand,source:ChangeSource=ChangeSource.HUMAN,lease:CardEditLease?=null):CardDraft {
        val current=requireNotNull(drafts.read(cardId)){"草稿不存在"}
        if(current.version!=expectedVersion)throw DraftConflict()
        var card=ContentTargets.find(current.content,targetId)
        var position=current.position
        fun module(id:String)=requireNotNull(card.modules.flattenModules().find { it.id==id }) { "目标模块不存在或不属于当前卡片" }
        when(command) {
            EditorCommand.OrganizeFolders->Unit
            is EditorCommand.PromoteModule->{
                val moving=module(command.moduleId)
                if(card.appearance.readingLayout==ReadingLayout.CONTINUOUS) {
                    val remaining=card.modules.removeModule(moving.id)
                    val body=remaining.filterNot {it.id==command.introductionId}
                    val main=ContentModule(id(),"主要",emptyList(),children=body)
                    card=card.copy(modules=remaining.filter {it.id==command.introductionId}+main+moving,
                        appearance=card.appearance.copy(readingLayout=ReadingLayout.PAGED))
                } else card=ContentChanges.apply(card,ContentChange.PlaceModule(moving.id,null,null))
            }
            is EditorCommand.AddPresentedModule->{
                command.introductionId?.let {id->require(card.modules.any {it.id==id}){"简介模块已变化"}}
                val body=card.modules.filterNot {it.id==command.introductionId}
                val mainSlot=card.appearance.readingLayout==ReadingLayout.CONTINUOUS || body.isEmpty()
                val added=ContentModule(id(),"",emptyList())
                if(mainSlot && command.horizontal) {
                    val main=ContentModule(id(),"主要",emptyList(),children=body)
                    card=card.copy(modules=card.modules.filter {it.id==command.introductionId}+main+added,
                        appearance=card.appearance.copy(readingLayout=ReadingLayout.PAGED))
                } else if(mainSlot && card.appearance.readingLayout==ReadingLayout.PAGED) {
                    val main=ContentModule(id(),"主要",emptyList(),children=listOf(added))
                    card=card.copy(modules=card.modules+main)
                } else {
                    require(mainSlot || command.horizontal){"请指定新增模块所属的横向分组"}
                    card=ContentChanges.apply(card,ContentChange.AddModule(added))
                }
                position=EditorPosition(added.id)
            }
            is EditorCommand.WriteMarkdownText->{
                val original=module(command.moduleId)
                val blocks=ModuleMarkdown(store).parse(card,original,text(command.text))
                card=ContentChanges.apply(card,ContentChange.ReplaceModule(original.copy(name=command.name,blocks=blocks)))
                position=EditorPosition(original.id)
            }
            is EditorCommand.WriteMarkdown->{
                val original=module(command.moduleId)
                val blocks=ModuleMarkdown(store).parse(card,original,command.content)
                card=ContentChanges.apply(card,ContentChange.ReplaceModule(original.copy(name=command.name,blocks=blocks)))
                position=command.position.copy(moduleId=original.id,blockId=null)
            }
            is EditorCommand.ReplaceImage->{
                val block=requireNotNull(module(command.moduleId).blocks.find {it.id==command.blockId} as? ContentBlock.Image){"图片块不存在"}
                require(command.resource.mediaType.startsWith("image/")){"资源不是图片"}
                card=ContentChanges.apply(card,ContentChange.PutResource(command.resource))
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Replace(block.id,block.copy(resourceId=command.resource.id)))))
                position=EditorPosition(command.moduleId,block.id)
            }
            is EditorCommand.AdoptImage->{
                card=ContentChanges.apply(card,ContentChange.PutResource(command.resource))
                command.moduleId?.let {moduleId->
                    val original=module(moduleId)
                    val after=command.afterBlockId?.let {id->original.blocks.indexOfFirst {it.id==id}.also {require(it>=0){"图片插入位置不存在"}}}
                    val block=ContentBlock.Image(id(),command.resource.id)
                    card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Insert(moduleId,block,after?.let {original.blocks.getOrNull(it+1)?.id}))))
                    position=EditorPosition(moduleId,block.id)
                }
            }
            is EditorCommand.SetCaption->{
                val original=module(command.moduleId)
                val image=requireNotNull(original.blocks.find {it.id==command.blockId} as? ContentBlock.Image){"图片块不存在"}
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Replace(image.id,image.copy(caption=text(command.text))))))
            }
            is EditorCommand.PlaceCharacter,is EditorCommand.Layout,is EditorCommand.RemoveResource,is EditorCommand.AddCharacter,is EditorCommand.CopyCharacter,is EditorCommand.RemoveCharacter->{
                val previouslyInTarget=(listOf(card)+card.internalCharacters).any {part->part.modules.flattenModules().any {it.id==position.moduleId}}
                card=CardComposition(store).apply(card,command)
                if(previouslyInTarget && (listOf(card)+card.internalCharacters).none {part->part.modules.flattenModules().any {it.id==position.moduleId}})position=EditorPosition()
            }
            is EditorCommand.MoveBlock->{
                val currentModule=module(command.moduleId)
                require(currentModule.blocks.any {it.id==command.blockId}){"内容块不属于目标模块"}
                command.beforeBlockId?.let {before->require(currentModule.blocks.any {it.id==before}){"定位内容块不属于目标模块"}}
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Move(command.blockId,command.moduleId,command.beforeBlockId))))
            }
            is EditorCommand.PlaceModule->{
                card=ContentChanges.apply(card,ContentChange.PlaceModule(command.moduleId,command.parentId,command.beforeId))
            }
            is EditorCommand.ModuleLayoutChange->{
                card=ContentChanges.apply(card,ContentChange.SetModuleLayout(command.moduleId,command.layout))
            }
            is EditorCommand.MoveModule->{
                module(command.moduleId);command.beforeId?.let {module(it)}
                card=ContentChanges.apply(card,ContentChange.MoveModule(command.moduleId,command.beforeId))
            }
            is EditorCommand.ModuleOptions->{
                val original=module(command.moduleId)
                card=ContentChanges.apply(card,ContentChange.ReplaceModule(original.copy(tags=command.tags.toList(),use=command.use)))
            }
            is EditorCommand.RenameModule->{card=ContentChanges.apply(card,ContentChange.ReplaceModule(module(command.moduleId).copy(name=command.name)))}
            is EditorCommand.Rename->{require(command.name.isNotBlank()){ "名称不能为空" };card=card.copy(name=command.name.trim())}
            is EditorCommand.RemoveModule->{
                val removed=module(command.moduleId)
                val removedIds=(listOf(removed)+removed.children.flattenModules()).map {it.id}.toSet()
                card=ContentChanges.apply(card,ContentChange.RemoveModule(command.moduleId))
                if(position.moduleId in removedIds)position=EditorPosition()
            }
            is EditorCommand.Appearance->{
                require(command.resourceId==null || card.resources.any {it.id==command.resourceId && it.mediaType.startsWith("image/")}){"图片资源不属于目标卡或类型不符"}
                card=ContentChanges.apply(card,ContentChange.SetAppearance(
                    if(command.cover)card.appearance.copy(coverResourceId=command.resourceId) else card.appearance.copy(avatarResourceId=command.resourceId)))
            }
            is EditorCommand.InsertOwnedImage->{
                val original=module(command.moduleId)
                require(card.resources.any {it.id==command.resourceId && it.mediaType.startsWith("image/")}){"图片资源不属于目标卡或类型不符"}
                val index=command.afterBlockId?.let {after->original.blocks.indexOfFirst {it.id==after}.also {require(it>=0){"定位内容块不属于目标模块"}}}
                val block=ContentBlock.Image(id(),command.resourceId)
                val before=index?.let {original.blocks.getOrNull(it+1)?.id}
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Insert(original.id,block,before))))
                position=EditorPosition(original.id,block.id)
            }
            is EditorCommand.ReplaceBlockImage->{
                val block=requireNotNull(module(command.moduleId).blocks.find {it.id==command.blockId} as? ContentBlock.Image){"图片块不存在"}
                require(card.resources.any {it.id==command.resourceId && it.mediaType.startsWith("image/")}){"图片资源不属于目标卡或类型不符"}
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Replace(block.id,block.copy(resourceId=command.resourceId)))))
            }
            is EditorCommand.RemoveBlock->{
                val original=module(command.moduleId)
                require(original.blocks.any { it.id==command.blockId }){"内容块不属于目标模块"}
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Remove(command.blockId))))
                position=EditorPosition(original.id,module(original.id).blocks.firstOrNull()?.id)
            }
            is EditorCommand.Position->{
                command.value.moduleId?.let { module(it) }
                position=command.value
            }
            is EditorCommand.AddModule->{
                val next=ContentModule(id(),command.name,emptyList(),layout=command.layout)
                card=ContentChanges.apply(card,ContentChange.AddModule(next,parentId=command.parentId));position=EditorPosition(next.id)
            }
            is EditorCommand.AddText->{
                val currentModule=module(command.moduleId)
                val at=command.afterBlockId?.let { block -> currentModule.blocks.indexOfFirst { it.id==block }.also { require(it>=0){"定位内容块不存在"} } }
                val block=ContentBlock.Text(id(),text(""))
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Insert(currentModule.id,block,at?.let { currentModule.blocks.getOrNull(it+1)?.id }))))
                position=EditorPosition(currentModule.id,block.id)
            }
            is EditorCommand.ReplaceTextRange->{
                val currentModule=module(command.moduleId)
                val block=requireNotNull(currentModule.blocks.find {it.id==command.blockId} as? ContentBlock.Text){"文字块不存在"}
                require(block.content==command.expectedContent){"文字版本已变化，请重新读取后修改"}
                val updated=TextRangeEdits(store.contents).replace(block.content,command.start,command.end,command.text)
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Replace(block.id,block.copy(content=updated)))))
                command.moduleName?.let {name->card=ContentChanges.apply(card,ContentChange.ReplaceModule(module(currentModule.id).copy(name=name)))}
                position=command.position.copy(moduleId=currentModule.id,blockId=block.id)
            }
            is EditorCommand.WriteText->{
                val currentModule=module(command.moduleId)
                if(command.blockId!=null)require(currentModule.blocks.any { it.id==command.blockId && it is ContentBlock.Text }){"文字块不存在"}
                val block=ContentBlock.Text(command.blockId?:id(),text(command.text))
                val blocks=if(command.blockId==null)currentModule.blocks+block else currentModule.blocks.map { if(it.id==block.id)block else it }
                card=ContentChanges.apply(card,ContentChange.ReplaceModule(currentModule.copy(name=command.moduleName,blocks=blocks)))
                position=command.position.copy(moduleId=currentModule.id,blockId=block.id)
            }
            is EditorCommand.WriteCaption->{
                val currentModule=module(command.moduleId)
                val block=currentModule.blocks.single { it.id==command.blockId } as ContentBlock.Image
                card=ContentChanges.apply(card,ContentChange.EditBlocks(listOf(BlockEdit.Replace(block.id,block.copy(caption=text(command.text))))))
                card=ContentChanges.apply(card,ContentChange.ReplaceModule(module(currentModule.id).copy(name=command.moduleName)))
                position=command.position.copy(moduleId=currentModule.id,blockId=block.id)
            }
        }
        if(command is EditorCommand.RemoveResource && position.moduleId!=null) {
            val currentModule=card.modules.flattenModules().find {it.id==position.moduleId}
            if(currentModule!=null && position.blockId!=null && currentModule.blocks.none {it.id==position.blockId})position=EditorPosition(currentModule.id)
        }
        card=FolderContents.organize(card)
        position.blockId?.let {blockId->
            card.modules.flattenModules().firstOrNull {m->m.blocks.any {it.id==blockId}}?.let {owner->position=position.copy(moduleId=owner.id)}
        }
        return drafts.update(cardId,current.version,ContentTargets.replace(current.content,card),position,source,lease)
    }
    private fun text(value:String):ContentRef {
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("editor-input"),ref))){ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))}
        return ref
    }
    private fun id()=UUID.randomUUID().toString()
}
