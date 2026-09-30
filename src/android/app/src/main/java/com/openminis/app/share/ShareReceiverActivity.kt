package com.openminis.app.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import novex.android.logkit.RunLog
import novex.android.sharekit.InboundShareIntake
import novex.android.sharekit.ShareHandoffLadder
import novex.android.sharekit.ProviderImportGate
import novex.android.sharekit.ShareInbox

/**
 * 分享接收 Activity 的 Manifest 壳（P3.5c 真重写收编；组件名与
 * intent-filter 冻结在 AndroidManifest）。提货/落盘/识别逻辑在
 * [InboundShareIntake]，盘上收件箱在 [ShareInbox]，移交重试阶梯在
 * [ShareHandoffLadder]。此处只留编排、双选对话框与提示。
 *
 * 线上形态对齐 iOS 分享扩展（{items: [{kind, value}], timestamp}）。
 * ACTION_VIEW 与 ACTION_SEND 的下游路径（收件箱 → 主界面 → 合成器
 * 预填）完全一致，只是载荷位置不同（data vs EXTRA_STREAM）。
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    // T-n01-andmenu-l10n：Tiramisu 前的语言覆写（同主界面）。
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = mutableListOf<PendingShare.Item>()
        try {
            when (intent?.action) {
                Intent.ACTION_SEND -> drainSingle(intent, items)
                Intent.ACTION_SEND_MULTIPLE -> drainMultiple(intent, items)
                Intent.ACTION_VIEW -> drainView(intent, items)
                else -> RunLog.warning(TAG, "unhandled action: ${intent?.action}")
            }
        } catch (e: Throwable) {
            RunLog.error(TAG, "extraction failed: ${e.message}")
        }

        // 恰好一条且解析为 provider 导出 JSON 时给双选：导入为供应商，
        // 或照常当聊天附件。多条分享无条件走附件批（不含糊）。整段
        // 加护：onCreate 里漏出去的任何异常都是“Unable to start
        // activity …ShareReceiverActivity”，炸的是用户只是想分享进去
        // 的那个应用——可选项失败必须降级为正常附件流。
        val providerJson = try {
            items.singleOrNull()?.let {
                InboundShareIntake.providerExportJsonOrNull(it, ShareInbox.sharedFileDirectory(this))
            }
        } catch (t: Throwable) {
            RunLog.warning(TAG, "provider-JSON detection failed: ${t.message}")
            null
        }
        if (providerJson != null &&
            ProviderImportGate.offerImportOrAttach(this, providerJson) { finishWithAttachmentFlow(items) } ==
            ProviderImportGate.Handled.TOOK_OVER
        ) return

        finishWithAttachmentFlow(items)
    }

    // ── 提货（薄转发到 sharekit）──────────────────────────────────────

    private fun drainSingle(intent: Intent, out: MutableList<PendingShare.Item>) =
        InboundShareIntake.drainSingleSend(
            intent,
            openStream = { uri -> runCatching { contentResolver.openInputStream(uri) }.getOrNull() },
            stagingDir = ShareInbox.sharedFileDirectory(this),
            out = out,
        )

    private fun drainMultiple(intent: Intent, out: MutableList<PendingShare.Item>) =
        InboundShareIntake.drainMultipleSend(
            intent,
            openStream = { uri -> runCatching { contentResolver.openInputStream(uri) }.getOrNull() },
            stagingDir = ShareInbox.sharedFileDirectory(this),
            out = out,
        )

    private fun drainView(intent: Intent, out: MutableList<PendingShare.Item>) =
        InboundShareIntake.drainView(
            intent,
            openStream = { uri -> runCatching { contentResolver.openInputStream(uri) }.getOrNull() },
            resolveType = { uri -> contentResolver.getType(uri) },
            stagingDir = ShareInbox.sharedFileDirectory(this),
            out = out,
        )

    // ── 收尾 ───────────────────────────────────────────────────────────

    /**
     * 条目持久化进收件箱（合成器稍后接手）再移交主界面。非 provider
     * 导入的一切分享都走这条。
     */
    private fun finishWithAttachmentFlow(items: List<PendingShare.Item>) {
        try {
            if (items.isNotEmpty()) {
                ShareInbox.savePendingShare(this, PendingShare(items, System.currentTimeMillis()))
            } else {
                RunLog.info(TAG, "no shareable items extracted")
            }
        } catch (e: Throwable) {
            RunLog.error(TAG, "savePendingShare failed: ${e.message}")
        }
        // 移交永不抛；finish 同样加护——同一条 onCreate 防线。
        handOffToMainActivity()
        try {
            finish()
        } catch (t: Throwable) {
            RunLog.warning(TAG, "finish() failed: ${t.message}")
        }
    }

    /**
     * 移交主界面，容忍失败。病根在 OEM 自由窗逻辑（vivo 实录见
     * [ShareHandoffLadder] 的档案），Intent 本身没毛病、也没有版本/机型
     * 判断能预判——唯一可做的防御就是不让一次失败的移交杀进程。分享
     * 此刻已持久化，用户下次从任何入口进应用都能取到。
     *
     * 备胎去掉 FLAG_ACTIVITY_CLEAR_TOP：崩溃发生在系统决定任务摆放的
     * 当口，启动旗标是请求里我们唯一能改的部分，纯 NEW_TASK 是唯一
     * 有实质差别的重试。
     */
    private fun handOffToMainActivity() {
        val outcome = ShareHandoffLadder.handOff(
            primary = {
                startActivity(mainActivityIntent(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            },
            fallback = {
                startActivity(mainActivityIntent(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onError = { stage, t ->
                RunLog.error(
                    TAG,
                    "$stage startActivity(MainActivity) failed: ${t.javaClass.simpleName}: ${t.message}",
                )
            },
        )

        when (outcome) {
            ShareHandoffPolicy.Outcome.LAUNCHED -> Unit
            ShareHandoffPolicy.Outcome.LAUNCHED_VIA_FALLBACK ->
                RunLog.info(TAG, "MainActivity launched via NEW_TASK-only fallback")
            ShareHandoffPolicy.Outcome.FAILED -> {
                // 分享已持久化，告诉用户怎么收场——否则在他们看来分享
                // 什么都没做。
                runCatching {
                    android.widget.Toast.makeText(
                        this, getString(com.openminis.app.R.string.share_open_app_failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { RunLog.warning(TAG, "failure toast failed: ${it.message}") }
            }
        }
    }

    private fun mainActivityIntent(flags: Int): Intent =
        Intent(this, Class.forName("com.openminis.app.MainActivity")).apply {
            addFlags(flags)
            putExtra("shared_content", true)
        }
}
