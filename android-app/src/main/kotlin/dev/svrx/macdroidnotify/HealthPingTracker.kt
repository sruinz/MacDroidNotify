package dev.svrx.macdroidnotify

class HealthPingTracker(
    private val timeoutMillis: Long,
) {
    private var pendingId: String? = null
    private var sentAtMillis: Long = 0L

    fun canSend(): Boolean = pendingId == null

    fun markSent(id: String, nowMillis: Long) {
        pendingId = id
        sentAtMillis = nowMillis
    }

    fun markPong(id: String, nowMillis: Long): Long? {
        if (id != pendingId) return null
        val rttMillis = nowMillis - sentAtMillis
        clear()
        return rttMillis
    }

    fun timedOut(nowMillis: Long): String? {
        val id = pendingId ?: return null
        return if (nowMillis - sentAtMillis >= timeoutMillis) id else null
    }

    fun clear() {
        pendingId = null
        sentAtMillis = 0L
    }
}
