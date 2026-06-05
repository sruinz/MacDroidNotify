package dev.svrx.macdroidnotify

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

data class DiscoveredMac(
    val host: String,
    val port: Int,
    val macId: String,
    val tlsFingerprint: String,
)

object MacDiscoveryMatcher {
    const val SERVICE_TYPE = "_macdroidnotify._tcp."

    fun fromServiceInfo(expectedMacId: String, info: NsdServiceInfo): DiscoveredMac? {
        val attributes = info.attributes.mapValues { entry -> entry.value.toString(Charsets.UTF_8) }
        val host = info.host?.hostAddress ?: return null
        return fromResolvedAttributes(expectedMacId, host, info.port, attributes)
    }

    fun fromResolvedAttributes(
        expectedMacId: String,
        host: String,
        port: Int,
        attributes: Map<String, String>,
    ): DiscoveredMac? {
        val version = attributes["protocolVersion"]?.toIntOrNull() ?: return null
        val macId = attributes["macId"] ?: return null
        val fingerprint = attributes["tlsFingerprint"] ?: return null
        if (version != ProtocolConstants.VERSION || macId != expectedMacId || !TlsFingerprint.isValid(fingerprint)) {
            return null
        }
        return DiscoveredMac(host, port, macId, TlsFingerprint.normalize(fingerprint))
    }

    fun serviceTypeMatches(serviceType: String): Boolean =
        serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')
}

class MacDiscovery(
    private val context: Context,
    private val expectedMacId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onDiscovered(mac: DiscoveredMac)
        fun onFailed(reason: String)
        fun onDebugLog(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(NsdManager::class.java)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var stopped = false

    fun start(timeoutMillis: Long = 3_500L) {
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                listener.onDebugLog("mdns discovery started type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!MacDiscoveryMatcher.serviceTypeMatches(serviceInfo.serviceType)) return
                listener.onDebugLog("mdns service found name=${serviceInfo.serviceName}")
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        listener.onDebugLog("mdns resolve failed code=$errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val discovered = MacDiscoveryMatcher.fromServiceInfo(expectedMacId, serviceInfo)
                        if (discovered == null) {
                            listener.onDebugLog("mdns resolved service ignored")
                            return
                        }
                        stop()
                        listener.onDiscovered(discovered)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                listener.onDebugLog("mdns service lost name=${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                listener.onDebugLog("mdns discovery stopped type=$serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener.onDebugLog("mdns start failed code=$errorCode")
                stop()
                listener.onFailed("mDNS 탐색 시작 실패: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener.onDebugLog("mdns stop failed code=$errorCode")
            }
        }
        discoveryListener = callback
        manager.discoverServices(MacDiscoveryMatcher.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback)
        handler.postDelayed({
            if (!stopped) {
                stop()
                listener.onFailed("mDNS 탐색 시간 초과")
            }
        }, timeoutMillis)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        discoveryListener?.let {
            runCatching { manager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }
}
