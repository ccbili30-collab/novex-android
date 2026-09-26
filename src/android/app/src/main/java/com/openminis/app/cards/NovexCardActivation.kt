package com.openminis.app.cards

import novex.content.ContentDocument
import novex.content.ContentRef
import novex.content.ModuleRouting
import novex.content.effectiveRouting
import novex.storage.TextPages

/**
 * [T-stage1-activation] 开局激活协议（总纲 §3.2，阶段 1 PR-B）。
 *
 * 挂卡启用时：所有 default 路由模块以**开局资料包**形态进入对话——一条
 * 持久化 system 消息（居中渲染、展开见全文，模型投影为对话流位置的
 * user 上下文块"像用户直接发的一样"）；per_turn/style 路由模块的文本
 * 拼接进对话的每轮注入/文风槽位（空则填、有则追加）；随后由调用方自动
 * 发起启动回合（文游卡=启动确认+开场引导；角色卡=直接入戏）。
 *
 * 放不下绝不静默漏：资料包 token 超出本轮可用预算 → 抛错并附完整携带
 * 清单，由 UI 明示（调大容量/减少默认模块/换轻卡三选一）。
 */
object NovexCardActivation {

    data class ActivationMaterial(
        /** 资料包全文（落库 system 消息的正文，含模块清单与启动词尾注）。 */
        val packageText: String,
        /** divider 行短标签：如"已载入开局资料 · 9 个模块 · 约 1.2 万字"。 */
        val label: String,
        /** per_turn 路由模块拼接文本（无则空）。 */
        val perTurnText: String,
        /** style 路由模块拼接文本（无则空）。 */
        val styleText: String,
        /** default 路由模块数与正文字符数（清单/报错用）。 */
        val defaultModuleCount: Int,
        val defaultCharCount: Int,
    )

    const val SYSTEM_ICON_KIND = "card-activation"

    /** 启动词（资料包尾注 + 自动启动回合的触发指令共用语义）。 */
    const val BOOTSTRAP_DIRECTIVE_WORLD =
        "【启动】开局资料已就位（见上方资料包）。请按此卡运行，现在开始第一幕：向玩家做开场引导，不要复述资料包。"
    const val BOOTSTRAP_DIRECTIVE_CHARACTER =
        "【启动】开局资料已就位（见上方资料包）。请以角色身份直接开场，不要复述资料包。"

    /**
     * 从卡结构构建激活材料。readText 由调用方注入（生产=TextPages 分页读
     * 全文；测试=内存串）。跨卡顺序：primary 在前、backgrounds 在后；卡内
     * 按模块树先序。
     */
    fun build(
        cards: List<ContentDocument>,
        readText: (ContentRef) -> String,
        cardName: (ContentDocument) -> String = { it.name },
    ): ActivationMaterial {
        val defaults = StringBuilder()
        val perTurn = StringBuilder()
        val style = StringBuilder()
        val listing = StringBuilder()
        var defaultCount = 0
        var charCount = 0
        cards.forEach { card ->
            val cardDefaults = card.modules.flatMap(::flatten)
                .filter { it.second.effectiveRouting() == ModuleRouting.DEFAULT }
            if (cardDefaults.isNotEmpty()) {
                listing.appendLine("◆ ${cardName(card)}（${cardDefaults.size} 个模块）")
            }
            cardDefaults.forEach { (_, module) ->
                defaultCount++
                listing.appendLine("  · ${module.name}")
                defaults.appendLine()
                defaults.appendLine("## ${module.name}")
                module.blocks.forEach { block ->
                    val text = (block as? novex.content.ContentBlock.Text)?.let { readText(it.content) }
                    if (!text.isNullOrBlank()) {
                        charCount += text.length
                        defaults.appendLine(text)
                    }
                }
            }
            card.modules.flatMap(::flatten).forEach { (_, module) ->
                val routed = when (module.effectiveRouting()) {
                    ModuleRouting.PER_TURN -> perTurn
                    ModuleRouting.STYLE -> style
                    else -> null
                } ?: return@forEach
                module.blocks.forEach { block ->
                    val text = (block as? novex.content.ContentBlock.Text)?.let { readText(it.content) }
                    if (!text.isNullOrBlank()) routed.appendLine(text)
                }
            }
        }
        val packageText = buildString {
            appendLine("【本局开局资料】")
            if (listing.isNotEmpty()) {
                append("载入以下模块：")
                appendLine()
                append(listing.toString().trimEnd())
                appendLine()
            }
            appendLine(defaults.toString().trimEnd())
            appendLine()
            appendLine("──")
            append("以上为本局全部开局资料，已就位。请以此为准运行本卡。")
        }
        return ActivationMaterial(
            packageText = packageText,
            label = "已载入开局资料 · $defaultCount 个模块 · 约${formatChars(charCount)}",
            perTurnText = perTurn.toString().trim(),
            styleText = style.toString().trim(),
            defaultModuleCount = defaultCount,
            defaultCharCount = charCount,
        )
    }

    /** 资料包 parts_json（单 text part，全文直存）。 */
    fun partsJson(material: ActivationMaterial): String =
        org.json.JSONArray().put(
            org.json.JSONObject().put("type", "text").put("value", material.packageText),
        ).toString()

    /** 预算护栏：资料包 token 数超出可用预算 → 明确报错（不静默截断）。 */
    fun requireFits(material: ActivationMaterial, availableTokens: Int, count: (String) -> Int) {
        val cost = count(material.packageText)
        require(cost <= availableTokens) {
            "开局资料包约 $cost token，超出本会话可用上下文 $availableTokens；" +
                "请在卡设置中减少默认模块、调大对话容量，或换一张更轻的卡。携带清单：${material.label}"
        }
    }

    private fun flatten(module: novex.content.ContentModule): List<Pair<Unit, novex.content.ContentModule>> =
        listOf(Unit to module) + module.children.flatMap(::flatten)

    private fun formatChars(count: Int): String = when {
        count >= 10_000 -> "${"%.1f".format(count / 10_000.0)} 万字"
        else -> "$count 字"
    }
}
