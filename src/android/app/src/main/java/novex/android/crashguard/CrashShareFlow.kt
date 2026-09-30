package novex.android.crashguard

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.openminis.app.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 崩溃风暴的分享流程：文件多选 → 发送方式三选一（邮件 / 系统分享 /
 * 存到下载）。旧路径 [com.openminis.app.crash.CrashFrequencyDetector]
 * 门面把首个前台 Activity 的时机转发进来。
 *
 * 对话框用裸 View 手拼而非引 Material 库——为一个对话框背一个依赖
 * 不划算；圆角背景自绘（见 [paintRoundedChrome]）。
 */
internal object CrashShareFlow {

    /** 选单里崩溃日志的收录窗与上限（近 24h、最多 10 份，按新→旧）。 */
    private const val PICK_CRASH_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    private const val PICK_CRASH_CAP = 10

    /** 运行日志（minis-YYYY-MM-DD.log）的收录窗：近 3 天，默认不勾。 */
    private const val PICK_DAILY_WINDOW_MS: Long = 3L * 24L * 60L * 60L * 1000L

    /** 项目崩溃收件箱（公开别名，可随开源构建分发）。 */
    private const val REPORT_INBOX = "dev@openminis.app"

    private const val TAG = "CrashShareFlow"

    /** 多选清单条目：文件 + 展示行 + 分区 + 默认勾选位。 */
    private data class PickRow(
        val file: File,
        val line: String,
        val section: Int,
        val checkedByDefault: Boolean,
    )

    /**
     * 首个前台 Activity 回调进来时弹流程；一次性（先取走清单，重复
     * resume 不重弹）。没有待分享的风暴时只回调 [onClosed]（安全模
     * 式下宿主可能拿它当 finish 用，不能晾着不叫）。
     *
     * [saveLauncher] 是宿主 Activity 的 ACTION_CREATE_DOCUMENT 注册
     * 桥：本流程不在 Activity 里，无法自行 registerForActivityResult。
     * 缺省时「保存」退回公共下载目录 + 路径 toast 的老路。
     */
    fun offer(
        activity: Activity,
        onClosed: (() -> Unit)? = null,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)? = null,
    ) {
        val burst = CrashBurstGuard.pendingBurstFiles ?: run {
            onClosed?.invoke()
            return
        }
        CrashBurstGuard.clearPendingBurst()
        try {
            // 无论用户最后选哪条出口，此刻之后的崩溃才算下一轮——
            // 否则同一批文件会把下次冷启动再点着一次。
            CrashBurstGuard.stampDismissCheckpoint(activity)
            showFilePicker(activity, burst, onClosed, saveLauncher)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "share flow failed: ${t.message}")
            CrashBurstGuard.finishSafeMode()
            onClosed?.invoke()
        }
    }

    // ── 第一步：文件多选 ─────────────────────────────────────────────

    private fun showFilePicker(
        activity: Activity,
        burst: List<File>,
        onClosed: (() -> Unit)?,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)?,
    ) {
        val rows = pickRows(activity, burst)
        val checks = ArrayList<CheckBox>(rows.size)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        var lastSection = -1
        rows.forEachIndexed { index, row ->
            if (row.section != lastSection) {
                lastSection = row.section
                list.addView(TextView(activity).apply {
                    text = activity.getString(
                        if (row.section == 0) R.string.crash_freq_pick_section_crashes
                        else R.string.crash_freq_pick_section_logs,
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, if (index == 0) 0 else dp(12), 0, dp(4))
                    alpha = 0.6f
                })
            }
            val box = CheckBox(activity).apply {
                text = row.line
                isChecked = row.checkedByDefault
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            checks.add(box)
            list.addView(box)
        }

        val scroller = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(list)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.crash_freq_pick_files_title)
            .setView(scroller)
            .setPositiveButton(R.string.crash_freq_pick_continue) { _, _ ->
                val chosen = rows.filterIndexed { i, _ -> checks[i].isChecked }.map { it.file }
                if (chosen.isEmpty()) {
                    // 一个都不勾的「继续」等于主动放弃，按「暂不」收尾。
                    closeFlow(activity, onClosed)
                } else {
                    showDeliveryPicker(activity, chosen, onClosed, saveLauncher)
                }
            }
            .setNegativeButton(R.string.crash_freq_dialog_dismiss) { _, _ -> closeFlow(activity, onClosed) }
            .setOnCancelListener { closeFlow(activity, onClosed) }
            .create()
        paintRoundedChrome(activity, dialog)
        dialog.show()
    }

    /**
     * 组装选单。崩溃分区收近 24h 上限 10 份（触发清单里的文件无条件
     * 纳入，哪怕刚过窗）；运行日志分区收近 3 天，体量大、默认不勾，
     * 用户要上下文才自己勾。
     */
    private fun pickRows(context: Context, burst: List<File>): List<PickRow> {
        val logsDir = File(context.filesDir, "logs")
        val now = System.currentTimeMillis()
        val burstPaths = burst.map { it.absolutePath }.toHashSet()

        val crashes = (logsDir.listFiles { f -> CrashBurstGuard.isCrashLogName(f.name) }
            ?: emptyArray<File>())
            .filter { it.absolutePath in burstPaths || now - it.lastModified() <= PICK_CRASH_WINDOW_MS }
            .sortedByDescending { it.lastModified() }
            .take(PICK_CRASH_CAP)

        val dailies = (logsDir.listFiles { f ->
            f.name.startsWith("minis-") && f.name.endsWith(".log")
        } ?: emptyArray<File>())
            .filter { now - it.lastModified() <= PICK_DAILY_WINDOW_MS }
            .sortedByDescending { it.lastModified() }

        val stampFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val rows = mutableListOf<PickRow>()
        crashes.forEach { file ->
            rows += PickRow(
                file = file,
                line = "${file.name}  ·  ${readableSize(file.length())}  ·  " +
                    "${stampFmt.format(Date(file.lastModified()))}",
                section = 0,
                checkedByDefault = true,
            )
        }
        dailies.forEach { file ->
            rows += PickRow(
                file = file,
                line = "${file.name}  ·  ${readableSize(file.length())}",
                section = 1,
                checkedByDefault = false,
            )
        }
        return rows
    }

    // ── 第二步：发送方式三选一 ───────────────────────────────────────

    private fun showDeliveryPicker(
        activity: Activity,
        files: List<File>,
        onClosed: (() -> Unit)?,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)?,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val holder = arrayOfNulls<AlertDialog>(1)
        fun option(labelRes: Int, run: () -> Unit) {
            column.addView(TextView(activity).apply {
                text = activity.getString(labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(
                    activity, android.R.drawable.list_selector_background,
                )
                setOnClickListener {
                    holder[0]?.dismiss()
                    run()
                }
            })
        }
        option(R.string.crash_freq_method_email) { shareZip(activity, files, mailOnly = true, onClosed) }
        option(R.string.crash_freq_method_share) { shareZip(activity, files, mailOnly = false, onClosed) }
        option(R.string.crash_freq_method_save) {
            if (saveLauncher != null) saveViaPicker(activity, files, saveLauncher, onClosed)
            else saveToDownloads(activity, files, onClosed)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.crash_freq_method_title, files.size))
            .setView(column)
            .setNegativeButton(R.string.crash_freq_dialog_dismiss) { _, _ -> closeFlow(activity, onClosed) }
            .setOnCancelListener { closeFlow(activity, onClosed) }
            .create()
        paintRoundedChrome(activity, dialog)
        holder[0] = dialog
        dialog.show()
    }

    /** 把系统对话框的直角窗景换成 24dp 圆角（明暗主题色取自 colorBackground）。 */
    private fun paintRoundedChrome(activity: Activity, dialog: AlertDialog) {
        try {
            val density = activity.resources.displayMetrics.density
            val backdrop = TypedValue().let { resolved ->
                val color = if (activity.theme.resolveAttribute(
                        android.R.attr.colorBackground, resolved, true,
                    )
                ) {
                    resolved.data
                } else {
                    0xFFFFFFFF.toInt()
                }
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24f * density
                    setColor(color)
                }
            }
            dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(backdrop) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "rounded chrome failed: ${t.message}")
        }
    }

    /** 流程终点的统一出口：明确「暂不」的场合顺手压 24h 硬抑制。 */
    private fun closeFlow(optOutContext: Context?, onClosed: (() -> Unit)?) {
        if (optOutContext != null) {
            CrashBurstGuard.armSuppressWindow(optOutContext)
        }
        CrashBurstGuard.finishSafeMode()
        android.util.Log.i(TAG, "share flow closed")
        onClosed?.invoke()
    }

    // ── 出口一/二：邮件 / 系统分享 ───────────────────────────────────

    private fun shareZip(
        context: Context,
        files: List<File>,
        mailOnly: Boolean,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = bundleZip(context, files) ?: run {
                toast(context, context.getString(R.string.crash_freq_zip_failed, "no files"))
                closeFlow(null, onClosed)
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
            val subject = context.getString(
                R.string.crash_freq_email_subject,
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
            )
            val sent = if (mailOnly) {
                mailZip(context, uri, subject)
            } else {
                chooserZip(context, uri, subject)
            }
            // 邮件路径打不开（无邮件应用）就退通用分享面板，别让用户
            // 手里攥着 zip 没处送。
            if (!sent) chooserZip(context, uri, subject)
            // 给选择面板半秒浮出来的时间，再让宿主（可能拿着 finish）
            // 收尾。
            Handler(Looper.getMainLooper()).postDelayed({ closeFlow(null, onClosed) }, 500L)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "share dispatch failed: ${t.message}")
            toast(context, context.getString(R.string.crash_freq_zip_failed, t.message ?: ""))
            closeFlow(null, onClosed)
        }
    }

    /**
     * 邮件出口。主 Intent 必须是 ACTION_SEND：主流邮件客户端对
     * ACTION_SENDTO 会静默丢附件（收件人主题都在、附件没有）。要过滤
     * 到"只有邮件应用"，用 selector 挂 ACTION_SENDTO mailto:。
     */
    private fun mailZip(context: Context, attachment: Uri, subject: String): Boolean {
        return try {
            val mailOnly = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:") }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_INBOX))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.crash_freq_email_body))
                putExtra(Intent.EXTRA_STREAM, attachment)
                // 读权挂在外层 Intent 上，选择面板与最终收件应用都盖到。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                selector = mailOnly
            }
            // 带 selector 的 resolveActivity 在部分 OEM 上探不出来，
            // 直接探 selector 本身。
            if (mailOnly.resolveActivity(context.packageManager) == null) {
                android.util.Log.w(TAG, "no mail app resolves mailto:")
                return false
            }
            context.startActivity(send)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "mail launch failed: ${t.message}")
            false
        }
    }

    /** 通用分享面板出口（也是邮件出口的兜底）。 */
    private fun chooserZip(context: Context, attachment: Uri, subject: String): Boolean {
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, attachment)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                send, context.getString(R.string.crash_freq_share_chooser),
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "chooser launch failed: ${t.message}")
            false
        }
    }

    // ── 出口三：保存 ─────────────────────────────────────────────────

    /** 首选路径：系统文件选择器（宿主传入的 ACTION_CREATE_DOCUMENT 桥）。 */
    private fun saveViaPicker(
        context: Context,
        files: List<File>,
        saveLauncher: (zip: File, onSaveDone: () -> Unit) -> Unit,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = bundleZip(context, files) ?: run {
                toast(context, context.getString(R.string.crash_freq_zip_failed, "no files"))
                closeFlow(null, onClosed)
                return
            }
            // 安全模式解除与宿主 finish 都压到选择器回执之后：这里先
            // finish 的话，系统选择器就没有可返回的前台了。
            saveLauncher(zip) { closeFlow(null, onClosed) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "save (picker) failed: ${t.message}")
            toast(context, context.getString(R.string.crash_freq_save_failed, t.message ?: ""))
            closeFlow(null, onClosed)
        }
    }

    /** 兜底路径：直接写公共下载目录（Q+ 走 MediaStore 免权限）。 */
    private fun saveToDownloads(context: Context, files: List<File>, onClosed: (() -> Unit)?) {
        try {
            val zip = bundleZip(context, files) ?: run {
                toast(context, context.getString(R.string.crash_freq_zip_failed, "no files"))
                closeFlow(null, onClosed)
                return
            }
            val where: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val row = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                resolver.openOutputStream(target).use { out ->
                    requireNotNull(out) { "MediaStore openOutputStream returned null" }
                    FileInputStream(zip).use { it.copyTo(out) }
                }
                "Downloads/${zip.name}"
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                downloads.mkdirs()
                val dest = File(downloads, zip.name)
                FileInputStream(zip).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                dest.absolutePath
            }
            toast(context, context.getString(R.string.crash_freq_save_success, where))
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "save failed: ${t.message}")
            toast(context, context.getString(R.string.crash_freq_save_failed, t.message ?: ""))
        } finally {
            closeFlow(null, onClosed)
        }
    }

    // ── 打包 ─────────────────────────────────────────────────────────

    /**
     * 把文件们打进 `cacheDir/share/minis-logs-<时间戳>.zip`。放 cache
     * 目录图的是"用户分享完不再打开，系统自会清"；读不了的文件跳过。
     * 空集返回 null。
     */
    private fun bundleZip(context: Context, files: List<File>): File? {
        val usable = files.filter { it.exists() && it.length() > 0 }
        if (usable.isEmpty()) return null
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(shareDir, "minis-logs-$stamp.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            for (file in usable) {
                try {
                    zip.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "zip skip ${file.name}: ${t.message}")
                }
            }
        }
        return zipFile
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    private fun readableSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }
}
