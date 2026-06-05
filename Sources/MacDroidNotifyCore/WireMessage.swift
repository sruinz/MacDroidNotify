import Foundation

public enum WireMessage: Equatable, Sendable {
    case challenge(ChallengePayload)
    case hello(HelloPayload)
    case pairingAccepted(PairingAcceptedPayload)
    case notificationPosted(NotificationPayload)
    case clipboardToMac(ClipboardPayload)
    case clipboardToAndroid(ClipboardPayload)
    case ping(PingPayload)
    case pong(PongPayload)
}

extension WireMessage: Codable {
    private enum CodingKeys: String, CodingKey {
        case type
        case protocolVersion
        case nonce
        case deviceId
        case deviceName
        case auth
        case macName
        case id
        case packageName
        case appName
        case title
        case text
        case timestampMillis
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "challenge":
            self = .challenge(ChallengePayload(
                protocolVersion: try container.decode(Int.self, forKey: .protocolVersion),
                nonce: try container.decode(String.self, forKey: .nonce)
            ))
        case "hello":
            self = .hello(HelloPayload(
                protocolVersion: try container.decode(Int.self, forKey: .protocolVersion),
                deviceId: try container.decode(String.self, forKey: .deviceId),
                deviceName: try container.decode(String.self, forKey: .deviceName),
                auth: try container.decode(String.self, forKey: .auth)
            ))
        case "pairing.accepted":
            self = .pairingAccepted(PairingAcceptedPayload(
                macName: try container.decode(String.self, forKey: .macName),
                timestampMillis: try container.decode(Int64.self, forKey: .timestampMillis)
            ))
        case "notification.posted":
            self = .notificationPosted(NotificationPayload(
                id: try container.decode(String.self, forKey: .id),
                packageName: try container.decode(String.self, forKey: .packageName),
                appName: try container.decode(String.self, forKey: .appName),
                title: try container.decode(String.self, forKey: .title),
                text: try container.decode(String.self, forKey: .text),
                timestampMillis: try container.decode(Int64.self, forKey: .timestampMillis)
            ))
        case "clipboard.toMac":
            self = .clipboardToMac(ClipboardPayload(
                text: try container.decode(String.self, forKey: .text),
                timestampMillis: try container.decode(Int64.self, forKey: .timestampMillis)
            ))
        case "clipboard.toAndroid":
            self = .clipboardToAndroid(ClipboardPayload(
                text: try container.decode(String.self, forKey: .text),
                timestampMillis: try container.decode(Int64.self, forKey: .timestampMillis)
            ))
        case "ping":
            self = .ping(PingPayload(
                id: try container.decode(String.self, forKey: .id),
                timestampMillis: try container.decode(Int64.self, forKey: .timestampMillis)
            ))
        case "pong":
            self = .pong(PongPayload(
                id: try container.decode(String.self, forKey: .id),
                timestampMillis: try container.decode(Int64.self, forKey: .timestampMillis)
            ))
        default:
            throw ProtocolError.invalidMessageType(type)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case let .challenge(payload):
            try container.encode("challenge", forKey: .type)
            try container.encode(payload.protocolVersion, forKey: .protocolVersion)
            try container.encode(payload.nonce, forKey: .nonce)
        case let .hello(payload):
            try container.encode("hello", forKey: .type)
            try container.encode(payload.protocolVersion, forKey: .protocolVersion)
            try container.encode(payload.deviceId, forKey: .deviceId)
            try container.encode(payload.deviceName, forKey: .deviceName)
            try container.encode(payload.auth, forKey: .auth)
        case let .pairingAccepted(payload):
            try container.encode("pairing.accepted", forKey: .type)
            try container.encode(payload.macName, forKey: .macName)
            try container.encode(payload.timestampMillis, forKey: .timestampMillis)
        case let .notificationPosted(payload):
            let limited = payload.limited()
            try container.encode("notification.posted", forKey: .type)
            try container.encode(limited.id, forKey: .id)
            try container.encode(limited.packageName, forKey: .packageName)
            try container.encode(limited.appName, forKey: .appName)
            try container.encode(limited.title, forKey: .title)
            try container.encode(limited.text, forKey: .text)
            try container.encode(limited.timestampMillis, forKey: .timestampMillis)
        case let .clipboardToMac(payload):
            let validated = try payload.validated()
            try container.encode("clipboard.toMac", forKey: .type)
            try container.encode(validated.text, forKey: .text)
            try container.encode(validated.timestampMillis, forKey: .timestampMillis)
        case let .clipboardToAndroid(payload):
            let validated = try payload.validated()
            try container.encode("clipboard.toAndroid", forKey: .type)
            try container.encode(validated.text, forKey: .text)
            try container.encode(validated.timestampMillis, forKey: .timestampMillis)
        case let .ping(payload):
            try container.encode("ping", forKey: .type)
            try container.encode(payload.id, forKey: .id)
            try container.encode(payload.timestampMillis, forKey: .timestampMillis)
        case let .pong(payload):
            try container.encode("pong", forKey: .type)
            try container.encode(payload.id, forKey: .id)
            try container.encode(payload.timestampMillis, forKey: .timestampMillis)
        }
    }
}
