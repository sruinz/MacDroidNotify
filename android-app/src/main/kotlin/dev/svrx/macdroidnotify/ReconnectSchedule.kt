package dev.svrx.macdroidnotify

class ReconnectSchedule(
    private val baseDelayMillis: Long,
    private val maxDelayMillis: Long,
    private val maxAttempts: Int = 3,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var delayMillis = baseDelayMillis
    private var nextAttemptAtMillis = 0L
    private var remainingAttempts = maxAttempts

    fun canAttempt(nowMillis: Long = this.nowMillis()): Boolean {
        return remainingAttempts > 0 && nowMillis >= nextAttemptAtMillis
    }

    fun recordFailure(nowMillis: Long = this.nowMillis()) {
        if (remainingAttempts <= 0) return
        remainingAttempts -= 1
        if (remainingAttempts == 0) {
            nextAttemptAtMillis = Long.MAX_VALUE
        } else {
            nextAttemptAtMillis = nowMillis + delayMillis
            delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
        }
    }

    fun recordSuccess() {
        delayMillis = baseDelayMillis
        nextAttemptAtMillis = 0
        remainingAttempts = maxAttempts
    }

    fun wake(nowMillis: Long = this.nowMillis()) {
        delayMillis = baseDelayMillis
        nextAttemptAtMillis = 0
        remainingAttempts = maxAttempts
    }

    fun isExhausted(): Boolean {
        return remainingAttempts <= 0
    }
}
