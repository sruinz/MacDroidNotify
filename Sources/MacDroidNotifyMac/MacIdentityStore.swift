import Foundation

final class MacIdentityStore {
    private let key = "macId"

    func loadOrCreateMacId() -> String {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: key), !saved.isEmpty {
            return saved
        }
        let created = UUID().uuidString.lowercased()
        defaults.set(created, forKey: key)
        return created
    }
}
