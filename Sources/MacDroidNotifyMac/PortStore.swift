import Foundation
import MacDroidNotifyCore

final class PortStore {
    private let key = "listenerPort"
    private let defaults = UserDefaults.standard

    func loadPort() -> UInt16 {
        let value = defaults.integer(forKey: key)
        guard value >= Int(NetworkPort.minimumUserValue),
              value <= Int(NetworkPort.maximumValue) else {
            return NetworkPort.defaultValue
        }
        return UInt16(value)
    }

    func save(_ port: UInt16) {
        defaults.set(Int(port), forKey: key)
    }
}
