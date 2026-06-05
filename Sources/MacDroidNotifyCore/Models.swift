import Foundation

public struct ChallengePayload: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let nonce: String

    public init(protocolVersion: Int = ProtocolLimits.version, nonce: String) {
        self.protocolVersion = protocolVersion
        self.nonce = nonce
    }
}

public struct HelloPayload: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let deviceId: String
    public let deviceName: String
    public let auth: String

    public init(protocolVersion: Int = ProtocolLimits.version, deviceId: String, deviceName: String, auth: String) {
        self.protocolVersion = protocolVersion
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.auth = auth
    }
}

public struct PairingAcceptedPayload: Codable, Equatable, Sendable {
    public let macName: String
    public let timestampMillis: Int64

    public init(macName: String, timestampMillis: Int64) {
        self.macName = macName
        self.timestampMillis = timestampMillis
    }
}

public struct NotificationPayload: Codable, Equatable, Sendable {
    public let id: String
    public let packageName: String
    public let appName: String
    public let title: String
    public let text: String
    public let timestampMillis: Int64

    public init(id: String, packageName: String, appName: String, title: String, text: String, timestampMillis: Int64) {
        self.id = id
        self.packageName = packageName
        self.appName = appName
        self.title = title
        self.text = text
        self.timestampMillis = timestampMillis
    }

    public func limited() -> NotificationPayload {
        NotificationPayload(
            id: id,
            packageName: packageName,
            appName: appName,
            title: title.limitedCharacters(to: ProtocolLimits.maxNotificationTextCharacters),
            text: text.limitedCharacters(to: ProtocolLimits.maxNotificationTextCharacters),
            timestampMillis: timestampMillis
        )
    }
}

public struct PingPayload: Codable, Equatable, Sendable {
    public let id: String
    public let timestampMillis: Int64

    public init(id: String, timestampMillis: Int64) {
        self.id = id
        self.timestampMillis = timestampMillis
    }
}

public struct PongPayload: Codable, Equatable, Sendable {
    public let id: String
    public let timestampMillis: Int64

    public init(id: String, timestampMillis: Int64) {
        self.id = id
        self.timestampMillis = timestampMillis
    }
}

public struct ClipboardPayload: Codable, Equatable, Sendable {
    public let text: String
    public let timestampMillis: Int64

    public init(text: String, timestampMillis: Int64) {
        self.text = text
        self.timestampMillis = timestampMillis
    }

    public func validated() throws -> ClipboardPayload {
        guard text.data(using: .utf8)?.count ?? 0 <= ProtocolLimits.maxClipboardBytes else {
            throw ProtocolError.clipboardTooLarge
        }
        return self
    }
}
