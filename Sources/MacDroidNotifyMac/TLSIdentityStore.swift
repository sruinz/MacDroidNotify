import CryptoKit
import Foundation
import MacDroidNotifyCore
import Security

struct TLSIdentity {
    let secIdentity: SecIdentity
    let fingerprint: String
}

final class TLSIdentityStore {
    private let fileManager = FileManager.default

    func loadOrCreateIdentity(macId: String) throws -> TLSIdentity {
        let directory = try supportDirectory()
        let certURL = directory.appendingPathComponent("macdroidnotify-cert.pem")
        let keyURL = directory.appendingPathComponent("macdroidnotify-key.pem")
        let p12URL = directory.appendingPathComponent("macdroidnotify-identity.p12")
        let passURL = directory.appendingPathComponent("macdroidnotify-pass.txt")

        if !fileManager.fileExists(atPath: p12URL.path) ||
            !fileManager.fileExists(atPath: certURL.path) ||
            !fileManager.fileExists(atPath: passURL.path) {
            try createIdentityFiles(macId: macId, certURL: certURL, keyURL: keyURL, p12URL: p12URL, passURL: passURL)
        }

        let password = try String(contentsOf: passURL, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        let identity = try loadIdentity(from: p12URL, password: password)
        let fingerprint = try certificateFingerprint(certURL: certURL)
        return TLSIdentity(secIdentity: identity, fingerprint: fingerprint)
    }

    private func supportDirectory() throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = base.appendingPathComponent("MacDroidNotify", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func createIdentityFiles(macId: String, certURL: URL, keyURL: URL, p12URL: URL, passURL: URL) throws {
        let password = RandomToken.make(byteCount: 24).base64URLEncodedString()
        try password.write(to: passURL, atomically: true, encoding: String.Encoding.utf8)

        let subject = "/CN=MacDroid Notify \(macId)"
        try runOpenSSL([
            "req",
            "-x509",
            "-newkey", "rsa:2048",
            "-nodes",
            "-keyout", keyURL.path,
            "-out", certURL.path,
            "-days", "3650",
            "-sha256",
            "-subj", subject,
            "-addext", "subjectAltName=DNS:macdroidnotify.local,DNS:MacDroidNotify,IP:127.0.0.1",
        ])
        try runOpenSSL([
            "pkcs12",
            "-export",
            "-out", p12URL.path,
            "-inkey", keyURL.path,
            "-in", certURL.path,
            "-passout", "pass:\(password)",
        ])
    }

    private func loadIdentity(from p12URL: URL, password: String) throws -> SecIdentity {
        let data = try Data(contentsOf: p12URL)
        let options = [kSecImportExportPassphrase as String: password] as CFDictionary
        var imported: CFArray?
        let status = SecPKCS12Import(data as CFData, options, &imported)
        guard status == errSecSuccess,
              let items = imported as? [[String: Any]],
              let identityValue = items.first?[kSecImportItemIdentity as String] else {
            throw TLSIdentityStoreError.identityImportFailed(status)
        }
        return identityValue as! SecIdentity
    }

    private func certificateFingerprint(certURL: URL) throws -> String {
        let pem = try String(contentsOf: certURL, encoding: .utf8)
        let base64 = pem
            .components(separatedBy: .newlines)
            .filter { !$0.hasPrefix("-----") && !$0.isEmpty }
            .joined()
        guard let der = Data(base64Encoded: base64) else {
            throw TLSIdentityStoreError.invalidCertificate
        }
        return TLSFingerprint.sha256Hex(for: der)
    }

    private func runOpenSSL(_ arguments: [String]) throws {
        let executable = ["/opt/homebrew/bin/openssl", "/usr/bin/openssl"].first {
            fileManager.isExecutableFile(atPath: $0)
        }
        guard let executable else {
            throw TLSIdentityStoreError.opensslMissing
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments

        let errorPipe = Pipe()
        process.standardError = errorPipe
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let errorData = errorPipe.fileHandleForReading.readDataToEndOfFile()
            let message = String(data: errorData, encoding: .utf8) ?? "openssl failed"
            throw TLSIdentityStoreError.opensslFailed(message)
        }
    }
}

enum TLSIdentityStoreError: LocalizedError {
    case opensslMissing
    case opensslFailed(String)
    case identityImportFailed(OSStatus)
    case invalidCertificate

    var errorDescription: String? {
        switch self {
        case .opensslMissing:
            return "openssl을 찾지 못했습니다."
        case let .opensslFailed(message):
            return "openssl 실행 실패: \(message)"
        case let .identityImportFailed(status):
            return "TLS identity를 불러오지 못했습니다. OSStatus=\(status)"
        case .invalidCertificate:
            return "TLS 인증서 형식이 올바르지 않습니다."
        }
    }
}
