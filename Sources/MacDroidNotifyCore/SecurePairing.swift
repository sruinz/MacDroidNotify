import CryptoKit
import Foundation

public struct SecurePairingPayload: Equatable, Sendable {
    public let host: String
    public let port: UInt16
    public let token: String
    public let macId: String
    public let tlsFingerprint: String

    public init(host: String, port: UInt16, token: String, macId: String, tlsFingerprint: String) {
        self.host = host
        self.port = port
        self.token = token
        self.macId = macId
        self.tlsFingerprint = TLSFingerprint.normalize(tlsFingerprint)
    }

    public var urlString: String {
        var components = URLComponents()
        components.scheme = "macdroidnotify"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "protocolVersion", value: "\(ProtocolLimits.version)"),
            URLQueryItem(name: "host", value: host),
            URLQueryItem(name: "port", value: "\(port)"),
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "macId", value: macId),
            URLQueryItem(name: "tlsFingerprint", value: tlsFingerprint),
        ]
        return components.string ?? ""
    }

    public static func parse(_ rawValue: String) -> SecurePairingPayload? {
        guard let components = URLComponents(string: rawValue),
              components.scheme == "macdroidnotify",
              components.host == "pair" else {
            return nil
        }
        let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).compactMap { item -> (String, String)? in
            guard let value = item.value else { return nil }
            return (item.name, value)
        })
        guard values["protocolVersion"].flatMap(Int.init) == ProtocolLimits.version,
              let host = values["host"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !host.isEmpty,
              let portValue = values["port"].flatMap(UInt16.init),
              let token = values["token"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty,
              let macId = values["macId"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !macId.isEmpty,
              let fingerprint = values["tlsFingerprint"],
              TLSFingerprint.isValid(fingerprint) else {
            return nil
        }
        return SecurePairingPayload(
            host: host,
            port: portValue,
            token: token,
            macId: macId,
            tlsFingerprint: fingerprint
        )
    }
}

public enum TLSFingerprint {
    public static func sha256Hex(for data: Data) -> String {
        SHA256.hash(data: data)
            .map { String(format: "%02X", $0) }
            .joined()
    }

    public static func normalize(_ value: String) -> String {
        value
            .filter { $0.isHexDigit }
            .map { String($0).uppercased() }
            .joined()
    }

    public static func isValid(_ value: String) -> Bool {
        let normalized = normalize(value)
        return normalized.count == 64 && normalized.allSatisfy(\.isHexDigit)
    }
}

public struct BonjourTXTRecord: Equatable, Sendable {
    public let macId: String
    public let tlsFingerprint: String

    public init(macId: String, tlsFingerprint: String) {
        self.macId = macId
        self.tlsFingerprint = TLSFingerprint.normalize(tlsFingerprint)
    }

    public var dictionary: [String: String] {
        [
            "protocolVersion": "\(ProtocolLimits.version)",
            "macId": macId,
            "tlsFingerprint": tlsFingerprint,
        ]
    }

    public static func parse(_ values: [String: String]) -> BonjourTXTRecord? {
        guard values["protocolVersion"].flatMap(Int.init) == ProtocolLimits.version,
              let macId = values["macId"], !macId.isEmpty,
              let fingerprint = values["tlsFingerprint"],
              TLSFingerprint.isValid(fingerprint) else {
            return nil
        }
        return BonjourTXTRecord(macId: macId, tlsFingerprint: fingerprint)
    }
}
