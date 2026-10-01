package novex.android.ui.cards

import novex.content.ContentDocument
import novex.content.ReadingLayout

/** 顶层排列跟随全局阅读视图（[T-reading-view-global] 用户裁决：切换视图而已，
 *  数据不变，默认翻页）；卡内 appearance.readingLayout 仅剩编辑器归置新模块的
 *  存量信号，阅读渲染不再读它。 */
internal fun ContentDocument.rootModulesHorizontal():Boolean =
    ReadingViewPrefs.current()==ReadingLayout.PAGED

/** 连续内容以一个展示槽承载；空卡也保留横向入口，读取页面不创建模块。 */
internal fun ContentDocument.usesMainSlot():Boolean = !rootModulesHorizontal() || bodyModules().isEmpty()

/**
 * [T-reading-view-global] 净眼退回件：编辑器「主要」槽入口必须与 CardEditor
 * 结构转换的前置条件（卡内 appearance.readingLayout）同源——阅读视图已全局
 * 化，若入口跟全局走，特定组合下「新增模块」会撞 CardEditor 的 require
 * 「请指定新增模块所属的横向分组」，存量 CONTINUOUS 卡则丢失新增入口。
 * 仅编辑器（ModuleTreeEditor）使用；阅读页三处仍走全局 [usesMainSlot]。
 */
internal fun ContentDocument.usesMainSlotByData():Boolean =
    appearance.readingLayout==ReadingLayout.CONTINUOUS || bodyModules().isEmpty()
