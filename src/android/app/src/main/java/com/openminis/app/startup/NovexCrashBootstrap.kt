package com.openminis.app.startup

import android.app.Application
import com.openminis.app.BuildConfig
import com.openminis.app.crash.CrashFrequencyDetector
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat

/** Keeps crash-report implementation classes outside the manifest-loaded Application class. */
object NovexCrashBootstrap {
    fun install(application: Application) {
        ACRA.init(
            application,
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.JSON)
                .withLogcatArguments(listOf("-t", "200", "-v", "time"))
                .withReportContent(
                    ReportField.APP_VERSION_NAME,
                    ReportField.APP_VERSION_CODE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                ),
        )
    }

    fun isReporterProcess(): Boolean = ACRA.isACRASenderServiceProcess()

    fun detectSafeMode(application: Application): Boolean {
        CrashFrequencyDetector.checkAtLaunch(application)
        return CrashFrequencyDetector.isSafeMode()
    }
}
