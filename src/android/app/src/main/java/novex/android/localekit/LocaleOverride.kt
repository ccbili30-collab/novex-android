package novex.android.localekit

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import java.util.Locale

/**
 * Tiramisu 之前的语言覆写（P3.5c 自 i18n/LocaleWrap 真重写）。
 *
 * Android 13+ 有 LocaleManager.applicationLocales，系统会把应用级语言
 * 传播到每个 Activity/Service 的 Resources，包括系统 TextView 给文本
 * 选中 ActionMode 标的“剪切/复制/粘贴/全选”。Android 12 及更早（提过
 * Telegram msg 31956 的 MIUI 13 即是）没有这层管道——无论用户在
 * 设置 → 外观 → 语言里选了什么，选中菜单始终停在系统语言。
 *
 * 解法与各家“应用内语言切换”库一致：在每个用户可见 Activity 的
 * attachBaseContext 里，把存好的语言套到基础 Configuration 上。此处
 * 只需一个助手，不引库。Tiramisu+ 是 no-op，避免双重套用。
 *
 * 键（冻结面）：prefs 文件 PREF_APPEARANCE、语言键 KEY_LANGUAGE；空值
 * （=跟随系统）或 API 33+ 原样返回 [base]。
 */
object LocaleOverride {

    /** 产出 Configuration.locales 以已存语言为首的 Context；attachBaseContext
     *  里调用安全（只用 base 自身的裸 SharedPreferences，不依赖 Application 状态）。 */
    fun wrap(base: Context): Context {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else savedLocale(base)
        return locale?.let { rebase(base, it) } ?: base
    }

    /** 读外观 prefs 里的语言码并解析；未设置或解析不了返回 null（= 不覆写）。 */
    private fun savedLocale(base: Context): Locale? {
        val code = base.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "").orEmpty()
        return code.takeIf { it.isNotEmpty() }?.let(::parseLanguageTag)
    }

    /** 把 [locale] 套到 base 的 Configuration 副本上，产出新 Context。 */
    private fun rebase(base: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            with(LocaleList(locale)) { LocaleList.setDefault(this); config.setLocales(this) }
        else @Suppress("DEPRECATION") { config.locale = locale }
        return base.createConfigurationContext(config)
    }

    /** 设置页存的 BCP-47-ish 标签（en / zh / ja 裸码）；带 - 或 _ 的
     *  zh-Hant 式标签交给 forLanguageTag。 */
    private fun parseLanguageTag(code: String): Locale? = when {
        code.isBlank() -> null
        code.any { it == '-' || it == '_' } -> Locale.forLanguageTag(code.replace('_', '-'))
        else -> Locale(code)
    }
}
