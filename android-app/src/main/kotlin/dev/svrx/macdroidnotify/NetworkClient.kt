package dev.svrx.macdroidnotify

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class NetworkClient(
    private val config: PairingConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(macName: String)
        fun onDisconnected(reason: String)
        fun onClipboardFromMac(text: String)
        fun onPong(id: String, rttMillis: Long)
        fun onDebugLog(message: String) {}
    }

    private val running = AtomicBoolean(false)
    private val disconnectNotified = AtomicBoolean(false)
    private val writerLock = Any()
    private val pingSentAtMillis = ConcurrentHashMap<String, Long>()
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MacDroid-Writer")
    }
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::runLoop, "MacDroid-NetworkClient").start()
    }

    fun close() {
        running.set(false)
        socket?.close()
        socket = null
        writer = null
        writerExecutor.shutdownNow()
    }

    fun sendNotification(payload: NotificationPayload): Boolean {
        return sendLine(ProtocolCodec.notificationPosted(payload))
    }

    fun sendClipboard(text: String): Boolean {
        return sendLine(ProtocolCodec.clipboardToMac(text, System.currentTimeMillis()))
    }

    fun sendPing(id: String): Boolean {
        val timestamp = System.currentTimeMillis()
        pingSentAtMillis[id] = timestamp
        val sent = sendLine(ProtocolCodec.ping(id, timestamp))
        if (!sent) pingSentAtMillis.remove(id)
        return sent
    }

    private fun runLoop() {
        try {
            val connectedSocket = tlsSocket()
            listener.onDebugLog("network connecting ${config.host}:${config.port}")
            connectedSocket.connect(InetSocketAddress(config.host, config.port), 5_000)
            listener.onDebugLog("network tls handshake starting")
            connectedSocket.startHandshake()
            socket = connectedSocket
            listener.onDebugLog("network tls socket connected")

            val reader = BufferedReader(InputStreamReader(connectedSocket.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(connectedSocket.getOutputStream(), Charsets.UTF_8))

            val challengeLine = reader.readLine() ?: error("No challenge from Mac")
            val challenge = ProtocolCodec.decodeChallenge(challengeLine)
            require(challenge.protocolVersion == ProtocolConstants.VERSION) { "Unsupported protocol version ${challenge.protocolVersion}" }
            listener.onDebugLog("network challenge received")
            sendLine(ProtocolCodec.hello(config, challenge.nonce))
            listener.onDebugLog("network hello sent")

            while (running.get()) {
                val line = reader.readLine() ?: break
                when (val message = ProtocolCodec.decodeServerMessage(line)) {
                    is ServerMessage.PairingAccepted -> {
                        listener.onDebugLog("network pairing accepted mac=${message.macName}")
                        listener.onConnected(message.macName)
                    }
                    is ServerMessage.ClipboardToAndroid -> listener.onClipboardFromMac(message.text)
                    is ServerMessage.Ping -> sendLine(
                        ProtocolCodec.pong(message.id, System.currentTimeMillis()),
                    )
                    is ServerMessage.Pong -> {
                        val sentAt = pingSentAtMillis.remove(message.id) ?: message.timestampMillis
                        listener.onPong(message.id, System.currentTimeMillis() - sentAt)
                    }
                    is ServerMessage.Challenge,
                    ServerMessage.Unknown -> Unit
                }
            }
            if (running.get()) {
                listener.onDebugLog("network disconnected eof")
                notifyDisconnected("Disconnected")
            }
        } catch (error: Exception) {
            if (running.get()) {
                listener.onDebugLog("network exception ${error.javaClass.simpleName}: ${error.message}")
                notifyDisconnected(error.message ?: "Connection failed")
            }
        } finally {
            close()
        }
    }

    private fun tlsSocket(): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedCertificateTrustManager(config.tlsFingerprint)), null)
        return context.socketFactory.createSocket() as SSLSocket
    }

    private fun sendLine(line: String): Boolean {
        if (writer == null) {
            listener.onDebugLog("network send failed writer=null")
            close()
            return false
        }

        return try {
            writerExecutor.execute { writeLine(line) }
            true
        } catch (error: RejectedExecutionException) {
            listener.onDebugLog("network send rejected ${error.message}")
            false
        }
    }

    private fun writeLine(line: String) {
        val target = writer ?: run {
            listener.onDebugLog("network async send failed writer=null")
            notifyDisconnected("Send failed: writer missing")
            close()
            return
        }

        try {
            synchronized(writerLock) {
                target.write(line)
                target.write("\n")
                target.flush()
            }
        } catch (error: Exception) {
            listener.onDebugLog("network send failed ${error.javaClass.simpleName}: ${error.message}")
            notifyDisconnected("Send failed: ${error.message ?: error.javaClass.simpleName}")
            close()
        }
    }

    private fun notifyDisconnected(reason: String) {
        if (disconnectNotified.compareAndSet(false, true)) {
            listener.onDisconnected(reason)
        }
    }
}
