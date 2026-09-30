package com.openminis.app.auth

import android.app.Activity
import android.os.Bundle
import novex.android.authkit.LoopbackRedirectRelay

/**
 * Manifest 壳（组件名冻结在 AndroidManifest 的 intent-filter 上，不能迁）：
 * 接住系统路由来的 localhost OAuth 重定向，转发逻辑全部在
 * [LoopbackRedirectRelay]。NoDisplay 主题、onCreate 即 finish，用户无感。
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LoopbackRedirectRelay.forward(intent?.data)
        finish()
    }
}
