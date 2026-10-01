package com.openminis.app.share

import novex.android.sharekit.ShareHandoffLadder

/**
 * 移交重试阶梯的旧路径门面（P3.5c 真重写收编）。
 *
 * 机制与档案（vivo V2352A 的 OEM 自由窗 NPE 实录、Throwable 级防护的
 * 理由）在 [ShareHandoffLadder]。[Outcome] 枚举被同包旧测试以
 * `ShareHandoffPolicy.Outcome` 形态钉住，无法经 typealias 转发——正典
 * 留此、实现侧反向引用。handOff 的参数名（primary / fallback /
 * onError）与永不抛的保证均为冻结面。
 */
object ShareHandoffPolicy {

    enum class Outcome { LAUNCHED, LAUNCHED_VIA_FALLBACK, FAILED }

    fun handOff(
        primary: () -> Unit,
        fallback: () -> Unit,
        onError: (stage: String, error: Throwable) -> Unit = { _, _ -> },
    ): Outcome = ShareHandoffLadder.handOff(primary, fallback, onError)
}
