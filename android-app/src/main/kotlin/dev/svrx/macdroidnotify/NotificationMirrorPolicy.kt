package dev.svrx.macdroidnotify

object NotificationMirrorPolicy {
    fun shouldForwardNotification(serviceEnabled: Boolean): Boolean {
        return serviceEnabled
    }
}
