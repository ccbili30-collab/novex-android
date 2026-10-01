package com.openminis.app.crash

import android.util.Log
import java.io.File

/**
 * NDK 信号处理器的 JNI 装载入口（JNI 件，血统清剿 P3.7 就地重写）。
 *
 * 冻结面：
 * - 对象名/包路径/`nativeInstall` 方法名共同决定 cpp 侧导出符号
 *   `Java_com_openminis_app_crash_NativeCrashHandler_nativeInstall`
 *   （crash_handler.cpp 按此名绑定），三者任一改动都会在运行期 UnsatisfiedLinkError。
 * - 行为契约：懒装载（首次 [install] 才 dlopen，未启用原生捕获的进程不付代价）、
 *   失败只告警不抛（崩溃线绝不因装载失败二次引爆）、进程内最多装一次。
 *
 * 装好之后 SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGILL/SIGSYS 由 native 侧写
 * `<logsDir>/native-crash-<stamp>.log`（`native-crash-` 前缀 + `.log` 扩展名是
 * logkit 文件契约，RunLogFileContractTest 钉死），随后恢复默认处置让系统
 * tombstone 照常产生。
 */
object NativeCrashHandler {

    private const val TAG = "NativeCrashHandler"

    @Volatile
    private var armed = false

    /** 幂等装载：竞态双检 + 装载/注册任一步失败即放弃（下次调用也不再重试）。 */
    fun install(logsDir: File) {
        if (armed) return
        synchronized(this) {
            if (armed) return
            if (!loadNativeLibrary()) return
            try {
                logsDir.mkdirs()
                nativeInstall(logsDir.absolutePath)
                armed = true
                Log.i(TAG, "installed; dir=${logsDir.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "nativeInstall failed: ${t.message}")
            }
        }
    }

    private fun loadNativeLibrary(): Boolean = try {
        System.loadLibrary(LIB_NAME)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "loadLibrary failed: ${t.message}")
        false
    }

    private external fun nativeInstall(logDir: String)

    private const val LIB_NAME = "minis_crash_handler"
}
