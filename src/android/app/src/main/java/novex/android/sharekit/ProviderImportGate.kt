package novex.android.sharekit

import android.app.Activity
import android.widget.Toast
import com.openminis.app.MinisApp
import com.openminis.app.R
import novex.android.logkit.RunLog

/**
 * provider 导出 JSON 的导入闸门（P3.5c 自 ShareReceiverActivity 的对话
 * 框/导入段真重写抽出）：识别为 provider 包后弹双选（导入为供应商 / 照常
 * 当附件），用户选择导入时跑 [MinisApp.providerRepositoryOrNull] 上的
 * importInstanceJSON 并提示结果。
 *
 * 全程不抛：分享接收路径跑在 onCreate 里，任何外漏异常都是
 * “Unable to start activity”，炸的是用户只是想分享进去的那个应用。
 * 安全模式进程下仓库可能从未赋值——null 走“导入失败”路径，绝不读裸
 * lateinit。
 */
object ProviderImportGate {

    /** 结果：接管=对话框已上屏并拥有流程；否则调用方自行走附件流。 */
    enum class Handled { TOOK_OVER, CANNOT_SHOW }

    private const val TAG = "ProviderImportGate"

    /**
     * 弹双选对话框。[onAttach] 是“照常当附件”的回退（含取消/返回键：
     * 摘要不应因为关窗而丢）。show() 在系统正在拆这个 Activity 时抛
     * BadTokenException——吞掉不报会两头落空（没对话框也没移交）。
     */
    fun offerImportOrAttach(
        activity: Activity,
        json: String,
        onAttach: () -> Unit,
    ): Handled = try {
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.share_provider_json_title))
            .setMessage(activity.getString(R.string.share_provider_json_message))
            .setPositiveButton(activity.getString(R.string.share_provider_json_import)) { _, _ ->
                runImport(activity, json)
                activity.finish()
            }
            .setNegativeButton(activity.getString(R.string.share_provider_json_attach)) { _, _ -> onAttach() }
            .setOnCancelListener { onAttach() } // 返回/点外 == 附件（既有行为）
            .show()
        Handled.TOOK_OVER
    } catch (t: Throwable) {
        RunLog.warning(TAG, "provider-import dialog could not be shown: ${t.message}")
        Handled.CANNOT_SHOW
    }

    private fun runImport(activity: Activity, json: String) {
        try {
            val label = repositoryOf(activity)?.importInstanceJSON(json)
            when {
                label != null -> {
                    RunLog.info(TAG, "imported provider \"$label\" from shared JSON")
                    toast(activity, activity.getString(R.string.share_provider_json_imported, label))
                }
                else -> {
                    RunLog.warning(TAG, "importInstanceJSON returned null")
                    toast(activity, activity.getString(R.string.share_provider_json_import_failed))
                }
            }
        } catch (t: Throwable) {
            RunLog.error(TAG, "provider import failed: ${t.message}")
        }
    }

    private fun repositoryOf(activity: Activity) =
        (activity.applicationContext as? MinisApp)?.providerRepositoryOrNull

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
