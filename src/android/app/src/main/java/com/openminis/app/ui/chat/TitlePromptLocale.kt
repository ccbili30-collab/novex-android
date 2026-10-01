package com.openminis.app.ui.chat

import java.util.Locale

/**
 * 会话标题生成的共享系统提示（血统清剿 P3.7 就地真重写；提示全文为模型
 * 面契约冻结面）。自动路径（ChatViewModel.generateSessionTitleIfNeeded）与
 * 手动重生成路径（SessionListViewModel.regenerateTitle）共用，永不漂移。
 * 与 iOS callSubModelForTitle 的系统提示逐字一致。
 *
 * 调用方把它作为裸 `systemPrompt` 传入；OAuth Anthropic 实例的前缀块由
 * AnthropicProvider.resolveSystemPrompt 在 provider 层强制前置（并剥掉调用
 * 方自带的），调用方无需自行前置。
 */
internal const val TITLE_GEN_SYSTEM_PROMPT: String =
    "You generate concise titles for conversations. You MUST respond with a single valid JSON object: {\"title\": \"...\", \"category\": \"...\"}. No other text."

/**
 * 构造追加在标题生成 user prompt 尾部的双语语言指令——对话内容是别的语
 * 言时，标题也要落在用户的界面语言上。
 *
 * 解析：`Locale.getDefault()`——应用暂未提供应用内语言覆盖。locale 解析
 * 失败时回落 "en"/"English" 而不是抛——标题生成绝不该被一个畸形 locale
 * 弄坏。
 */
internal fun titleLanguageDirective(locale: Locale = Locale.getDefault()): String {
    val (code, humanName) = resolveTitleLanguageCode(locale)
    return buildString {
        append("\n\n")
        append("The user's app interface language is \"").append(code)
            .append("\" (").append(humanName).append("). Generate the title primarily in this language. ")
        append("If the conversation content is in a different language, you may keep proper nouns ")
        append("from it, but the overall title language should match the interface language.\n")
        append("用户的 App 界面语言是 \"").append(code).append("\"（").append(humanName).append("）。")
        append("请优先使用该语言生成标题。如果对话内容是其他语言，可保留专有名词，但标题整体语言应与界面语言一致。")
    }
}

private fun resolveTitleLanguageCode(locale: Locale): Pair<String, String> = try {
    val lang = locale.language.takeIf { it.isNotEmpty() } ?: return "en" to "English"
    when {
        // 简繁都要区分——裸 "zh" 两边一样，但标题风格差异可观。
        lang == "zh" -> zhVariantOf(locale)
        else -> lang to (humanReadable[lang] ?: locale.getDisplayLanguage(Locale.ENGLISH).ifEmpty { lang })
    }
} catch (_: Exception) {
    "en" to "English"
}

private fun zhVariantOf(locale: Locale): Pair<String, String> {
    val isTraditional = locale.script.equals("Hant", ignoreCase = true) ||
        locale.country in setOf("TW", "HK", "MO")
    return if (isTraditional) "zh-Hant" to "繁體中文 / Traditional Chinese"
    else "zh-Hans" to "简体中文 / Simplified Chinese"
}

private val humanReadable: Map<String, String> = mapOf(
    "en" to "English",
    "ja" to "日本語 / Japanese",
    "ko" to "한국어 / Korean",
    "fr" to "Français / French",
    "de" to "Deutsch / German",
    "es" to "Español / Spanish",
    "it" to "Italiano / Italian",
    "pt" to "Português / Portuguese",
    "ru" to "Русский / Russian",
    "ar" to "العربية / Arabic",
    "hi" to "हिन्दी / Hindi",
    "vi" to "Tiếng Việt / Vietnamese",
    "th" to "ไทย / Thai",
    "id" to "Bahasa Indonesia / Indonesian",
    "tr" to "Türkçe / Turkish",
    "nl" to "Nederlands / Dutch",
    "pl" to "Polski / Polish",
)
