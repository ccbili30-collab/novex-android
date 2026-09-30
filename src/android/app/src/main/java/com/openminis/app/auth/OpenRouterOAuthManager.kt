@file:Suppress("unused")

package com.openminis.app.auth

/**
 * OpenRouter 换长期 key 流的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.OpenRouterKeyFlow]：callback_url 授权页、
 * /api/v1/auth/keys JSON 交换、HTTP-Referer / X-Title 头、96 字节
 * standard-base64→URL-safe PKCE、端口 3000/3001/3002 梯队。
 */
typealias OpenRouterOAuthManager = novex.android.authkit.OpenRouterKeyFlow
