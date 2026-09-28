package novex.android

import novex.content.ContentDocument
import novex.content.ReadingLayout

/** 顶层排列跟随全局阅读视图（[T-reading-view-global] 用户裁决：切换视图而已，
 *  数据不变，默认翻页）；卡内 appearance.readingLayout 仅剩编辑器归置新模块的
 *  存量信号，阅读渲染不再读它。 */
internal fun ContentDocument.rootModulesHorizontal():Boolean =
    ReadingViewPrefs.current()==ReadingLayout.PAGED

/** 连续内容以一个展示槽承载；空卡也保留横向入口，读取页面不创建模块。 */
internal fun ContentDocument.usesMainSlot():Boolean = !rootModulesHorizontal() || bodyModules().isEmpty()
