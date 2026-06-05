import Foundation

public enum NetworkPort {
    public static let defaultValue: UInt16 = 47_655
    public static let minimumUserValue: UInt16 = 1_024
    public static let maximumValue: UInt16 = UInt16.max

    public static func parseUserInput(_ value: String) -> UInt16? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let port = UInt16(trimmed), port >= minimumUserValue else {
            return nil
        }
        return port
    }
}
