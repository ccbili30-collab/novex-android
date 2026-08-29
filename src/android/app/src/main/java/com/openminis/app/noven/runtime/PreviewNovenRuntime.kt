package com.openminis.app.noven.runtime

import com.openminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class PreviewNovenRuntime : NovenRuntime {
    private val _worlds = MutableStateFlow(
        listOf(
            NovenWorld(
                id = "bone-city",
                title = "白骨城纪事",
                subtitle = "一座建立在远古巨兽遗骸上的北境城邦",
                description = "商旅沿着骨桥进入城邦，教会、矿工与旧贵族争夺巨兽遗骸中残存的力量。你抵达时，横跨深渊的主桥刚刚断裂。",
                coverRes = R.drawable.noven_bone_city,
                permission = WorldPermission.STORY_ONLY,
                tags = listOf("黑暗奇幻", "城邦", "调查"),
            ),
            NovenWorld(
                id = "raven-room",
                title = "鸦室来信",
                subtitle = "古典宅邸里挂着一幅不属于这个时代的画",
                description = "每一任房主都声称画中的乌鸦比昨天更靠近窗边。直到今晨，画框里多出了一封写着你名字的信。",
                permission = WorldPermission.OPEN,
                tags = listOf("怪诞", "悬疑", "现代性"),
            ),
            NovenWorld(
                id = "ash-clock",
                title = "灰烬钟楼",
                subtitle = "城市用人的记忆维持时间运转",
                description = "钟声每响一次，就有人忘记一件事。你是钟楼新来的听音人，也是唯一记得昨夜发生过什么的人。",
                permission = WorldPermission.READ_ONLY,
                tags = listOf("蒸汽幻想", "记忆", "都市"),
            ),
        )
    )
    override val worlds: StateFlow<List<NovenWorld>> = _worlds.asStateFlow()

    private val initialSession = NovenSession(
        id = "eleanor",
        title = "红藤不等人",
        preview = "暗红火漆上，藤蔓缠绕着一柄匕首。",
        timeLabel = "刚刚",
        coverRes = R.drawable.noven_bone_city,
        worldId = "bone-city",
    )
    private val _sessions = MutableStateFlow(
        listOf(
            initialSession,
            NovenSession("raven", "画框外的房间", "那只乌鸦并不在画布上。", "昨天", worldId = "raven-room"),
            NovenSession("clock", "第十三次钟声", "你仍然记得她的名字。", "周二", worldId = "ash-clock"),
        )
    )
    override val sessions: StateFlow<List<NovenSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(initialSession.id)
    override val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _storyNodes = MutableStateFlow(openingNodes())
    override val storyNodes: StateFlow<List<StoryNode>> = _storyNodes.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    override fun openSession(sessionId: String) {
        _activeSessionId.value = sessionId
        _storyNodes.value = if (sessionId == initialSession.id) openingNodes() else emptyList()
    }

    override fun startWorld(worldId: String): String {
        val world = _worlds.value.first { it.id == worldId }
        val id = UUID.randomUUID().toString()
        val session = NovenSession(
            id = id,
            title = world.title,
            preview = "故事尚未开始",
            timeLabel = "现在",
            coverRes = world.coverRes,
            worldId = worldId,
        )
        _sessions.value = listOf(session) + _sessions.value
        _activeSessionId.value = id
        _storyNodes.value = if (worldId == "bone-city") openingNodes() else listOf(
            StoryNode.Narrative(
                id = "intro-$id",
                eyebrow = "故事开始",
                title = world.title,
                paragraphs = listOf(world.description, "人工智能已经接住这个世界。现在，它在等你给出第一句话。"),
            ),
            StoryNode.Actions("actions-$id", "你可以直接行动", listOf("环顾四周", "检查随身物品", "等一个人先开口")),
        )
        return id
    }

    override fun createBlankSession(): String {
        val id = UUID.randomUUID().toString()
        _sessions.value = listOf(NovenSession(id, "未命名故事", "说出一个念头，人工智能会接住它。", "现在")) + _sessions.value
        _activeSessionId.value = id
        _storyNodes.value = emptyList()
        return id
    }

    override fun send(text: String) {
        if (text.isBlank()) return
        val turnId = UUID.randomUUID().toString()
        _storyNodes.value = _storyNodes.value + listOf(
            StoryNode.UserMessage(
                id = "user-$turnId",
                text = text,
            ),
            StoryNode.Narrative(
                id = "assistant-$turnId",
                paragraphs = listOf(
                    "你的动作让故事继续向前。$text",
                    "真实模型循环将在下一阶段通过同一运行接口接管这里。",
                ),
            ),
        )
    }

    override fun stop() {
        _isGenerating.value = false
    }

    private fun openingNodes(): List<StoryNode> = listOf(
        StoryNode.Narrative(
            id = "birth",
            eyebrow = "世界已经生成",
            title = "你在世界的一角，已经活过了一段沉重的人生",
            paragraphs = listOf(
                "你在诺德萨恩王国北部的一座古老石堡中出生。父亲是边境伯爵，母亲早逝。你是长女，也是家族在所有宴会上最无可挑剔的脸面。",
                "十六岁那年，一支南方商队让你喝下了溶有“红藤”的酒。四年来，你替他们送信、偷地图、打开不该打开的门。你仍在壁炉前读诗，只是手会在夜里发抖。",
            ),
        ),
        StoryNode.Character(
            id = "eleanor-card",
            name = "埃莉诺·冯·哈弗斯",
            identity = "20岁 · 边境伯爵长女",
            publicFace = "举止得体，精通骑术，熟悉北地礼仪。",
            secret = "被名为“红藤”的药物控制，替地下组织传递情报。",
            traits = listOf("贵族", "骑术", "长期焦虑"),
        ),
        StoryNode.Narrative(
            id = "morning",
            eyebrow = "今天早晨 · 哈弗斯堡",
            paragraphs = listOf(
                "石墙上凝着霜。你坐在窗前，手指间捏着一封没有署名的信。暗红火漆上，一株藤蔓缠绕着一柄匕首。",
                "信里只有一行字：明晚，北门酒馆，后厨。有人等你。",
            ),
        ),
        StoryNode.Actions(
            id = "first-actions",
            prompt = "你准备怎么度过今天？",
            actions = listOf("查看信封", "找乳母谈话", "提前进城"),
        ),
    )
}
