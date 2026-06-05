import AppKit
import Foundation

final class MacDebugLogStore {
    private var lines: [String] = []
    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    func append(_ message: String) {
        let line = "\(formatter.string(from: Date())) \(message)"
        lines.append(line)
        if lines.count > 200 {
            lines.removeFirst(lines.count - 200)
        }
    }

    func report(status: String, port: UInt16, macId: String, tlsFingerprint: String, loginStatus: String, iconDiagnostics: String) -> String {
        """
        MacDroid Notify Mac debug
        status=\(status)
        port=\(port)
        macId=\(macId)
        tlsFingerprint=\(tlsFingerprint)
        loginItem=\(loginStatus)
        bundleId=\(Bundle.main.bundleIdentifier ?? "없음")
        bundlePath=\(Bundle.main.bundlePath)
        \(iconDiagnostics)
        logs:
        \(lines.joined(separator: "\n"))
        """
    }
}
