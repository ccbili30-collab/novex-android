package com.openminis.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Compatibility entry for old internal links; normal launcher starts stay in NovexLaunchActivity. */
class NovexHomeActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? MinisApp
        if (app == null || !app.startupCoordinator.state.value.minimumAvailable) {
            startActivity(Intent().setClassName(this, "com.openminis.app.NovexLaunchActivity").apply {
                action = Intent.ACTION_MAIN
            })
            finish()
            return
        }
        installNovexHomeSurface(app)
    }
}
