package com.openminis.app.novex.domain

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.data.model.ThinkingLevel
import java.io.File
import org.json.JSONObject

/**
 * [T-stage3-snapshot] 世界快照（总纲 §3.6）——压缩时产出的"当前状态"结构化
 * 陈述，根治记忆干扰：常量模块可安全重注入（幂等），可变状态由快照承载，
 * 两个信息源经元说明明确分工（"常设规则 vs 剧情现状"）。
 *
 * 增量更新：输入=上一版快照+本轮压缩摘要+最近原文，新快照以旧版为基底
 * 打变化——不从头重写。持久化 `<novex>/<session>/snapshots/`（latest +
 * 历史链；存档三元组的快照分量复用）。
 */
object NovexStateSnapshot {

    data class Snapshot(
        val timeAnchor: String,
        val location: String,
        val mainThread: String,
        val characters: String,
        val openThreads: String,
        val rawJson: String,
        val createdAt: Long,
    )

    const val SNAPSHOT_SYSTEM_PROMPT = "你是状态记录器。把更新为最新的世界状态快照。只输出 JSON 对象，无其他文字。"

    fun prompt(previous: Snapshot?, summary: String, recentText: String, cardName: String?): String = buildString {
        appendLine("更新世界状态快照。以旧快照为基底，把摘要与近期对话带来的变化打上去；旧信息未被推翻的部分原样保留。")
        if (cardName != null) appendLine("本局：$cardName")
        appendLine("只输出 JSON：{\"time\":\"游戏内时间锚点\",\"location\":\"当前所在地\",\"main\":\"主线进展一句话\",\"characters\":\"关键人物状态（每条一人，含存亡/关系/动向）\",\"threads\":\"未决伏笔与倒计时\"}")
        appendLine("旧快照：")
        appendLine(previous?.rawJson ?: "（无）")
        appendLine("本轮压缩摘要：")
        appendLine(summary.ifBlank { "（无）" })
        appendLine("近期原文（节选）：")
        appendLine(recentText.take(4000).ifBlank { "（无）" })
    }

    fun parse(raw: String, now: Long): Snapshot? = runCatching {
        val root = JSONObject(raw.trim().substringAfter('{').let { "{$it" }.substringBeforeLast('}') + "}")
        fun s(k: String) = root.optString(k).trim()
        Snapshot(
            timeAnchor = s("time"), location = s("location"), mainThread = s("main"),
            characters = s("characters"), openThreads = s("threads"),
            rawJson = root.toString(), createdAt = now,
        ).takeIf { it.timeAnchor.isNotEmpty() || it.mainThread.isNotEmpty() }
    }.getOrNull()

    /** 压缩后注入的"当前状态锚"一行版（总纲 §3.8 第三层，注意力锚）。 */
    fun anchorLine(snapshot: Snapshot?): String? = snapshot?.let {
        listOfNotNull(
            it.timeAnchor.takeIf(String::isNotEmpty),
            it.location.takeIf(String::isNotEmpty),
            it.mainThread.takeIf(String::isNotEmpty),
        ).takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
    }

    /** 持久化：latest + 历史链（存档三元组的快照分量读取点）。 */
    fun saveLatest(context: Context, sessionId: String, snapshot: Snapshot) {
        val dir = File(File(File(context.filesDir, "novex"), sessionId), "snapshots")
        dir.mkdirs()
        val json = JSONObject(snapshot.rawJson).put("savedAt", snapshot.createdAt).toString()
        File(dir, "latest.json").writeText(json)
        File(dir, "${snapshot.createdAt}.json").writeText(json)
    }

    fun loadLatest(context: Context, sessionId: String): Snapshot? = runCatching {
        val file = File(File(File(File(context.filesDir, "novex"), sessionId), "snapshots"), "latest.json")
        parse(file.readText(), JSONObject(file.readText()).optLong("savedAt", 0L))
    }.getOrNull()

    /** 压缩后生成（调用方保证后台语境；失败返回 null 由调用方静默跳过）。 */
    suspend fun generate(
        provider: LLMProvider,
        previous: Snapshot?,
        summary: String,
        recentText: String,
        cardName: String?,
    ): Snapshot? {
        val answer = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, prompt(previous, summary, recentText, cardName))),
            SNAPSHOT_SYSTEM_PROMPT, 2048, thinkingLevel = ThinkingLevel.OFF,
        )
        return parse(answer.text, System.currentTimeMillis())
    }
}
