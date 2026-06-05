package dev.svrx.macdroidnotify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

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
            "macdroidnotify://pair?protocolVersion=2&host=192.168.0.2&port=47655&token=token-123&macId=mac-1&tlsFingerprint=AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
        )

        assertEquals(
            PairingDetails(
                "192.168.0.2",
                47655,
                "token-123",
                "mac-1",
                "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
            ),
            pairing,
        )
    }

    @Test
    fun rejectsInvalidPairingQrUri() {
        assertNull(PairingUriParser.parse("https://example.com"))
        assertNull(PairingUriParser.parse("macdroidnotify://pair?host=&port=bad&token="))
        assertNull(PairingUriParser.parse("macdroidnotify://pair?host=192.168.0.2&port=47655&token=legacy"))
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
        store.setLastDiscovery("success 192.168.0.2:47656")

        val report = store.buildReport(
            config = PairingConfig(
                host = "192.168.0.2",
                port = 47655,
                token = "abcdefghijklmnopqrstuvwxyz",
                deviceId = "device",
                deviceName = "Android Phone",
                macId = "mac-1",
                tlsFingerprint = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
            ),
            status = ConnectionStatusSnapshot(ConnectionPhase.FAILED, "send failed"),
        )

        assertEquals(true, report.contains("token=abcd...wxyz len=26"))
        assertEquals(false, report.contains("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(false, report.contains("event-0"))
        assertEquals(true, report.contains("event-129"))
        assertEquals(true, report.contains("lastDiscovery=success 192.168.0.2:47656"))
    }

    @Test
    fun healthPingTrackerSeparatesUserPingFromBackgroundPing() {
        val tracker = HealthPingTracker(timeoutMillis = 8_000L)

        assertEquals(true, tracker.canSend())
        tracker.markSent("health-1", nowMillis = 1_000L)

        assertEquals(false, tracker.canSend())
        assertEquals(null, tracker.markPong("ping-user", nowMillis = 1_050L))
        assertEquals(null, tracker.timedOut(nowMillis = 8_999L))
        assertEquals("health-1", tracker.timedOut(nowMillis = 9_000L))
        assertEquals(8_050L, tracker.markPong("health-1", nowMillis = 9_050L))
        assertEquals(true, tracker.canSend())
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

    @Test
    fun tlsFingerprintNormalizesAndValidatesSha256Hex() {
        val value = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"

        assertEquals("AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899", TlsFingerprint.normalize(value))
        assertEquals(true, TlsFingerprint.isValid(value))
        assertEquals(false, TlsFingerprint.isValid("abc"))
    }

    @Test
    fun pinnedTrustManagerAcceptsOnlyMatchingCertificateFingerprint() {
        val encoded = "macdroid-test-certificate".toByteArray()
        val certificate = EncodedCertificate(encoded)
        val matching = PinnedCertificateTrustManager(TlsFingerprint.sha256Hex(encoded))
        val mismatching = PinnedCertificateTrustManager("A".repeat(64))

        matching.checkServerTrusted(arrayOf(certificate), "RSA")

        assertThrows(CertificateException::class.java) {
            mismatching.checkServerTrusted(arrayOf(certificate), "RSA")
        }
    }

    @Test
    fun mdnsMatcherAcceptsOnlyExpectedMacIdAndVersionTwo() {
        val attributes = mapOf(
            "protocolVersion" to "2",
            "macId" to "mac-1",
            "tlsFingerprint" to "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99",
        )

        val discovered = MacDiscoveryMatcher.fromResolvedAttributes(
            expectedMacId = "mac-1",
            host = "192.168.0.2",
            port = 47655,
            attributes = attributes,
        )

        assertEquals(
            DiscoveredMac(
                "192.168.0.2",
                47655,
                "mac-1",
                "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
            ),
            discovered,
        )
        assertNull(MacDiscoveryMatcher.fromResolvedAttributes("other-mac", "192.168.0.2", 47655, attributes))
        assertNull(MacDiscoveryMatcher.fromResolvedAttributes("mac-1", "192.168.0.2", 47655, attributes + ("protocolVersion" to "1")))
        assertEquals(true, MacDiscoveryMatcher.serviceTypeMatches("_macdroidnotify._tcp"))
        assertEquals(true, MacDiscoveryMatcher.serviceTypeMatches("_macdroidnotify._tcp."))
    }
}

private class EncodedCertificate(
    private val encodedBytes: ByteArray,
) : X509Certificate() {
    private val principal = X500Principal("CN=MacDroid Notify Test")

    override fun getEncoded(): ByteArray = encodedBytes
    override fun verify(key: PublicKey?) = Unit
    override fun verify(key: PublicKey?, sigProvider: String?) = Unit
    override fun toString(): String = "EncodedCertificate"
    override fun getPublicKey(): PublicKey? = null
    override fun checkValidity() = Unit
    override fun checkValidity(date: Date?) = Unit
    override fun getVersion(): Int = 3
    override fun getSerialNumber(): BigInteger = BigInteger.ONE
    override fun getIssuerDN(): Principal = principal
    override fun getSubjectDN(): Principal = principal
    override fun getNotBefore(): Date = Date(0)
    override fun getNotAfter(): Date = Date(0)
    override fun getTBSCertificate(): ByteArray = encodedBytes
    override fun getSignature(): ByteArray = ByteArray(0)
    override fun getSigAlgName(): String = "none"
    override fun getSigAlgOID(): String = "0.0"
    override fun getSigAlgParams(): ByteArray? = null
    override fun getIssuerUniqueID(): BooleanArray? = null
    override fun getSubjectUniqueID(): BooleanArray? = null
    override fun getKeyUsage(): BooleanArray? = null
    override fun getBasicConstraints(): Int = -1
    override fun hasUnsupportedCriticalExtension(): Boolean = false
    override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
    override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
    override fun getExtensionValue(oid: String?): ByteArray? = null
}
