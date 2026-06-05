import CryptoKit
import Foundation

public enum RandomToken {
    public static func make(byteCount: Int = 32) -> Data {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(byteCount)
        for _ in 0..<byteCount {
            bytes.append(UInt8.random(in: UInt8.min...UInt8.max))
        }
        return Data(bytes)
    }

    public static func nonce() -> String {
        make(byteCount: 24).base64URLEncodedString()
    }
}
