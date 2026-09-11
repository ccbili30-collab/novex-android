package novex.storage

import novex.content.*

/** 人工和模型新建作品共用；稳定操作编号用于中断后的保存结果查询。 */
class CardCreation(private val store:CardStore) {
    fun create(id:String,name:String,kind:CardKind,source:ChangeSource,operation:String):SavedCard {
        require(name.isNotBlank()){"作品名称不能为空"}
        store.history(id,operation)?.let {return it}
        require(store.open(id)==null){"新作品编号已被占用"}
        return store.save(ContentDocument(id,kind,name.trim()),null,source,operation)
    }
}
