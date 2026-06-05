import CryptoKit
import Foundation
import Testing
@testable import MacDroidNotifyCore

@Test func authCodeUsesHMACSHA256Base64URL() throws {
    let token = Data("01234567890123456789012345678901".utf8)
    let nonce = "nonce-for-test"

    let auth = PairingAuth.authCode(token: token, nonce: nonce)

    let key = SymmetricKey(data: token)
    let mac = HMAC<SHA256>.authenticationCode(for: Data(nonce.utf8), using: key)
    #expect(auth == Data(mac).base64URLEncodedString())
}

@Test func notificationPayloadTruncatesTitleAndTextTo512Characters() throws {
    let longTitle = String(repeating: "A", count: 600)
    let longText = String(repeating: "B", count: 700)

    let payload = NotificationPayload(
        id: "n1",
        packageName: "com.example.sender",
        appName: "Sender",
        title: longTitle,
        text: longText,
        timestampMillis: 42
    ).limited()

    #expect(payload.title.count == 512)
    #expect(payload.text.count == 512)
}

@Test func clipboardPayloadRejectsTextLargerThan32KiB() throws {
    let ok = ClipboardPayload(text: String(repeating: "x", count: 32 * 1024), timestampMillis: 1)
    let tooLarge = ClipboardPayload(text: String(repeating: "x", count: 32 * 1024 + 1), timestampMillis: 1)

    #expect(try ok.validated().text.count == 32 * 1024)
    #expect(throws: ProtocolError.clipboardTooLarge) {
        try tooLarge.validated()
    }
}

@Test func ndjsonCodecRoundTripsNotificationMessage() throws {
    let message = WireMessage.notificationPosted(NotificationPayload(
        id: "id",
        packageName: "com.example",
        appName: "Example",
        title: "Title",
        text: "Body",
        timestampMillis: 10
    ))

    let line = try NDJSONCodec.encode(message)
    let decoded = try NDJSONCodec.decode(line)

    #expect(decoded == message)
}

@Test func ndjsonCodecRoundTripsPairingAcceptedMessage() throws {
    let message = WireMessage.pairingAccepted(PairingAcceptedPayload(
        macName: "Mac mini",
        timestampMillis: 100
    ))

    let line = try NDJSONCodec.encode(message)
    let decoded = try NDJSONCodec.decode(line)

    #expect(decoded == message)
}

@Test func ndjsonCodecRoundTripsPingAndPongMessages() throws {
    let ping = WireMessage.ping(PingPayload(id: "ping-1", timestampMillis: 10))
    let pong = WireMessage.pong(PongPayload(id: "ping-1", timestampMillis: 20))

    #expect(try NDJSONCodec.decode(try NDJSONCodec.encode(ping)) == ping)
    #expect(try NDJSONCodec.decode(try NDJSONCodec.encode(pong)) == pong)
}

@Test func deduplicatorRejectsRecentlySeenNotificationIDs() {
    let deduplicator = NotificationDeduplicator(windowSeconds: 30)

    #expect(deduplicator.shouldAccept(id: "same", now: 100))
    #expect(!deduplicator.shouldAccept(id: "same", now: 110))
    #expect(deduplicator.shouldAccept(id: "same", now: 131))
}

@Test func networkPortParsesUserEditablePortRange() {
    #expect(NetworkPort.parseUserInput("47655") == 47_655)
    #expect(NetworkPort.parseUserInput(" 49152 ") == 49_152)
    #expect(NetworkPort.parseUserInput("1023") == nil)
    #expect(NetworkPort.parseUserInput("65536") == nil)
    #expect(NetworkPort.parseUserInput("not-a-port") == nil)
}

@Test func securePairingURLRoundTripsVersionTwoFields() throws {
    let payload = SecurePairingPayload(
        host: "192.168.0.2",
        port: 47_655,
        token: "token-123",
        macId: "mac-id",
        tlsFingerprint: "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
    )

    let parsed = try #require(SecurePairingPayload.parse(payload.urlString))

    #expect(parsed.host == "192.168.0.2")
    #expect(parsed.port == 47_655)
    #expect(parsed.token == "token-123")
    #expect(parsed.macId == "mac-id")
    #expect(parsed.tlsFingerprint == "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899")
}

@Test func securePairingRejectsLegacyVersionOneURL() {
    let legacy = "macdroidnotify://pair?host=192.168.0.2&port=47655&token=token-123"

    #expect(SecurePairingPayload.parse(legacy) == nil)
}

@Test func tlsFingerprintNormalizesAndValidatesSha256Hex() {
    let fingerprint = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"

    #expect(TLSFingerprint.normalize(fingerprint) == "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899")
    #expect(TLSFingerprint.isValid(fingerprint))
    #expect(!TLSFingerprint.isValid("abc"))
}

@Test func bonjourTXTRecordEncodesVersionMacIdAndFingerprint() throws {
    let record = BonjourTXTRecord(
        macId: "mac-123",
        tlsFingerprint: "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899"
    )

    let parsed = try #require(BonjourTXTRecord.parse(record.dictionary))

    #expect(record.dictionary["protocolVersion"] == "2")
    #expect(parsed.macId == "mac-123")
    #expect(parsed.tlsFingerprint == "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899")
}
