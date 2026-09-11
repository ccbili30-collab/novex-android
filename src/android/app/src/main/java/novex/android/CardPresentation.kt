package novex.android

import novex.content.ContentDocument
import novex.content.ReadingLayout

/** 保存展示、草稿预览和编辑目录共同解释顶层排列，不另存页面布局副本。 */
internal fun ContentDocument.rootModulesHorizontal():Boolean =
    appearance.readingLayout==ReadingLayout.PAGED

/** 连续内容以一个展示槽承载；空卡也保留横向入口，读取页面不创建模块。 */
internal fun ContentDocument.usesMainSlot():Boolean = !rootModulesHorizontal() || bodyModules().isEmpty()
