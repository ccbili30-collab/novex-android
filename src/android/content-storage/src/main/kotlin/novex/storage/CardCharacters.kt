package novex.storage

/** 保留人工入口适配，创建和复制规则统一进入共同编辑器。 */
class CardCharacters(private val store:CardStore) {
    fun create(worldId:String,expectedVersion:String,name:String):CardDraft=
        CardEditor(store).apply(worldId,expectedVersion,worldId,EditorCommand.AddCharacter(name))
    fun copy(worldId:String,expectedVersion:String,sourceId:String):CardDraft=
        CardEditor(store).apply(worldId,expectedVersion,worldId,EditorCommand.CopyCharacter(sourceId))
}
