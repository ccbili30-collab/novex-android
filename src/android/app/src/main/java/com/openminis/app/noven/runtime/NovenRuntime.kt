package com.openminis.app.noven.runtime

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class NovenWorld(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val coverRes: Int? = null,
    val permission: WorldPermission,
    val tags: List<String>,
)

enum class WorldPermission {
    READ_ONLY,
    STORY_ONLY,
    OPEN,
}

@Immutable
data class NovenSession(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val coverRes: Int? = null,
    val worldId: String? = null,
)

sealed interface StoryNode {
    val id: String

    @Immutable
    data class UserMessage(
        override val id: String,
        val text: String,
    ) : StoryNode

    @Immutable
    data class Narrative(
        override val id: String,
        val eyebrow: String? = null,
        val title: String? = null,
        val paragraphs: List<String>,
    ) : StoryNode

    @Immutable
    data class Character(
        override val id: String,
        val name: String,
        val identity: String,
        val publicFace: String,
        val secret: String,
        val traits: List<String>,
    ) : StoryNode

    @Immutable
    data class Actions(
        override val id: String,
        val prompt: String,
        val actions: List<String>,
    ) : StoryNode
}

/**
 * 诺文产品层唯一依赖的运行接口。
 *
 * 第一版由本地预览适配器实现；后续 OpenMinis 适配器负责把工具循环、
 * 持久化会话和流式响应投影成同一组状态，不允许界面直接依赖 ChatViewModel。
 */
interface NovenRuntime {
    val worlds: StateFlow<List<NovenWorld>>
    val sessions: StateFlow<List<NovenSession>>
    val activeSessionId: StateFlow<String?>
    val storyNodes: StateFlow<List<StoryNode>>
    val isGenerating: StateFlow<Boolean>

    fun openSession(sessionId: String)
    fun startWorld(worldId: String): String
    fun createBlankSession(): String
    fun send(text: String)
    fun stop()
}
