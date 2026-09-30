@file:Suppress("unused")

package com.openminis.app.auth

/**
 * OAuth 登录流基类的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.VendorLoginFlow]（授权 URL 装配、code 交换、
 * 刷新时序、手工 bearer、跨端导入导出）。本名保留是因为大量调用方（含
 * UI 全限定名引用与零改动验收的既有测试）钉在
 * `com.openminis.app.auth.OAuthManager` 上：forInstance / sanitizeBody /
 * validAccessToken / isAuthenticated / logout 等成员形状是冻结面。
 */
typealias OAuthManager = novex.android.authkit.VendorLoginFlow
