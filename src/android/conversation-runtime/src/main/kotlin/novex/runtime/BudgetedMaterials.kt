package novex.runtime

import novex.conversation.AdoptionReason
import novex.storage.CardStore
import novex.storage.TextPages

data class BudgetedText(val source:MaterialText,val text:String,val next:Long?)

/** 与工具共用分页器。只分配本轮预算，不修改卡片，也不把截取冒充完整采用。 */
class BudgetedMaterials(store:CardStore) {
    private val pages=TextPages(store.contents)
    fun read(draft:RequestMaterialDraft,budget:Int,count:(String)->Int,allowDeferred:Boolean):List<BudgetedText> {
        var remaining=budget.coerceAtLeast(0)
        val required=draft.plan.decisions.filter {it.reason==AdoptionReason.ALWAYS || it.reason==AdoptionReason.MANUAL_SELECTED}.map {it.module.id}.toSet()
        return draft.texts.sortedBy {if(it.moduleId in required)0 else 1}.map {source->
            val output=StringBuilder();var offset=0L;var next:Long?=0L
            while(next!=null && remaining>0) {
                val page=pages.read(source.reference,offset,minOf(8192,remaining.coerceAtLeast(1)))
                var body=page.text
                if(count(body)>remaining) {
                    var lo=0;var hi=body.codePointCount(0,body.length)
                    while(lo<hi){val mid=(lo+hi+1)/2;val end=body.offsetByCodePoints(0,mid)
                        if(count(body.substring(0,end))<=remaining)lo=mid else hi=mid-1}
                    body=body.substring(0,body.offsetByCodePoints(0,lo))
                }
                val consumed=body.codePointCount(0,body.length)
                output.append(body);remaining-=count(body)
                next=if(body.length==page.text.length)page.next else offset+consumed
                if(body.length!=page.text.length || consumed==0)break
                offset=next?:offset
            }
            require(next==null || (allowDeferred && source.moduleId !in required)) {
                if(source.moduleId in required)"必带模块「${source.moduleName.ifBlank {"未命名模块"}}」超过可用上下文；请调整必带设置或容量，原文保留"
                else "只读模式下所选资料超过可用上下文，无法调用分页工具；请减少携带资料或调整容量，原文保留"
            }
            BudgetedText(source,output.toString(),next)
        }
    }
}
