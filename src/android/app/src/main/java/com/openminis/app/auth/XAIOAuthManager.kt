@file:Suppress("unused")

package com.openminis.app.auth

/**
 * xAI 登录流的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.XaiLoginFlow]：OIDC 发现 + *.x.ai 域校验、
 * 127.0.0.1 redirect、hex PKCE 且交换回显 challenge 对、nonce/plan/referrer
 * 参数、403 保令牌刷新、重入合并与 Custom Tab 关闭探测。存储键
 * （verifier/challenge/state/auth_endpoint/token_endpoint/account_id/email/
 * display_name）与老盘数据兼容。
 */
typealias XAIOAuthManager = novex.android.authkit.XaiLoginFlow
