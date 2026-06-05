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
) {
    fun isComplete(): Boolean = host.isNotBlank() && port in 1..65535 && token.isNotBlank()
}

data class PairingDetails(
    val host: String,
    val port: Int,
    val token: String,
) {
    fun isComplete(): Boolean = host.isNotBlank() && port in 1..65535 && token.isNotBlank()
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
        val host = params["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        val token = params["token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return PairingDetails(host, port, token).takeIf { it.isComplete() }
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
            deviceId = deviceId.ifBlank { "fold7" },
            deviceName = prefs.getString("device_name", android.os.Build.MODEL).orEmpty(),
        )
    }

    fun save(pairing: PairingDetails) {
        save(pairing.host, pairing.port, pairing.token)
    }

    fun save(host: String, port: Int, token: String) {
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .apply()
    }
}
