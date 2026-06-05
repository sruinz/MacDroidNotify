package dev.svrx.macdroidnotify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationMirrorListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        log("listener connected active=${runCatching { activeNotifications?.size ?: 0 }.getOrDefault(-1)}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        log("listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        when (val result = extractPayloadResult(sbn)) {
            is ExtractionResult.Accepted -> {
                val payload = result.payload
                log(
                    "listener accepted package=${payload.packageName} app=${payload.appName} " +
                        "titleLen=${payload.title.length} textLen=${payload.text.length}",
                )
                try {
                    ConnectionService.sendNotification(this, payload)
                } catch (error: Exception) {
                    log("listener send failed ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            is ExtractionResult.Rejected -> {
                log("listener rejected package=${sbn.packageName} reason=${result.reason}")
            }
        }
    }

    internal fun extractPayload(sbn: StatusBarNotification): NotificationPayload? {
        return (extractPayloadResult(sbn) as? ExtractionResult.Accepted)?.payload
    }

    private fun extractPayloadResult(sbn: StatusBarNotification): ExtractionResult {
        if (sbn.packageName == packageName) return ExtractionResult.Rejected("self")
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return ExtractionResult.Rejected("ongoing")
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        )?.toString().orEmpty()

        if (title.isBlank() && text.isBlank()) return ExtractionResult.Rejected("blank")

        return ExtractionResult.Accepted(
            NotificationPayload(
                id = sbn.key,
                packageName = sbn.packageName,
                appName = appNameFor(sbn.packageName),
                title = title,
                text = text,
                timestampMillis = sbn.postTime,
            ).limited(),
        )
    }

    private fun appNameFor(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun log(message: String) {
        DebugLogStore(applicationContext).append(message)
    }

    private sealed class ExtractionResult {
        data class Accepted(val payload: NotificationPayload) : ExtractionResult()
        data class Rejected(val reason: String) : ExtractionResult()
    }

    companion object {
        fun requestListenerRebind(context: Context) {
            DebugLogStore(context).append("listener rebind requested")
            if (Build.VERSION.SDK_INT >= 24) {
                requestRebind(ComponentName(context, NotificationMirrorListener::class.java))
            }
        }
    }
}
