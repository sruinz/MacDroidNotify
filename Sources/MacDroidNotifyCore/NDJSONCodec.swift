import Foundation

public enum NDJSONCodec {
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    private static let decoder = JSONDecoder()

    public static func encode(_ message: WireMessage) throws -> String {
        let data = try encoder.encode(message)
        guard let line = String(data: data, encoding: .utf8) else {
            throw ProtocolError.invalidLine
        }
        return line + "\n"
    }

    public static func decode(_ line: String) throws -> WireMessage {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8), !data.isEmpty else {
            throw ProtocolError.invalidLine
        }
        return try decoder.decode(WireMessage.self, from: data)
    }
}
