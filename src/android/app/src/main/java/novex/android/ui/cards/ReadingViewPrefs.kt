package novex.android.ui.cards

import android.content.Context
import novex.content.ReadingLayout

/**
 * [T-reading-view-global]（用户 2026-09-28：「他应该就是一种前端渲染模式，
 * 反正数据不会变……切换视图而已，默认翻页」）阅读视图的全局偏好——纯
 * 渲染判定源，不写卡数据。
 *
 * 与卡内 `appearance.readingLayout` 的分工：该字段与编辑器「主要容器/
 * 横向分组」的数据组织规则绑定（PromoteModule/AddPresentedModule 按它
 * 归置模块），保留为存量兼容与编辑信号；阅读渲染（滚动/翻页）只认这里。
 */
object ReadingViewPrefs {
    private const val PREFS = "novex_reading_view"
    private const val KEY = "layout"

    @Volatile
    private var cached: ReadingLayout = ReadingLayout.PAGED

    /** 默认翻页（用户口径）；未 hydrate（无 Context 场景）同样成立。 */
    fun current(): ReadingLayout = cached

    fun hydrate(context: Context) {
        cached = parse(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        )
    }

    fun set(context: Context, layout: ReadingLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, layout.name)
            .apply()
        cached = layout
    }

    /** 非法值回落翻页（fail-closed 到默认视图）。 */
    internal fun parse(raw: String?): ReadingLayout =
        ReadingLayout.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
            ?: ReadingLayout.PAGED
}
