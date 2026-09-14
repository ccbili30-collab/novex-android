package novex.storage

import novex.content.*
import java.util.UUID

/** 内容组合规则只在此处定义；页面和模型沿共同编辑器进入。 */
internal class CardComposition(private val store:CardStore) {
    fun apply(card:ContentDocument,command:EditorCommand):ContentDocument=when(command) {
        is EditorCommand.PlaceCharacter->{
            require(card.kind==CardKind.WORLD && card.internalCharacters.any {it.id==command.characterId}) {"角色不属于当前世界"}
            var modules=card.modules.mapModules {it.copy(characterIds=it.characterIds-command.characterId)}
            command.moduleId?.let {id->modules=modules.updateModule(id){it.copy(characterIds=it.characterIds+command.characterId)}}
            card.copy(modules=modules)
        }
        is EditorCommand.Layout->card.copy(appearance=card.appearance.copy(readingLayout=command.layout))
        is EditorCommand.RemoveResource->{
            require(card.resources.any {it.id==command.resourceId}){"素材不属于当前卡片"}
            val used=card.appearance.avatarResourceId==command.resourceId || card.appearance.coverResourceId==command.resourceId ||
                card.modules.flattenModules().any {m->m.blocks.any {it is ContentBlock.Image && it.resourceId==command.resourceId}}
            require(!used || command.removeUses){"素材仍在使用，请先移除展示或明确同时移除全部用途"}
            // 历史修订仍可使用原始字节；这里只移除当前作品的资源归属，不清理历史文件。
            card.copy(resources=card.resources.filterNot {it.id==command.resourceId},
                appearance=card.appearance.copy(avatarResourceId=card.appearance.avatarResourceId?.takeUnless {it==command.resourceId},coverResourceId=card.appearance.coverResourceId?.takeUnless {it==command.resourceId}),
                modules=card.modules.mapModules {m->m.copy(blocks=m.blocks.filterNot {it is ContentBlock.Image && it.resourceId==command.resourceId})})
        }
        is EditorCommand.AddCharacter->{
            require(card.kind==CardKind.WORLD && command.name.isNotBlank()){"只有世界可创建内部角色，名称不能为空"}
            card.copy(internalCharacters=card.internalCharacters+ContentDocument(UUID.randomUUID().toString(),CardKind.CHARACTER,command.name.trim()))
        }
        is EditorCommand.CopyCharacter->{
            require(card.kind==CardKind.WORLD){"只有世界可放入角色副本"}
            val source=requireNotNull(store.open(command.sourceId)){"来源角色未保存"}.content
            require(source.kind==CardKind.CHARACTER){"来源不是独立角色"}
            val copy=ContentCopies.plan(source,{UUID.randomUUID().toString()},store.contents.allocator())
            if(copy.transfers.isNotEmpty())store.contents.receive(copy.transfers,store.contents::open)
            card.copy(internalCharacters=card.internalCharacters+copy.candidate)
        }
        is EditorCommand.RemoveCharacter->{
            require(card.kind==CardKind.WORLD && card.internalCharacters.any {it.id==command.characterId}){"内部角色不属于这个世界"}
            card.copy(internalCharacters=card.internalCharacters.filterNot {it.id==command.characterId},modules=card.modules.mapModules {it.copy(characterIds=it.characterIds-command.characterId)})
        }
        else->error("不是内容组合操作")
    }.validate()
}
