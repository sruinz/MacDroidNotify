package dev.svrx.macdroidnotify

sealed interface OutboundWork {
    data class Clipboard(val text: String) : OutboundWork
    data class Notification(val payload: NotificationPayload) : OutboundWork
    data class Ping(val id: String) : OutboundWork
    data object TestNotification : OutboundWork
}

class OutboundWorkQueue(private val maxSize: Int = 100) {
    private val works = ArrayDeque<OutboundWork>()

    val size: Int
        get() = works.size

    fun enqueue(work: OutboundWork) {
        if (work is OutboundWork.Notification) {
            works.removeAll { it is OutboundWork.Notification && it.payload.id == work.payload.id }
        } else if (work is OutboundWork.Clipboard) {
            works.removeAll { it is OutboundWork.Clipboard }
        }
        while (works.size >= maxSize) {
            works.removeFirstOrNull()
        }
        works.addLast(work)
    }

    fun peek(): OutboundWork? = works.firstOrNull()

    fun dequeue(): OutboundWork? = works.removeFirstOrNull()
}
