package com.openminis.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import novex.android.runtime.AgentKeepAlive

/**
 * 对话后台保活的 Manifest 壳。
 *
 * 组件名与本类绑定（AndroidManifest.xml 的
 * `.service.AgentForegroundService` + mediaPlayback 前台类型是冻结
 * 面，系统与用户已认得这个标识），故类钉在旧路径；全部行为逻辑——
 * 前台身份、唤醒锁、通知装配、悬浮胶囊观察者——都在
 * [AgentKeepAlive]，本类只把生命周期回调递过去。
 */
class AgentForegroundService : Service() {

    private val brain: AgentKeepAlive by lazy { AgentKeepAlive(this) }

    override fun onCreate() {
        super.onCreate()
        brain.onCreated()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        brain.onStartCommand(intent, startId)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        brain.onTaskRemoved()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        brain.onScreenGeometryChanged(newConfig)
    }

    override fun onDestroy() {
        brain.onDestroyed()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"

        /** 以当前状态起服务或原位刷新常驻行。 */
        fun startService(context: Context, sessionCount: Int, toolStatus: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
                putExtra(EXTRA_TOOL_STATUS, toolStatus)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停服务（流式与在场两桶全空时由中枢调用）。 */
        fun stopService(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
