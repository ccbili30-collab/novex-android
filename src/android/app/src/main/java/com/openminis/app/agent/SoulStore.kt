@file:Suppress("unused")

package com.openminis.app.agent

/**
 * SOUL.md 人格存储的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现拆入 novex.android.soul 三件：
 *  - [novex.android.soul.SoulDocument]  身份/文档模型 + frontmatter 编解码
 *    （含历史名迁移）；
 *  - [novex.android.soul.SoulRepository] 文件 IO、播种与迁移、身份缓存、
 *    语言分轴长度规则、缺省内容；
 *  - SystemPromptBuilder 不再门面化——全仓零外部引用（系统提示装配已由
 *    本包 NovexSystemPrompt 接管），随真重写裁撤。
 *
 * 旧名全量别名保留：调用方（含 UI 全限定名引用与零改动验收的既有测试）
 * 钉在 com.openminis.app.agent.{SoulStore, SoulMetadata, SoulFile,
 * SoulMDParser, SoulBodyLimitCheck, migrateLegacyAssistantName} 上。
 * 存储路径、prefs/键、frontmatter 形态与缺省正文均为冻结面。
 */
typealias SoulStore = novex.android.soul.SoulRepository
typealias SoulMetadata = novex.android.soul.SoulIdentity
typealias SoulFile = novex.android.soul.SoulDocument
typealias SoulMDParser = novex.android.soul.SoulFrontmatterCodec

/** 正文体长度判定（形状冻结：设置页 when 分支钉住各 case 与字段）。 */
sealed class SoulBodyLimitCheck {
    object Ok : SoulBodyLimitCheck()
    data class OverLimitChinese(val chars: Int, val cap: Int) : SoulBodyLimitCheck()
    data class OverLimitEnglish(val words: Int, val cap: Int) : SoulBodyLimitCheck()

    val isOverLimit: Boolean get() = this !is Ok
}

/** 顶层迁移函数门面（同包旧测试按原名调用）。 */
internal fun migrateLegacyAssistantName(source: String): String =
    novex.android.soul.migrateLegacyAssistantName(source)
