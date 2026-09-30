package novex.android.sharekit

import com.openminis.app.share.ShareHandoffPolicy.Outcome

/**
 * 分享接收 Activity 移交主界面的重试阶梯（P3.5c 自 share/ShareHandoffPolicy
 * 真重写），抽出来是因为它可单测而 startActivity 本身不可。
 *
 * 起因（vivo V2352A / Android 14 实录）：分享进应用立刻连环崩溃
 * （HangDetector 记到 restartCount=50）。异常发自 startActivity：
 *
 *     RuntimeException: Unable to start activity …ShareReceiverActivity
 *     Caused by: NullPointerException … String.equals(Object) on null
 *     Caused by: RemoteException: Remote stack trace:
 *       at VivoActivityStarterImpl.generateLaunchFreeFormOption(:2195)
 *
 * 病灶全在 OEM 系统进程的自由窗逻辑：system_server NPE， marshal 回来的
 * 异常 message 又是 null，Parcel.readException 重建它时再 NPE 一次；两
 * 下都落在我们主线程上，一次不加护的调用就能带走整个进程。
 *
 * 真正的 Activity 活（拼 Intent、调 startActivity）要有活 Context，
 * JVM 上跑不了；能测的是决策层——试几次、按什么顺序、每一步抛了怎么
 * 办。[handOff] 把各步收成 lambda，测试想叫哪步炸就叫哪步炸。
 *
 * 参数名（primary / fallback / onError）与 [Outcome] 各值是同包旧测试
 * 钉住的冻结面。
 */
object ShareHandoffLadder {

    /**
     * 先跑 [primary]，抛了再跑 [fallback]，报告哪个成了。
     *
     * 刻意接 [Throwable] 而非 Exception：实录的顶层是 RuntimeException，
     * 但解 Binder 包的过程中可能翻出别的 Error，而一次尽力而为的移交
     * 里没有任何值得为它赔上进程的失败模式。所以本函数永不抛——这个
     * 保证就是它存在的全部意义（调用点在 onCreate，任何外漏都会变成
     * “Unable to start activity” 直接带走应用）。
     *
     * @param onError 每次失败回调（带阶段名），它自己抛也一并吞掉——
     *   坏掉的日志器不能击穿这层防护。
     */
    fun handOff(
        primary: () -> Unit,
        fallback: () -> Unit,
        onError: (stage: String, error: Throwable) -> Unit = { _, _ -> },
    ): Outcome {
        val rungs = listOf(
            "primary" to (primary to Outcome.LAUNCHED),
            "fallback" to (fallback to Outcome.LAUNCHED_VIA_FALLBACK),
        )
        for ((stage, attempt) in rungs) {
            val (action, ifLanded) = attempt
            val failure = runCatching(action).exceptionOrNull() ?: return ifLanded
            report(onError, stage, failure)
        }
        return Outcome.FAILED
    }

    /** 日志回调自身也吞异常——坏掉的日志器不能击穿这层防护。 */
    private fun report(onError: (String, Throwable) -> Unit, stage: String, t: Throwable) =
        runCatching { onError(stage, t) }
}
