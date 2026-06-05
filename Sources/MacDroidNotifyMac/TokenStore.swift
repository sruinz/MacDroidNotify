import Foundation
import MacDroidNotifyCore

final class TokenStore {
    private let key = "pairingToken"
    private let defaults = UserDefaults.standard

    func loadOrCreateToken() -> Data {
        if let value = defaults.string(forKey: key),
           let token = Data(base64URLEncoded: value),
           token.count == 32 {
            return token
        }

        let token = RandomToken.make(byteCount: 32)
        defaults.set(token.base64URLEncodedString(), forKey: key)
        return token
    }
}
