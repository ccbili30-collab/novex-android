@file:Suppress("unused")

package com.openminis.app.auth

/**
 * OpenAI Codex 登录流的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.CodexLoginFlow]：JSON 体交换、CLI 指纹
 * 授权参数、系统代理感知客户端 + 3 轮退避重试、id_token 账号/档位解析。
 * accountId 属性被 ProviderFactory 的 codexAccountId 装配引用，属冻结面。
 */
typealias OpenAIOAuthManager = novex.android.authkit.CodexLoginFlow

/** 网络/代理不通的异常门面：UI 依它给“检查网络或代理”提示。 */
typealias OAuthNetworkUnreachableException = novex.android.authkit.OAuthNetworkUnreachableException
