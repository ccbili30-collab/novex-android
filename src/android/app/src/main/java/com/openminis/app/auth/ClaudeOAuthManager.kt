@file:Suppress("unused")

package com.openminis.app.auth

/**
 * Anthropic 登录流的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.ClaudeLoginFlow]：JSON 体交换、96 字节
 * PKCE、5 分钟刷新窗、四分类刷新与并发合并。
 * ANTHROPIC_OAUTH_IDENTIFIER_PROMPT 常量被 UI/传输层全限定名引用，属冻结面。
 */
typealias ClaudeOAuthManager = novex.android.authkit.ClaudeLoginFlow
