package dev.svrx.macdroidnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

class ConnectionService : Service(), NetworkClient.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var client: NetworkClient? = null
    private lateinit var configStore: AppConfig
    private lateinit var statusStore: ConnectionStatusStore
    private lateinit var debugLogStore: DebugLogStore
    private var lastMacName = ""

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            ensureClient()
            handler.postDelayed(this, RECONNECT_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        configStore = AppConfig(this)
        statusStore = ConnectionStatusStore(this)
        debugLogStore = DebugLogStore(this)
        ensureNotificationChannel(this)
        debugLogStore.append("service onCreate")

        val initialStatus = if (configStore.load().isComplete()) {
            ConnectionStatusSnapshot(ConnectionPhase.CONNECTING, "Mac 연결을 준비 중입니다.")
        } else {
            ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "먼저 Mac의 QR을 스캔하세요.")
        }
        statusStore.save(initialStatus)
        startForeground(NOTIFICATION_ID, foregroundNotification(initialStatus))
        handler.post(reconnectRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLogStore.append("service onStart action=${intent?.action ?: "start"} client=${clientState()}")
        when (intent?.action) {
            ACTION_STOP -> {
                debugLogStore.append("service stop requested")
                updateStatus(ConnectionStatusSnapshot(ConnectionPhase.IDLE, "서비스가 중지되었습니다."))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND_CLIPBOARD -> {
                intent.getStringExtra(EXTRA_TEXT)?.let { sendClipboard(it) }
            }
            ACTION_SEND_NOTIFICATION -> {
                notificationFromIntent(intent)?.let { sendNotification(it) }
            }
            ACTION_SEND_PING -> sendPing()
            ACTION_SEND_TEST_NOTIFICATION -> sendTestNotification()
            else -> ensureClient()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        debugLogStore.append("service onDestroy")
        handler.removeCallbacks(reconnectRunnable)
        client?.close()
        client = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnected(macName: String) {
        lastMacName = macName.ifBlank { "Mac" }
        debugLogStore.append("service connected mac=$lastMacName")
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.CONNECTED, "$lastMacName 연결됨"))
    }

    override fun onDisconnected(reason: String) {
        debugLogStore.append("service disconnected reason=$reason")
        client = null
        val displayReason = if (reason == "Disconnected") "연결이 끊어졌습니다." else reason
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.FAILED, displayReason))
    }

    override fun onClipboardFromMac(text: String) {
        debugLogStore.append("service clipboard from mac textLen=${text.length}")
        showMacClipboardNotification(text)
        handler.post {
            Toast.makeText(this, "Mac 클립보드를 받았습니다. 알림을 탭해 적용하세요.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPong(id: String, rttMillis: Long) {
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
            updateStatus(ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "먼저 Mac의 QR을 스캔하세요."))
            return
        }
        if (client?.isRunning == true) {
            debugLogStore.append("service ensureClient skipped client=${clientState()}")
            return
        }

        debugLogStore.append("service ensureClient new client host=${config.host}:${config.port}")
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.CONNECTING, "${config.host}:${config.port} 연결 중"))
        client = NetworkClient(config, this).also { it.start() }
    }

    private fun sendClipboard(text: String) {
        try {
            ProtocolCodec.requireClipboardText(text)
            if (client?.sendClipboard(text) == true) {
                handler.post {
                    Toast.makeText(this, "클립보드를 Mac으로 보냈습니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                showDisconnectedToast()
            }
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
        if (client?.sendNotification(payload) == true) {
            debugLogStore.append("service notification sent package=${payload.packageName}")
        } else {
            markSendFailure("notification")
        }
    }

    private fun sendPing() {
        val id = "ping-${System.currentTimeMillis()}"
        debugLogStore.append("service ping requested id=$id client=${clientState()}")
        if (client?.sendPing(id) == true) {
            val current = statusStore.load()
            updateStatus(current.copy(detail = "핑 응답 대기 중"))
        } else {
            markSendFailure("ping")
        }
    }

    private fun sendTestNotification() {
        val payload = NotificationPayload(
            id = "test-${System.currentTimeMillis()}",
            packageName = packageName,
            appName = "MacDroid Notify",
            title = "테스트 알림",
            text = "Android에서 보낸 테스트 알림입니다.",
            timestampMillis = System.currentTimeMillis(),
        )
        if (client?.sendNotification(payload) == true) {
            handler.post {
                Toast.makeText(this, "테스트 알림을 Mac으로 보냈습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            markSendFailure("test notification")
        }
    }

    private fun markSendFailure(action: String) {
        debugLogStore.append("service send failed action=$action client=${clientState()}")
        client?.close()
        client = null
        updateStatus(ConnectionStatusSnapshot(ConnectionPhase.FAILED, "Mac에 연결되지 않았습니다."))
        handler.post {
            Toast.makeText(this, "Mac에 연결되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
        ensureClient()
    }

    private fun showDisconnectedToast() {
        markSendFailure("clipboard")
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

    companion object {
        const val CHANNEL_ID = "connection_quiet_v2"
        private const val CLIPBOARD_CHANNEL_ID = "clipboard_actions"
        private const val NOTIFICATION_ID = 1001
        private const val CLIPBOARD_NOTIFICATION_ID = 1002
        private const val RECONNECT_DELAY_MS = 5_000L

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
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ConnectionService::class.java).setAction(ACTION_STOP))
        }

        fun sendClipboard(context: Context, text: String) {
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_SEND_CLIPBOARD)
                .putExtra(EXTRA_TEXT, text)
            context.startService(intent)
        }

        fun sendNotification(context: Context, payload: NotificationPayload) {
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
