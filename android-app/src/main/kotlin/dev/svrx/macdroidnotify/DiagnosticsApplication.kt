package dev.svrx.macdroidnotify

import android.app.Application

class DiagnosticsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashLogHandler(
                DebugLogStore(this),
                Thread.getDefaultUncaughtExceptionHandler(),
            ),
        )
    }
}
