import CryptoKit
import Foundation

public enum PairingAuth {
    public static func authCode(token: Data, nonce: String) -> String {
        let key = SymmetricKey(data: token)
        let mac = HMAC<SHA256>.authenticationCode(for: Data(nonce.utf8), using: key)
        return Data(mac).base64URLEncodedString()
    }

    public static func verify(token: Data, nonce: String, auth: String) -> Bool {
        let expected = authCode(token: token, nonce: nonce)
        return constantTimeEquals(expected, auth)
    }

    private static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        guard left.count == right.count else {
            return false
        }
        var diff: UInt8 = 0
        for index in left.indices {
            diff |= left[index] ^ right[index]
        }
        return diff == 0
    }
}
