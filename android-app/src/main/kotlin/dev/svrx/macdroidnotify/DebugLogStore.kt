package dev.svrx.macdroidnotify

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface DebugLogStorage {
    fun readLinesJson(): String?
    fun writeLinesJson(value: String)
    fun readLastDiscovery(): String?
    fun writeLastDiscovery(value: String)
}

class InMemoryDebugLogStorage : DebugLogStorage {
    private var value: String? = null

    override fun readLinesJson(): String? = value

    override fun writeLinesJson(value: String) {
        this.value = value
    }

    private var lastDiscovery: String? = null

    override fun readLastDiscovery(): String? = lastDiscovery

    override fun writeLastDiscovery(value: String) {
        lastDiscovery = value
    }
}

private class SharedPreferencesDebugLogStorage(context: Context) : DebugLogStorage {
    private val prefs = context.getSharedPreferences("macdroid_notify_debug", Context.MODE_PRIVATE)

    override fun readLinesJson(): String? = prefs.getString(KEY, null)

    override fun writeLinesJson(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    override fun readLastDiscovery(): String? = prefs.getString(KEY_LAST_DISCOVERY, null)

    override fun writeLastDiscovery(value: String) {
        prefs.edit().putString(KEY_LAST_DISCOVERY, value).apply()
    }

    private companion object {
        const val KEY = "debug_lines_json"
        const val KEY_LAST_DISCOVERY = "last_discovery"
    }
}

class DebugLogStore(private val storage: DebugLogStorage) {
    constructor(context: Context) : this(SharedPreferencesDebugLogStorage(context))

    fun append(message: String, nowMillis: Long = System.currentTimeMillis()) {
        val lines = readLines().toMutableList()
        lines += "${formatTime(nowMillis)} $message"
        val trimmed = lines.takeLast(MAX_LINES)
        storage.writeLinesJson(JSONArray(trimmed).toString())
    }

    fun setLastDiscovery(value: String) {
        storage.writeLastDiscovery(value)
    }

    fun buildReport(config: PairingConfig, status: ConnectionStatusSnapshot): String {
        return buildString {
            appendLine("MacDroid Notify debug")
            appendLine("status=${status.title}")
            appendLine("detail=${status.description}")
            appendLine("host=${config.host.ifBlank { "(empty)" }}")
            appendLine("port=${config.port}")
            appendLine("token=${maskToken(config.token)}")
            appendLine("macId=${config.macId.ifBlank { "(empty)" }}")
            appendLine("tlsFingerprint=${maskFingerprint(config.tlsFingerprint)}")
            appendLine("serviceEnabled=${config.serviceEnabled}")
            appendLine("lastDiscovery=${storage.readLastDiscovery().orEmpty().ifBlank { "(none)" }}")
            appendLine("deviceId=${config.deviceId.ifBlank { "(empty)" }}")
            appendLine("deviceName=${config.deviceName.ifBlank { "(empty)" }}")
            appendLine("logs:")
            readLines().forEach { appendLine(it) }
        }.trimEnd()
    }

    private fun readLines(): List<String> {
        val raw = storage.readLinesJson() ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.optString(index) }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun maskToken(token: String): String {
        if (token.isBlank()) return "(empty)"
        if (token.length <= 8) return "*** len=${token.length}"
        return "${token.take(4)}...${token.takeLast(4)} len=${token.length}"
    }

    private fun maskFingerprint(fingerprint: String): String {
        val normalized = TlsFingerprint.normalize(fingerprint)
        if (normalized.isBlank()) return "(empty)"
        return "${normalized.take(8)}...${normalized.takeLast(8)} len=${normalized.length}"
    }

    private fun formatTime(timestampMillis: Long): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
    }

    private companion object {
        const val MAX_LINES = 120
    }
}
