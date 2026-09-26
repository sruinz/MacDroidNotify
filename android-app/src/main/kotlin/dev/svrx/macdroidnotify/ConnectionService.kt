package dev.svrx.macdroidnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.Looper
import android.widget.Toast

class ConnectionService : Service(), NetworkClient.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var client: NetworkClient? = null
    private lateinit var configStore: AppConfig
    private lateinit var statusStore: ConnectionStatusStore
    private lateinit var debugLogStore: DebugLogStore
    private var lastMacName = ""
    private var discovery: MacDiscovery? = null
    private var discoveryRunning = false
    private val healthPingTracker = HealthPingTracker(HEALTH_TIMEOUT_MS)
    private val reconnectSchedule = ReconnectSchedule(
        baseDelayMillis = RECONNECT_DELAY_MS,
        maxDelayMillis = MAX_RECONNECT_DELAY_MS,
    )
    private val outboundWorkQueue = OutboundWorkQueue()
    private var macConnected = false

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            ensureClient()
            handler.postDelayed(this, RECONNECT_DELAY_MS)
        }
    }

    private val healthRunnable = object : Runnable {
        override fun run() {
            runHealthCheck()
            handler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    private val healthTimeoutRunnable = object : Runnable {
        override fun run() {
            handleHealthTimeout()
        }
    }

    override fun onCreate() {
        super.onCreate()
        configStore = AppConfig(this)
        statusStore = ConnectionStatusStore(this)
        debugLogStore = DebugLogStore(this)
        ensureNotificationChannel(this)
        debugLogStore.append("service onCreate")
        appendServiceLifecycle("create")

        val initialStatus = if (configStore.load().isComplete()) {
            ConnectionStatusSnapshot(ConnectionPhase.CONNECTING, "Mac 연결을 준비 중입니다.")
        } else {
            ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "먼저 Mac의 QR을 스캔하세요.")
        }
        statusStore.save(initialStatus)
        startAsForeground(initialStatus)
        handler.post(reconnectRunnable)
        handler.postDelayed(healthRunnable, HEALTH_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLogStore.append("service onStart action=${intent?.action ?: "start"} client=${clientState()}")
        appendServiceLifecycle("start action=${intent?.action ?: "start"} startId=$startId flags=$flags")
        when (intent?.action) {
            ACTION_STOP -> {
                debugLogStore.append("service stop requested")
                updateStatus(ConnectionStatusSnapshot(ConnectionPhase.IDLE, "서비스가 중지되었습니다."))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                reconnectSchedule.wake()
                ensureClient()
            }
            ACTION_SEND_CLIPBOARD -> {
                wakeForOutboundWork()
                intent.getStringExtra(EXTRA_TEXT)?.let { sendClipboard(it) }
            }
            ACTION_SEND_NOTIFICATION -> {
                wakeForOutboundWork()
                notificationFromIntent(intent)?.let { sendNotification(it) }
            }
            ACTION_SEND_PING -> {
                wakeForOutboundWork()
                sendPing()
            }
            ACTION_SEND_TEST_NOTIFICATION -> {
                wakeForOutboundWork()
                sendTestNotification()
            }
            else -> ensureClient()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        debugLogStore.append("service onDestroy")
        appendServiceLifecycle("destroy")
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(healthRunnable)
        handler.removeCallbacks(healthTimeoutRunnable)
        discovery?.stop()
        client?.close()
        client = null
        macConnected = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        appendServiceLifecycle("task removed action=${rootIntent?.action.orEmpty()}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int) {
        appendServiceLifecycle("timeout startId=$startId")
        super.onTimeout(startId)
    }

    override fun onTimeout(fgsType: Int, startId: Int) {
        appendServiceLifecycle("timeout fgsType=$fgsType startId=$startId")
        super.onTimeout(fgsType, startId)
    }

    override fun onTrimMemory(level: Int) {
        appendServiceLifecycle("trim memory level=$level")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        appendServiceLifecycle("low memory")
        super.onLowMemory()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnected(macName: String) {
        lastMacName = macName.ifBlank { "Mac" }
        macConnected = true
        reconnectSchedule.recordSuccess()
        debugLogStore.append("service connected mac=$lastMacName")
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.CONNECTED, "$lastMacName 연결됨"))
        handler.post { flushOutboundWork() }
    }

    override fun onDisconnected(reason: String) {
        macConnected = false
        reconnectSchedule.recordFailure()
        debugLogStore.append("service disconnected reason=$reason")
        client = null
        val phase = when {
            reason.contains("fingerprint", ignoreCase = true) ||
                reason.contains("certificate", ignoreCase = true) ||
                reason.contains("SSL", ignoreCase = true) -> ConnectionPhase.TLS_FAILED
            reason.contains("protocol version", ignoreCase = true) ||
                reason.contains("auth", ignoreCase = true) -> ConnectionPhase.AUTH_FAILED
            else -> ConnectionPhase.RECONNECT_WAITING
        }
        val displayReason = if (reason == "Disconnected") "연결이 끊어졌습니다. 재연결을 기다립니다." else reason
        updateStatus(
            ConnectionStatusSnapshot(
                phase,
                if (phase == ConnectionPhase.RECONNECT_WAITING && reconnectSchedule.isExhausted()) {
                    "Mac이 응답하지 않습니다. 다음 알림이나 작업이 있을 때 다시 시도합니다."
                } else {
                    displayReason
                },
            ),
        )
        clearHealthPing()
        handler.post { requestReconnect("disconnect") }
    }

    override fun onClipboardFromMac(text: String) {
        debugLogStore.append("service clipboard from mac textLen=${text.length}")
        showMacClipboardNotification(text)
        handler.post {
            Toast.makeText(this, "Mac 클립보드를 받았습니다. 알림을 탭해 적용하세요.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPong(id: String, rttMillis: Long) {
        val healthRttMillis = healthPingTracker.markPong(id, System.currentTimeMillis())
        if (healthRttMillis != null) {
            handler.removeCallbacks(healthTimeoutRunnable)
            debugLogStore.append("health pong id=$id rtt=${healthRttMillis.coerceAtLeast(0)}")
            return
        }
        debugLogStore.append("service pong id=$id rtt=${rttMillis.coerceAtLeast(0)}")
        updateStatus(
            ConnectionStatusSnapshot(
                phase = ConnectionPhase.CONNECTED,
                detail = "${lastMacName.ifBlank { "Mac" }} 연결됨",
                lastPingRttMillis = rttMillis.coerceAtLeast(0),
            ),
        )
        handler.post {
            Toast.makeText(this, "핑 성공: ${rttMillis.coerceAtLeast(0)}ms", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDebugLog(message: String) {
        debugLogStore.append(message)
    }

    private fun ensureClient() {
        val config = configStore.load()
        if (!config.isComplete()) {
            debugLogStore.append("service ensureClient pairing missing")
            updateStatus(ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "먼저 Mac의 보안 페어링 QR을 스캔하세요."))
            return
        }
        if (!ConnectionPolicy.shouldAttemptConnection(hasWifiTransport())) {
            waitForWifi()
            return
        }
        if (!reconnectSchedule.canAttempt()) return
        if (client?.isRunning == true) {
            debugLogStore.append("service ensureClient skipped client=${clientState()}")
            return
        }
        if (discoveryRunning) {
            debugLogStore.append("service ensureClient skipped discovery=running")
            return
        }

        discoverThenConnect(config)
    }

    private fun discoverThenConnect(config: PairingConfig) {
        discoveryRunning = true
        clearHealthPing()
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.DISCOVERING, "페어링된 Mac을 찾는 중"))
        debugLogStore.append("service mdns discovery requested macId=${config.macId}")
        discovery?.stop()
        discovery = MacDiscovery(this, config.macId, object : MacDiscovery.Listener {
            override fun onDiscovered(mac: DiscoveredMac) {
                discoveryRunning = false
                debugLogStore.append("service mdns discovered ${mac.host}:${mac.port} macId=${mac.macId}")
                debugLogStore.setLastDiscovery("success ${mac.host}:${mac.port}")
                configStore.updateEndpoint(mac.host, mac.port)
                connect(configStore.load())
            }

            override fun onFailed(reason: String) {
                discoveryRunning = false
                debugLogStore.setLastDiscovery("${discoveryFailureKind(reason)} fallback ${config.host}:${config.port}")
                debugLogStore.append("service mdns failed reason=$reason")
                debugLogStore.append("service mdns fallback ${config.host}:${config.port}")
                connect(config)
            }

            override fun onDebugLog(message: String) {
                debugLogStore.append(message)
            }
        }).also { it.start() }
    }

    private fun connect(config: PairingConfig) {
        macConnected = false
        debugLogStore.append("service ensureClient new tls client host=${config.host}:${config.port}")
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.CONNECTING, "${config.host}:${config.port} TLS 연결 중"))
        client = NetworkClient(config, this).also { it.start() }
    }

    private fun waitForWifi() {
        val shouldCloseCurrentWork = discoveryRunning || client?.isRunning == true
        if (shouldCloseCurrentWork) {
            debugLogStore.append("service connection paused wifi only")
            clearHealthPing()
            discovery?.stop()
            discoveryRunning = false
            client?.close()
            client = null
            macConnected = false
        }

        val current = statusStore.load()
        if (current.phase != ConnectionPhase.WAITING_FOR_WIFI) {
            debugLogStore.append("service waiting for wifi")
            updateStatus(ConnectionStatusSnapshot(ConnectionPhase.WAITING_FOR_WIFI, "Wi-Fi 연결을 기다리는 중입니다."))
        }
    }

    private fun hasWifiTransport(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun runHealthCheck() {
        val currentClient = client
        if (currentClient?.isRunning != true || statusStore.load().phase != ConnectionPhase.CONNECTED) {
            clearHealthPing()
            return
        }

        if (!healthPingTracker.canSend()) return

        val nowMillis = System.currentTimeMillis()
        val id = "health-$nowMillis"
        if (currentClient.sendPing(id)) {
            healthPingTracker.markSent(id, nowMillis)
            handler.removeCallbacks(healthTimeoutRunnable)
            handler.postDelayed(healthTimeoutRunnable, HEALTH_TIMEOUT_MS)
            debugLogStore.append("health ping sent id=$id")
        } else {
            clearHealthPing()
            markSendFailure("health ping")
        }
    }

    private fun handleHealthTimeout() {
        val currentClient = client ?: return
        val timedOutId = healthPingTracker.timedOut(System.currentTimeMillis()) ?: return
        reconnectSchedule.recordFailure()
        debugLogStore.append("health timeout id=$timedOutId")
        clearHealthPing()
        currentClient.close()
        client = null
        macConnected = false
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.RECONNECT_WAITING, "Mac 응답이 없어 재연결을 준비합니다."))
        requestReconnect("health timeout")
    }

    private fun clearHealthPing() {
        handler.removeCallbacks(healthTimeoutRunnable)
        healthPingTracker.clear()
    }

    private fun requestReconnect(reason: String) {
        debugLogStore.append("reconnect requested reason=$reason")
        ensureClient()
    }

    private fun discoveryFailureKind(reason: String): String {
        return when {
            reason.contains("시간 초과") || reason.contains("timeout", ignoreCase = true) -> "timeout"
            reason.contains("시작 실패") -> "startFailed"
            else -> "failed"
        }
    }

    private fun sendClipboard(text: String) {
        try {
            ProtocolCodec.requireClipboardText(text)
            outboundWorkQueue.enqueue(OutboundWork.Clipboard(text))
            flushOutboundWork()
        } catch (error: IllegalArgumentException) {
            handler.post {
                Toast.makeText(this, error.message ?: "클립보드가 너무 큽니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendNotification(payload: NotificationPayload) {
        debugLogStore.append(
            "service notification requested package=${payload.packageName} app=${payload.appName} " +
                "titleLen=${payload.title.length} textLen=${payload.text.length} client=${clientState()}",
        )
        outboundWorkQueue.enqueue(OutboundWork.Notification(payload))
        flushOutboundWork()
    }

    private fun sendPing() {
        val id = "ping-${System.currentTimeMillis()}"
        debugLogStore.append("service ping requested id=$id client=${clientState()}")
        outboundWorkQueue.enqueue(OutboundWork.Ping(id))
        flushOutboundWork()
    }

    private fun sendTestNotification() {
        outboundWorkQueue.enqueue(OutboundWork.TestNotification)
        flushOutboundWork()
    }

    private fun markSendFailure(action: String) {
        reconnectSchedule.recordFailure()
        debugLogStore.append("service send failed action=$action client=${clientState()}")
        clearHealthPing()
        client?.close()
        client = null
        macConnected = false
        updateStatus(
            ConnectionStatusSnapshot(
                ConnectionPhase.RECONNECT_WAITING,
                if (reconnectSchedule.isExhausted()) {
                    "Mac이 응답하지 않습니다. 다음 알림이나 작업이 있을 때 다시 시도합니다."
                } else {
                    "Mac에 연결되지 않았습니다. 재연결을 준비합니다."
                },
            ),
        )
        handler.post {
            Toast.makeText(this, "Mac에 연결되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
        ensureClient()
    }

    private fun canSendNow(): Boolean {
        return macConnected && client?.isRunning == true
    }

    private fun flushOutboundWork() {
        if (!canSendNow()) return
        while (true) {
            val work = outboundWorkQueue.peek() ?: break
            if (!dispatchOutboundWork(work)) {
                markSendFailure("queued work")
                break
            }
            outboundWorkQueue.dequeue()
        }
    }

    private fun dispatchOutboundWork(work: OutboundWork): Boolean {
        return when (work) {
            is OutboundWork.Clipboard -> {
                val sent = client?.sendClipboard(work.text) == true
                if (sent) {
                    handler.post {
                        Toast.makeText(this, "클립보드를 Mac으로 보냈습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                sent
            }
            is OutboundWork.Notification -> {
                val sent = client?.sendNotification(work.payload) == true
                if (sent) {
                    debugLogStore.append("service notification sent package=${work.payload.packageName}")
                }
                sent
            }
            is OutboundWork.Ping -> {
                val sent = client?.sendPing(work.id) == true
                if (sent) {
                    val current = statusStore.load()
                    updateStatus(current.copy(detail = "핑 응답 대기 중"))
                }
                sent
            }
            OutboundWork.TestNotification -> sendTestNotificationNow()
        }
    }

    private fun sendTestNotificationNow(): Boolean {
        val payload = NotificationPayload(
            id = "test-${System.currentTimeMillis()}",
            packageName = packageName,
            appName = "MacDroid Notify",
            title = "테스트 알림",
            text = "Android에서 보낸 테스트 알림입니다.",
            timestampMillis = System.currentTimeMillis(),
        )
        val sent = client?.sendNotification(payload) == true
        if (sent) {
            handler.post {
                Toast.makeText(this, "테스트 알림을 Mac으로 보냈습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        return sent
    }

    private fun notificationFromIntent(intent: Intent): NotificationPayload? {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return null
        val packageName = intent.getStringExtra(EXTRA_NOTIFICATION_PACKAGE) ?: return null
        val appName = intent.getStringExtra(EXTRA_NOTIFICATION_APP) ?: packageName
        val title = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT).orEmpty()
        val timestamp = intent.getLongExtra(EXTRA_NOTIFICATION_TIMESTAMP, System.currentTimeMillis())
        return NotificationPayload(id, packageName, appName, title, text, timestamp)
    }

    private fun updateStatus(snapshot: ConnectionStatusSnapshot) {
        statusStore.save(snapshot)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, foregroundNotification(snapshot))
    }

    private fun startAsForeground(snapshot: ConnectionStatusSnapshot) {
        val notification = foregroundNotification(snapshot)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showMacClipboardNotification(text: String) {
        ensureClipboardNotificationChannel(this)
        val pendingIntent = PendingIntent.getActivity(
            this,
            CLIPBOARD_NOTIFICATION_ID,
            ApplyClipboardActivity.intent(this, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CLIPBOARD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_LOW)

        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Mac 클립보드 수신됨")
            .setContentText("탭하면 Android 클립보드에 넣습니다.")
            .setStyle(Notification.BigTextStyle().bigText("탭하면 Android 클립보드에 넣습니다."))
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(CLIPBOARD_NOTIFICATION_ID, notification)
    }

    private fun foregroundNotification(snapshot: ConnectionStatusSnapshot): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        }
        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_LOW)

        return builder
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("MacDroid Notify")
            .setContentText(snapshot.description.lineSequence().firstOrNull().orEmpty())
            .setSubText(snapshot.title)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun clientState(): String {
        val current = client ?: return "null"
        return "running=${current.isRunning}"
    }

    private fun appendServiceLifecycle(event: String) {
        debugLogStore.appendLifecycle(
            "service $event pid=${Process.myPid()} elapsed=${SystemClock.elapsedRealtime()}",
        )
    }

    private fun wakeForOutboundWork() {
        reconnectSchedule.wake()
        if (client?.isRunning != true) ensureClient()
    }

    companion object {
        const val CHANNEL_ID = "connection_quiet_v2"
        private const val CLIPBOARD_CHANNEL_ID = "clipboard_actions"
        private const val NOTIFICATION_ID = 1001
        private const val CLIPBOARD_NOTIFICATION_ID = 1002
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val HEALTH_INTERVAL_MS = 10_000L
        private const val HEALTH_TIMEOUT_MS = 8_000L

        const val ACTION_START = "dev.svrx.macdroidnotify.START"
        const val ACTION_STOP = "dev.svrx.macdroidnotify.STOP"
        const val ACTION_SEND_CLIPBOARD = "dev.svrx.macdroidnotify.SEND_CLIPBOARD"
        const val ACTION_SEND_NOTIFICATION = "dev.svrx.macdroidnotify.SEND_NOTIFICATION"
        const val ACTION_SEND_PING = "dev.svrx.macdroidnotify.SEND_PING"
        const val ACTION_SEND_TEST_NOTIFICATION = "dev.svrx.macdroidnotify.SEND_TEST_NOTIFICATION"
        const val EXTRA_TEXT = "text"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_NOTIFICATION_PACKAGE = "notification_package"
        private const val EXTRA_NOTIFICATION_APP = "notification_app"
        private const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        private const val EXTRA_NOTIFICATION_TEXT = "notification_text"
        private const val EXTRA_NOTIFICATION_TIMESTAMP = "notification_timestamp"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "조용한 연결 상태",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mac 연결 유지를 위한 필수 foreground service 알림입니다."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }

        fun ensureClipboardNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CLIPBOARD_CHANNEL_ID,
                "클립보드 작업",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mac에서 받은 클립보드를 Android에 적용하기 위한 알림입니다."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            AppConfig(context).setServiceEnabled(true)
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            AppConfig(context).setServiceEnabled(false)
            context.startService(Intent(context, ConnectionService::class.java).setAction(ACTION_STOP))
        }

        fun sendClipboard(context: Context, text: String) {
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_SEND_CLIPBOARD)
                .putExtra(EXTRA_TEXT, text)
            context.startService(intent)
        }

        fun sendNotification(context: Context, payload: NotificationPayload) {
            val config = AppConfig(context).load()
            if (!NotificationMirrorPolicy.shouldForwardNotification(config.serviceEnabled)) {
                DebugLogStore(context).append("listener ignored service disabled package=${payload.packageName}")
                return
            }
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_SEND_NOTIFICATION)
                .putExtra(EXTRA_NOTIFICATION_ID, payload.id)
                .putExtra(EXTRA_NOTIFICATION_PACKAGE, payload.packageName)
                .putExtra(EXTRA_NOTIFICATION_APP, payload.appName)
                .putExtra(EXTRA_NOTIFICATION_TITLE, payload.title)
                .putExtra(EXTRA_NOTIFICATION_TEXT, payload.text)
                .putExtra(EXTRA_NOTIFICATION_TIMESTAMP, payload.timestampMillis)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendPing(context: Context) {
            context.startService(Intent(context, ConnectionService::class.java).setAction(ACTION_SEND_PING))
        }

        fun sendTestNotification(context: Context) {
            context.startService(
                Intent(context, ConnectionService::class.java).setAction(ACTION_SEND_TEST_NOTIFICATION),
            )
        }
    }
}
