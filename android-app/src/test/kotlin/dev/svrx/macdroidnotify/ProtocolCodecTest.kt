package dev.svrx.macdroidnotify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ProtocolCodecTest {
    @Test
    fun helloUsesHmacSha256Base64UrlAuth() {
        val tokenBytes = "01234567890123456789012345678901".toByteArray()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val config = PairingConfig("192.168.0.2", 47655, token, "device", "Android Phone")

        val line = ProtocolCodec.hello(config, "nonce-for-test")
        val json = JSONObject(line)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenBytes, "HmacSHA256"))
        val expected = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal("nonce-for-test".toByteArray()))

        assertEquals("hello", json.getString("type"))
        assertEquals(expected, json.getString("auth"))
    }

    @Test
    fun notificationPayloadLimitsTitleAndText() {
        val payload = NotificationPayload(
            id = "id",
            packageName = "pkg",
            appName = "App",
            title = "A".repeat(600),
            text = "B".repeat(700),
            timestampMillis = 1,
        ).limited()

        assertEquals(512, payload.title.length)
        assertEquals(512, payload.text.length)
    }

    @Test
    fun clipboardRejectsTextOver32KiB() {
        val tooLarge = "x".repeat(32 * 1024 + 1)

        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.clipboardToMac(tooLarge, 1)
        }
    }

    @Test
    fun clipboardTextTreatsBlankValuesAsEmpty() {
        assertEquals(null, ClipboardText.sendableText(null))
        assertEquals(null, ClipboardText.sendableText(""))
        assertEquals(null, ClipboardText.sendableText("   \n\t"))
    }

    @Test
    fun clipboardTextKeepsNonBlankTextUntrimmed() {
        assertEquals("  hello\n", ClipboardText.sendableText("  hello\n"))
    }

    @Test
    fun decodesClipboardToAndroidMessage() {
        val message = ProtocolCodec.decodeServerMessage(
            """{"type":"clipboard.toAndroid","text":"hello"}""",
        )

        assertEquals(ServerMessage.ClipboardToAndroid("hello"), message)
    }

    @Test
    fun parsesPairingQrUri() {
        val pairing = PairingUriParser.parse(
            "macdroidnotify://pair?host=192.168.0.2&port=47655&token=token-123",
        )

        assertEquals(PairingDetails("192.168.0.2", 47655, "token-123"), pairing)
    }

    @Test
    fun rejectsInvalidPairingQrUri() {
        assertNull(PairingUriParser.parse("https://example.com"))
        assertNull(PairingUriParser.parse("macdroidnotify://pair?host=&port=bad&token="))
    }

    @Test
    fun decodesPairingAcceptedMessage() {
        val message = ProtocolCodec.decodeServerMessage(
            """{"type":"pairing.accepted","macName":"Mac mini","timestampMillis":100}""",
        )

        assertEquals(ServerMessage.PairingAccepted("Mac mini", 100), message)
    }

    @Test
    fun encodesAndDecodesPingAndPongPayloads() {
        val pingJson = JSONObject(ProtocolCodec.ping("ping-1", 10))
        val pongJson = JSONObject(ProtocolCodec.pong("ping-1", 20))
        val decodedPong = ProtocolCodec.decodeServerMessage(
            """{"type":"pong","id":"ping-1","timestampMillis":30}""",
        )

        assertEquals("ping", pingJson.getString("type"))
        assertEquals("ping-1", pingJson.getString("id"))
        assertEquals(10, pingJson.getLong("timestampMillis"))
        assertEquals("pong", pongJson.getString("type"))
        assertEquals("ping-1", pongJson.getString("id"))
        assertEquals(20, pongJson.getLong("timestampMillis"))
        assertEquals(ServerMessage.Pong("ping-1", 30), decodedPong)
    }

    @Test
    fun connectionStatusStorePersistsFailureConnectedAndPingResult() {
        val storage = InMemoryStatusStorage()
        val store = ConnectionStatusStore(storage)

        store.save(ConnectionStatusSnapshot(ConnectionPhase.FAILED, detail = "timeout"))
        assertEquals(ConnectionPhase.FAILED, store.load().phase)
        assertEquals("timeout", store.load().detail)

        store.save(
            ConnectionStatusSnapshot(
                phase = ConnectionPhase.CONNECTED,
                detail = "Mac mini",
                lastPingRttMillis = 42,
            ),
        )

        val saved = store.load()
        assertEquals(ConnectionPhase.CONNECTED, saved.phase)
        assertEquals("Mac mini", saved.detail)
        assertEquals(42L, saved.lastPingRttMillis)
        assertEquals("연결됨", saved.title)
    }

    @Test
    fun debugLogReportMasksTokenAndKeepsRecentLines() {
        val store = DebugLogStore(InMemoryDebugLogStorage())
        repeat(130) { index ->
            store.append("event-$index", nowMillis = 1_000L + index)
        }

        val report = store.buildReport(
            config = PairingConfig(
                host = "192.168.0.2",
                port = 47655,
                token = "abcdefghijklmnopqrstuvwxyz",
                deviceId = "device",
                deviceName = "Android Phone",
            ),
            status = ConnectionStatusSnapshot(ConnectionPhase.FAILED, "send failed"),
        )

        assertEquals(true, report.contains("token=abcd...wxyz len=26"))
        assertEquals(false, report.contains("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(false, report.contains("event-0"))
        assertEquals(true, report.contains("event-129"))
    }

    @Test
    fun notificationAccessParsesEnabledListenerList() {
        val target = "dev.svrx.macdroidnotify/dev.svrx.macdroidnotify.NotificationMirrorListener"
        val enabled = "com.example/.OtherListener:$target"
        val shortTarget = "dev.svrx.macdroidnotify/.NotificationMirrorListener"

        assertEquals(true, NotificationAccess.isComponentEnabled(enabled, target))
        assertEquals(true, NotificationAccess.isComponentEnabled(shortTarget, shortTarget))
        assertEquals(false, NotificationAccess.isComponentEnabled("com.example/.OtherListener", target))
        assertEquals(false, NotificationAccess.isComponentEnabled(null, target))
    }
}
