@file:Suppress("unused")

package com.openminis.app.i18n

/**
 * 语言覆写的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.localekit.LocaleOverride]。MainActivity /
 * NovexHomeActivity / ShareReceiverActivity 以全限定名调 wrap，行为与
 * prefs 键为冻结面。
 */
typealias LocaleWrap = novex.android.localekit.LocaleOverride
