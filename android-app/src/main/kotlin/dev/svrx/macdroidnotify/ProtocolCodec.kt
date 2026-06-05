package dev.svrx.macdroidnotify

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val PROTOCOL_VERSION = 1
private const val MAX_NOTIFICATION_CHARS = 512
private const val MAX_CLIPBOARD_BYTES = 32 * 1024

data class NotificationPayload(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestampMillis: Long,
) {
    fun limited(): NotificationPayload = copy(
        title = title.take(MAX_NOTIFICATION_CHARS),
        text = text.take(MAX_NOTIFICATION_CHARS),
    )
}

data class Challenge(val protocolVersion: Int, val nonce: String)

sealed class ServerMessage {
    data class Challenge(val nonce: String) : ServerMessage()
    data class PairingAccepted(val macName: String, val timestampMillis: Long) : ServerMessage()
    data class ClipboardToAndroid(val text: String) : ServerMessage()
    data class Ping(val id: String, val timestampMillis: Long) : ServerMessage()
    data class Pong(val id: String, val timestampMillis: Long) : ServerMessage()
    data object Unknown : ServerMessage()
}

object ProtocolCodec {
    fun hello(config: PairingConfig, nonce: String): String {
        return JSONObject()
            .put("type", "hello")
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("deviceId", config.deviceId)
            .put("deviceName", config.deviceName)
            .put("auth", authCode(config.token, nonce))
            .toString()
    }

    fun notificationPosted(payload: NotificationPayload): String {
        val limited = payload.limited()
        return JSONObject()
            .put("type", "notification.posted")
            .put("id", limited.id)
            .put("packageName", limited.packageName)
            .put("appName", limited.appName)
            .put("title", limited.title)
            .put("text", limited.text)
            .put("timestampMillis", limited.timestampMillis)
            .toString()
    }

    fun clipboardToMac(text: String, timestampMillis: Long): String {
        requireClipboardText(text)
        return JSONObject()
            .put("type", "clipboard.toMac")
            .put("text", text)
            .put("timestampMillis", timestampMillis)
            .toString()
    }

    fun ping(id: String, timestampMillis: Long): String {
        return JSONObject()
            .put("type", "ping")
            .put("id", id)
            .put("timestampMillis", timestampMillis)
            .toString()
    }

    fun pong(id: String, timestampMillis: Long): String {
        return JSONObject()
            .put("type", "pong")
            .put("id", id)
            .put("timestampMillis", timestampMillis)
            .toString()
    }

    fun decodeChallenge(line: String): Challenge {
        val json = JSONObject(line)
        require(json.getString("type") == "challenge") { "Expected challenge" }
        return Challenge(
            protocolVersion = json.getInt("protocolVersion"),
            nonce = json.getString("nonce"),
        )
    }

    fun decodeServerMessage(line: String): ServerMessage {
        val json = JSONObject(line)
        return when (json.optString("type")) {
            "challenge" -> ServerMessage.Challenge(json.optString("nonce"))
            "pairing.accepted" -> ServerMessage.PairingAccepted(
                macName = json.optString("macName"),
                timestampMillis = json.optLong("timestampMillis"),
            )
            "clipboard.toAndroid" -> ServerMessage.ClipboardToAndroid(json.optString("text"))
            "ping" -> ServerMessage.Ping(
                id = json.optString("id"),
                timestampMillis = json.optLong("timestampMillis"),
            )
            "pong" -> ServerMessage.Pong(
                id = json.optString("id"),
                timestampMillis = json.optLong("timestampMillis"),
            )
            else -> ServerMessage.Unknown
        }
    }

    fun requireClipboardText(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES) {
            "Clipboard text is larger than 32 KiB"
        }
    }

    fun authCode(token: String, nonce: String): String {
        val key = SecretKeySpec(decodeBase64Url(token), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(nonce.toByteArray(Charsets.UTF_8)))
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.getUrlDecoder().decode(padded)
    }
}
