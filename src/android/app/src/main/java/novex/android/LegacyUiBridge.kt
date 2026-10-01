@file:Suppress("unused")

package novex.android

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import novex.android.ui.cards.introductionModule as introductionModuleImpl
import novex.android.ui.cards.moduleExcerptText as moduleExcerptTextImpl

/**
 * 包根归位（D1，架构质量审计 2026-09-30）后的旧 UI 线桥接门面。
 *
 * 包根 20 件散文件已全部归位：卡片族 19 件（UI 页 + ViewModel + 阅读视图
 * 偏好 + Markdown 变换）进 [novex.android.ui.cards]，宿主路径解析进
 * [novex.android.data.ContentPaths]。本文件仅为尚未搬迁/重写的旧世界
 * 调用方保留 `novex.android.X` 旧名字——它们集中在 com.openminis.app.ui
 * （UI 清洗线战场，本刀不碰）与 MinisApp/AppNavigation 的全限定引用。
 *
 * 拆除条件（详见 docs/UPSTREAM_EXIT_PLAN.md「门面拆除排期」）：
 *  - ContentPaths：UI 清洗线重写 MarkdownText/StreamingMarkdownText/
 *    MinisImageFetcher/FileBrowserViewModel/ChatLinkResolver 等消费件时改引
 *    `novex.android.data.ContentPaths`；
 *  - 卡片族符号（CardSessionModel/LibraryModel/FileTransferModel/
 *    CardThumbnail/ReadingViewPrefs/CardImportProvider/introductionModule/
 *    moduleExcerptText）：noven 屏（NovenComponents/NovenSessionRow/
 *    NovenHomeScreen 等）随 UI 线改引 `novex.android.ui.cards.*`，
 *    MinisApp/AppNavigation 同步改引后即可删除本文件。
 * 新代码一律直接 import 归位后的新路径，禁止再引用本桥。
 */
typealias ContentPaths = novex.android.data.ContentPaths
typealias CardSessionModel = novex.android.ui.cards.CardSessionModel
typealias LibraryModel = novex.android.ui.cards.LibraryModel
typealias FileTransferModel = novex.android.ui.cards.FileTransferModel
typealias ReadingViewPrefs = novex.android.ui.cards.ReadingViewPrefs
typealias CardImportProvider = novex.android.ui.cards.CardImportProvider

/** 函数无法 typealias，保留同签名转发；参数与默认值与本体逐字一致。 */
internal fun novex.content.ContentDocument.introductionModule(): novex.content.ContentModule? =
    introductionModuleImpl()

internal fun moduleExcerptText(value: String): String = moduleExcerptTextImpl(value)

@androidx.compose.runtime.Composable
internal fun CardThumbnail(
    ref: novex.content.ContentRef,
    model: novex.android.ui.cards.CardSessionModel,
    description: String = "图片缩略图",
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier.size(56.dp),
    maxEdge: Int = 256,
) = novex.android.ui.cards.CardThumbnail(ref, model, description, modifier, maxEdge)
