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
