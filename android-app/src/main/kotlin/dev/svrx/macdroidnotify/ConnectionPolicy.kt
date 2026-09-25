package dev.svrx.macdroidnotify

object ConnectionPolicy {
    fun shouldAttemptConnection(hasWifiTransport: Boolean): Boolean = hasWifiTransport
}
