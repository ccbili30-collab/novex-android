@file:Suppress("unused")

package com.openminis.app.auth

/**
 * Gemini 登录流的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.GeminiLoginFlow]：offline/consent 授权
 * 参数、userinfo 补邮箱、cloudcode-pa 两级项目探测、form 体刷新四分类。
 * email / gcp_project 辅助字段键与导出路径上的 exportOAuthString 调用对
 * 老存储兼容。
 */
typealias GeminiOAuthManager = novex.android.authkit.GeminiLoginFlow
