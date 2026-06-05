package dev.svrx.macdroidnotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val config = AppConfig(appContext).load()
        val debugLogStore = DebugLogStore(appContext)
        val action = intent?.action

        debugLogStore.append(
            "auto start broadcast action=$action autoStartEnabled=${config.autoStartEnabled} complete=${config.isComplete()}",
        )

        if (!AutoStartPolicy.shouldStart(config, action)) return

        try {
            ConnectionService.start(appContext)
            debugLogStore.append("auto start service requested action=$action")
        } catch (error: Exception) {
            debugLogStore.append("auto start failed ${error.javaClass.simpleName}: ${error.localizedMessage}")
            ConnectionStatusStore(appContext).save(
                ConnectionStatusSnapshot(
                    phase = ConnectionPhase.FAILED,
                    detail = "자동 시작 실패: ${error.localizedMessage ?: error.javaClass.simpleName}",
                ),
            )
        }
    }
}
