package dev.svrx.macdroidnotify

object ClipboardText {
    fun sendableText(value: CharSequence?): String? {
        val text = value?.toString() ?: return null
        return text.takeIf { it.isNotBlank() }
    }
}
