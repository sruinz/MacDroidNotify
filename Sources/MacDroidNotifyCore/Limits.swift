import Foundation

public enum ProtocolLimits {
    public static let version = 2
    public static let maxNotificationTextCharacters = 512
    public static let maxClipboardBytes = 32 * 1024
}

public enum ProtocolError: Error, Equatable {
    case clipboardTooLarge
    case invalidLine
    case invalidMessageType(String)
    case authenticationFailed
}

extension String {
    func limitedCharacters(to maxCount: Int) -> String {
        guard count > maxCount else {
            return self
        }
        return String(prefix(maxCount))
    }
}
