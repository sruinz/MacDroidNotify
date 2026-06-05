import Foundation

final class LegacyDefaultsMigrator {
    private let legacyBundleId = "dev.svrx.macdroidnotify.mac"
    private let keys = ["pairingToken", "macId", "listenerPort"]

    func migrateIfNeeded() {
        guard Bundle.main.bundleIdentifier != legacyBundleId,
              let legacyDefaults = UserDefaults(suiteName: legacyBundleId) else {
            return
        }

        let currentDefaults = UserDefaults.standard
        for key in keys where currentDefaults.object(forKey: key) == nil {
            if let value = legacyDefaults.object(forKey: key) {
                currentDefaults.set(value, forKey: key)
            }
        }
    }
}
