package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 侧边对话回传（2026-09-14 决策 3/5/16）：侧边页的符号入口触发，侧边模型先产出
 * 增量交接简报，简报以带标记的用户消息并入主线历史。水位线持久化在侧边会话上，
 * 每次回传只总结水位线之后的新内容；主对话侧只认"回传后新产生的助手回复"，
 * 生成失败绝不把旧回复当简报（交接守卫，见 ChatViewModel 的回传状态机——
 * 2026-09-15 下沉：此前收尾协程活在页面里，退出页面即静默丢简报）。
 */
internal object NovexSideHandoff {
    const val PREFS = "novex_side_conversations"

    /** 回传后新简报写入主线时，主对话页需要重载一次才能看到（导航返回时消费）。 */
    fun markParentDirty(context: Context, parentId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("dirty-parent", parentId).apply()
    }

    fun consumeParentDirty(context: Context, sessionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("dirty-parent", null) != sessionId) return false
        prefs.edit().remove("dirty-parent").apply()
        return true
    }

    fun readWatermark(context: Context, sideId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("brief:$sideId", null)

    fun writeWatermark(context: Context, sideId: String, brief: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("brief:$sideId", brief).apply()
    }

    /** 简报作为带标记的用户消息进入主线（决策 4）：一行折叠标记，正文可展开。 */
    fun parts(brief: String): String = JSONArray()
        .put(JSONObject().put("type", "text").put("value", "〔侧边结论 · 已并入〕\n$brief"))
        .toString()

    fun instruction(previous: String?): String =
        (previous?.takeIf { it.isNotBlank() }?.let { "上一次已交接的内容：\n$it\n\n" } ?: "") +
            "请把本次讨论中上次交接之后的新结论，压缩成一份交接简报：只列确定的事实、设定变更和接下来要做的事，" +
            "不要复述剧情，不要空话，500 字以内。状态相关的变化要写明字段与数值（如 hp 83/100），主线会据此自行落账。直接输出简报正文。"
}

/**
 * [T-side-handoff-strip] 回传状态条（2026-09-15 决策 ①）：钉在侧边页输入栏
 * 上方，常驻反馈回传全程——忙碌转圈文案 → 成功绿字停 3 秒 → 失败红字带重试，
 * 任何状态都不会无声消失。状态源在 ChatViewModel（页面销毁不丢流程）。
 */
@Composable
internal fun NovexSideHandoffStatusStrip(
    state: ChatViewModel.SideHandoffState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        when (state) {
            is ChatViewModel.SideHandoffState.Running -> Text(
                "⟳ 正在生成交接简报…",
                color = ChatColors.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            is ChatViewModel.SideHandoffState.Succeeded -> Text(
                "✓ 已并入主对话",
                color = ChatColors.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            is ChatViewModel.SideHandoffState.Failed -> {
                Text(
                    "✗ ${state.reason}",
                    color = ChatColors.warningText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "重试",
                    color = ChatColors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Text(
                    "关闭",
                    color = ChatColors.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            is ChatViewModel.SideHandoffState.Idle -> {}
        }
    }
}
