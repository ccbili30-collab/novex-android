package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.R
import novex.android.data.model.ThinkingLevel

private val thinkingLevelLabels = mapOf(
    ThinkingLevel.OFF to R.string.thinking_level_off,
    ThinkingLevel.LOW to R.string.thinking_level_low,
    ThinkingLevel.MEDIUM to R.string.thinking_level_medium,
    ThinkingLevel.HIGH to R.string.thinking_level_high,
    ThinkingLevel.XHIGH to R.string.thinking_level_xhigh,
    ThinkingLevel.MAX to R.string.thinking_level_max,
    ThinkingLevel.ULTRA to R.string.thinking_level_ultra,
)

/**
 * 思考档位的用户可见名称（斜杠面板、档位胶囊用）。数据层
 * [ThinkingLevel.displayName] 是英文常量，这里走 strings.xml 本地化。
 */
fun ThinkingLevel.localizedName(context: Context): String =
    context.getString(checkNotNull(thinkingLevelLabels[this]))
