package dev.svrx.macdroidnotify

import java.lang.Thread.UncaughtExceptionHandler

class CrashLogHandler(
    private val debugLogStore: DebugLogStore,
    private val previousHandler: UncaughtExceptionHandler?,
) : UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        debugLogStore.appendLifecycle(
            "process crash thread=${thread.name} " +
                "error=${throwable.javaClass.simpleName}: ${throwable.localizedMessage.orEmpty()} " +
                "stack=${throwable.stackTraceToString().replaceLineBreaks().take(MAX_STACK_LENGTH)}",
        )
        previousHandler?.uncaughtException(thread, throwable)
    }

    private fun String.replaceLineBreaks(): String = replace("\n", " | ")

    private companion object {
        const val MAX_STACK_LENGTH = 3_000
    }
}
