package dev.svrx.macdroidnotify

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
        val component = ComponentName(context, NotificationMirrorListener::class.java)
        return isComponentEnabled(
            enabled,
            setOf(component.flattenToString(), component.flattenToShortString()),
        )
    }

    fun statusText(context: Context): String {
        return if (isEnabled(context)) "허용됨" else "필요함"
    }

    internal fun isComponentEnabled(enabledListeners: String?, targetComponent: String): Boolean {
        return isComponentEnabled(enabledListeners, setOf(targetComponent))
    }

    private fun isComponentEnabled(enabledListeners: String?, targetComponents: Set<String>): Boolean {
        if (enabledListeners.isNullOrBlank()) return false
        return enabledListeners.split(":")
            .map { it.trim() }
            .any { enabled ->
                targetComponents.any { target -> enabled.equals(target, ignoreCase = true) }
            }
    }
}
