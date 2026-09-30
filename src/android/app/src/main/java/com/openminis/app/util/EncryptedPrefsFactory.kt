@file:Suppress("unused")

package com.openminis.app.util

/**
 * 自愈加密 prefs 工厂的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.vault.SelfHealingPrefs]：三级阶梯（正常创建 →
 * 清 XML/keyset/主键别名重建 → `_plain_fallback` 明文兜底）与各级文件名
 * 均为冻结面。safeCreate 是存量调用方（provider_secrets / oauth_prefs /
 * env 加密库）钉住的原名。
 */
typealias EncryptedPrefsFactory = novex.android.vault.SelfHealingPrefs
