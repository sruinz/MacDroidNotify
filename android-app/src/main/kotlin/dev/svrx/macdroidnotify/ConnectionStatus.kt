package dev.svrx.macdroidnotify

import android.content.Context
import org.json.JSONObject

enum class ConnectionPhase {
    IDLE,
    PAIRING_REQUIRED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class ConnectionStatusSnapshot(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val detail: String = "",
    val lastPingRttMillis: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val title: String
        get() = when (phase) {
            ConnectionPhase.IDLE -> "대기 중"
            ConnectionPhase.PAIRING_REQUIRED -> "페어링 필요"
            ConnectionPhase.CONNECTING -> "연결 중"
            ConnectionPhase.CONNECTED -> "연결됨"
            ConnectionPhase.FAILED -> "실패"
        }

    val description: String
        get() = buildString {
            if (detail.isNotBlank()) append(detail)
            if (lastPingRttMillis != null) {
                if (isNotEmpty()) append("\n")
                append("핑 RTT: ${lastPingRttMillis}ms")
            }
        }.ifBlank { "QR 페어링 후 서비스를 시작하세요." }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("phase", phase.name)
            .put("detail", detail)
            .put("lastPingRttMillis", lastPingRttMillis)
            .put("updatedAtMillis", updatedAtMillis)
    }

    companion object {
        fun fromJson(raw: String?): ConnectionStatusSnapshot {
            if (raw.isNullOrBlank()) return ConnectionStatusSnapshot()
            return try {
                val json = JSONObject(raw)
                ConnectionStatusSnapshot(
                    phase = ConnectionPhase.valueOf(json.optString("phase", ConnectionPhase.IDLE.name)),
                    detail = json.optString("detail"),
                    lastPingRttMillis = json.takeIf { it.has("lastPingRttMillis") && !it.isNull("lastPingRttMillis") }
                        ?.optLong("lastPingRttMillis"),
                    updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                )
            } catch (_: Exception) {
                ConnectionStatusSnapshot()
            }
        }
    }
}

interface StatusStorage {
    fun readStatusJson(): String?
    fun writeStatusJson(value: String)
}

class InMemoryStatusStorage : StatusStorage {
    private var value: String? = null

    override fun readStatusJson(): String? = value

    override fun writeStatusJson(value: String) {
        this.value = value
    }
}

private class SharedPreferencesStatusStorage(context: Context) : StatusStorage {
    private val prefs = context.getSharedPreferences("macdroid_notify_status", Context.MODE_PRIVATE)

    override fun readStatusJson(): String? = prefs.getString(KEY, null)

    override fun writeStatusJson(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "status_json"
    }
}

class ConnectionStatusStore(private val storage: StatusStorage) {
    constructor(context: Context) : this(SharedPreferencesStatusStorage(context))

    fun save(snapshot: ConnectionStatusSnapshot) {
        storage.writeStatusJson(snapshot.toJson().toString())
    }

    fun load(): ConnectionStatusSnapshot {
        return ConnectionStatusSnapshot.fromJson(storage.readStatusJson())
    }
}
