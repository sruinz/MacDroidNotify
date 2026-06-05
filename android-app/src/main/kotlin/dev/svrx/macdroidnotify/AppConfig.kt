package dev.svrx.macdroidnotify

import android.content.Context
import android.provider.Settings
import java.net.URI
import java.net.URLDecoder

data class PairingConfig(
    val host: String,
    val port: Int,
    val token: String,
    val deviceId: String,
    val deviceName: String,
    val macId: String = "",
    val tlsFingerprint: String = "",
    val serviceEnabled: Boolean = false,
    val autoStartEnabled: Boolean = false,
) {
    fun isComplete(): Boolean =
        host.isNotBlank() &&
            port in 1..65535 &&
            token.isNotBlank() &&
            macId.isNotBlank() &&
            TlsFingerprint.isValid(tlsFingerprint)
}

data class PairingDetails(
    val host: String,
    val port: Int,
    val token: String,
    val macId: String,
    val tlsFingerprint: String,
) {
    fun isComplete(): Boolean =
        host.isNotBlank() &&
            port in 1..65535 &&
            token.isNotBlank() &&
            macId.isNotBlank() &&
            TlsFingerprint.isValid(tlsFingerprint)
}

object PairingUriParser {
    fun parse(rawValue: String?): PairingDetails? {
        val raw = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }

        if (uri.scheme != "macdroidnotify" || uri.host != "pair") return null
        val params = parseQuery(uri.rawQuery)
        val version = params["protocolVersion"]?.toIntOrNull() ?: return null
        if (version != ProtocolConstants.VERSION) return null
        val host = params["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        val token = params["token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val macId = params["macId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val fingerprint = params["tlsFingerprint"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return PairingDetails(host, port, token, macId, fingerprint).takeIf { it.isComplete() }
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                if (pieces.size != 2) return@mapNotNull null
                decode(pieces[0]) to decode(pieces[1])
            }
            .toMap()
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}

class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("macdroid_notify", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun load(): PairingConfig {
        val deviceId = prefs.getString("device_id", null) ?: Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()

        return PairingConfig(
            host = prefs.getString("host", "").orEmpty(),
            port = prefs.getInt("port", 47655),
            token = prefs.getString("token", "").orEmpty(),
            deviceId = deviceId.ifBlank { "android" },
            deviceName = prefs.getString("device_name", android.os.Build.MODEL).orEmpty(),
            macId = prefs.getString("mac_id", "").orEmpty(),
            tlsFingerprint = prefs.getString("tls_fingerprint", "").orEmpty(),
            serviceEnabled = prefs.getBoolean("service_enabled", false),
            autoStartEnabled = prefs.getBoolean("auto_start_enabled", false),
        )
    }

    fun save(pairing: PairingDetails) {
        save(pairing.host, pairing.port, pairing.token, pairing.macId, pairing.tlsFingerprint)
    }

    fun save(host: String, port: Int, token: String, macId: String, tlsFingerprint: String) {
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .putString("mac_id", macId)
            .putString("tls_fingerprint", TlsFingerprint.normalize(tlsFingerprint))
            .apply()
    }

    fun updateEndpoint(host: String, port: Int) {
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .apply()
    }

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("service_enabled", enabled).apply()
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_start_enabled", enabled).apply()
    }
}
