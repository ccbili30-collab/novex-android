@file:Suppress("unused")

package com.openminis.app.deeplink

/**
 * minis:// 深链的解析门面（P3.5c 真重写收编）。
 *
 * 解析实现在 [novex.android.navlink.LinkParser]（URI 表、settings 路径
 * 别名、未知路径回落语义均为冻结面）；[DeepLinkAction] 密封族与
 * [DeepLinkCoordinator] 的嵌套类型被 MainActivity / AppNavigation /
 * ChatScreen / 日志页与 androidTest 以 `DeepLinkAction.X` /
 * `DeepLinkCoordinator.ChatAction` 形态钉在本包——嵌套类型无法经
 * typealias 转发，故正典留此、实现侧反向引用。
 */
typealias DeepLinkHandler = novex.android.navlink.LinkParser

/** 深链解析产物（形状冻结：各 case 名与字段被 UI when 分支钉住）。 */
sealed class DeepLinkAction {
    data object OpenShare : DeepLinkAction()
    data class OpenSession(val sessionId: String) : DeepLinkAction()

    /** 图标长按快捷：minis://action/<name>，快捷方式 XML 直用 Intent.data。 */
    data object NewChat : DeepLinkAction()
    data object NewCameraChat : DeepLinkAction()

    /** 到达任意设置页；带参路由已拼好（"provider/<id>"、"model_group/<id>"）。 */
    data class OpenSettingsScreen(val route: String) : DeepLinkAction()

    data object Unknown : DeepLinkAction()
}
