package dev.svrx.macdroidnotify

object AutoStartPolicy {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    fun shouldStart(config: PairingConfig, action: String?): Boolean {
        return config.autoStartEnabled &&
            config.isComplete() &&
            action in setOf(ACTION_BOOT_COMPLETED, ACTION_MY_PACKAGE_REPLACED)
    }
}
