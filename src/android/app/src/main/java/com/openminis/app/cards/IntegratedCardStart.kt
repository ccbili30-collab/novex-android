package com.openminis.app.cards

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.content.ContentTargets
import novex.runtime.ManagementTarget
import novex.runtime.SourceSelection

/**
 * 从卡片开一段新会话：按 manage 构造 CardBinding（普通 → primary
 * SourceSelection；管理 → managed ManagementTarget），然后走
 * IntegratedCardEntry.draftId 落库。IO 线程执行，返回 chat id。
 */
object IntegratedCardStart {
    suspend fun start(app: Context, rootId: String, targetId: String, manage: Boolean): String =
        withContext(Dispatchers.IO) {
            val cards = IntegratedCards(app)
            val name = ContentTargets.find(
                requireNotNull(cards.store.open(rootId)).content,
                targetId,
            ).name
            val binding = if (manage) {
                CardBinding(managed = setOf(ManagementTarget(rootId, targetId)))
            } else {
                CardBinding(primary = SourceSelection(rootId, targetId))
            }
            IntegratedCardEntry.draftId(app, name, binding)
        }
}
