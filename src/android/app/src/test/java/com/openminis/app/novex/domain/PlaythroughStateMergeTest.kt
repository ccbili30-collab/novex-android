package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态合并守护（用户 2026-09-15 报告的"新状态把旧状态完全盖住"缺陷）：
 * 新一轮的快照必须**继承**路径上最近祖先的全部值再套用本轮更新——
 * 更新旧数据、添加新数据，而不是从空新建。
 */
class PlaythroughStateMergeTest {
    private fun updates(vararg items: JSONObject): String = JSONArray().apply { items.forEach { put(it) } }.toString()
    private fun update(key: String, value: Any, max: Number? = null): JSONObject =
        JSONObject().put("key", key).put("value", value).also { item ->
            if (max != null) item.put("max", max)
        }

    @Test fun newBranchInheritsNearestAncestorValuesBeforeApplyingUpdates() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        val turn1 = "turn-1"
        config = PlaythroughStateRegistration.applyUpdates(config, turn1, updates(update("hp", 100), update("location", "山门")), listOf("root", turn1))
        val turn2 = "turn-2"
        config = PlaythroughStateRegistration.applyUpdates(config, turn2, updates(update("hp", 72)), listOf("root", turn1, turn2))

        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("root", turn1, turn2))
        // 更新旧数据 + 添加新数据：hp 被本轮覆盖，location 继承保留。
        assertEquals((resolved.values["hp"] as PlaythroughValue.Number).value, 72.0, 0.0)
        assertEquals((resolved.values["location"] as PlaythroughValue.Text).value, "山门")
        assertEquals(2, resolved.values.size)
    }

    @Test fun deepBranchInheritsFromNearestAncestorOnly() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 100)), listOf("t1"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t2", updates(update("mp", 50)), listOf("t1", "t2"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t3", updates(update("armor", 3)), listOf("t1", "t2", "t3"))
        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("t1", "t2", "t3"))
        assertEquals(setOf("hp", "mp", "armor"), resolved.values.keys)
    }

    @Test fun sameBranchUpdateReusesItsOwnSnapshot() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 100)), listOf("t1"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 40), update("gold", 5)), listOf("t1"))
        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("t1"))
        assertEquals(40.0, (resolved.values["hp"] as PlaythroughValue.Number).value, 0.0)
        assertEquals(5.0, (resolved.values["gold"] as PlaythroughValue.Number).value, 0.0)
    }

    @Test fun numberMaxSurvivesCodecRoundTripAndDrivesDisplayBars() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        config = PlaythroughStateRegistration.applyUpdates(
            config, "t1",
            updates(update("hp", 83, max = 100), update("sanity", 7)),
            listOf("t1"),
        )
        val encoded = NovexConversationConfigurationCodec.encode(config)
        val decoded = NovexConversationConfigurationCodec.decode(encoded, "chat")
        val hp = InteractiveFictionRuntime.resolveState(decoded, listOf("t1")).values["hp"] as PlaythroughValue.Number
        assertEquals(83.0, hp.value, 0.0)
        assertEquals(100.0, hp.max!!, 0.0)
        val sanity = InteractiveFictionRuntime.resolveState(decoded, listOf("t1")).values["sanity"] as PlaythroughValue.Number
        assertEquals(null, sanity.max)
    }

    @Test fun invalidMaxIsRejectedOrDroppedNotStored() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        // max=0 或负数：注册时丢弃（按无上限处理），不落库。
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 5, max = 0)), listOf("t1"))
        val hp = InteractiveFictionRuntime.resolveState(config, listOf("t1")).values["hp"] as PlaythroughValue.Number
        assertEquals(null, hp.max)
    }

    // ── 删除能力（2026-09-15 用户报告：AI 只能加、不能改删）────────────────

    private fun remove(key: String): JSONObject = JSONObject().put("remove", key)

    @Test fun removeDeletesExistingKeyOnSameBranch() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 100), update("mp", 40)), listOf("t1"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(remove("hp")), listOf("t1"))
        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("t1"))
        assertEquals(setOf("mp"), resolved.values.keys)
    }

    @Test fun removeOnNewBranchInheritsAncestorThenDeletes() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 100), update("mp", 40)), listOf("t1"))
        // 新分支上删除：先继承祖先（mp 保留），再删 hp。
        config = PlaythroughStateRegistration.applyUpdates(config, "t2", updates(remove("hp")), listOf("t1", "t2"))
        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("t1", "t2"))
        assertEquals(setOf("mp"), resolved.values.keys)
    }

    @Test fun removeMissingKeyIsIdempotent() {
        var config = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"))
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(update("hp", 100)), listOf("t1"))
        // 删除不存在的键：幂等成功，既有值不受影响。
        config = PlaythroughStateRegistration.applyUpdates(config, "t1", updates(remove("ghost")), listOf("t1"))
        val resolved = InteractiveFictionRuntime.resolveState(config, listOf("t1"))
        assertEquals(setOf("hp"), resolved.values.keys)
    }
}
