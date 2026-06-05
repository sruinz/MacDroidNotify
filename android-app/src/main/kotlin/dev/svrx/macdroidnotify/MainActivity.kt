package dev.svrx.macdroidnotify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var config: AppConfig
    private lateinit var statusStore: ConnectionStatusStore
    private lateinit var debugLogStore: DebugLogStore
    private lateinit var scrollView: ScrollView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var macIdInput: EditText
    private lateinit var tlsFingerprintInput: EditText
    private lateinit var manualSection: LinearLayout
    private lateinit var manualToggleButton: Button
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var pairingSummary: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        statusStore = ConnectionStatusStore(this)
        debugLogStore = DebugLogStore(this)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        parsePairingUri(intent?.data?.toString())
        buildContentView()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parsePairingUri(intent.data?.toString())
        fillFields(config.load())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startServiceAfterChecks()
        } else {
            statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.FAILED, "알림 권한이 필요합니다."))
            refreshStatus()
            toast("알림 권한을 허용한 뒤 다시 시작하세요.")
        }
    }

    private fun buildContentView() {
        val saved = config.load()
        scrollView = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            clipToPadding = false
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollView.addView(root)

        root.addView(text("MacDroid Notify", 24f, TEXT_PRIMARY).apply {
            setPadding(0, 0, 0, 4)
        })
        root.addView(text("버전 ${BuildConfig.VERSION_NAME}", 14f, TEXT_SECONDARY).apply {
            setPadding(0, 0, 0, 20)
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = 18f
                setStroke(1, BORDER)
            }
        }
        statusTitle = text("대기 중", 20f, TEXT_PRIMARY)
        statusDetail = text("", 15f, TEXT_SECONDARY).apply {
            setPadding(0, 8, 0, 0)
        }
        pairingSummary = text("", 14f, TEXT_SECONDARY).apply {
            setPadding(0, 14, 0, 0)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusDetail)
        statusCard.addView(pairingSummary)
        root.addView(statusCard, spacedLayout())

        root.addView(button("QR로 페어링") { scanPairingQr() }, spacedLayout())
        root.addView(button("서비스 시작") { requestNotificationPermissionThenStart() }, compactLayout())
        root.addView(button("서비스 중지") { ConnectionService.stop(this) }, compactLayout())
        root.addView(button("핑 테스트") { ConnectionService.sendPing(this) }, compactLayout())
        root.addView(button("테스트 알림 보내기") { ConnectionService.sendTestNotification(this) }, compactLayout())
        root.addView(button("디버그 로그 복사") { copyDebugLog() }, compactLayout())
        root.addView(button("상시 알림 설정") { openConnectionNotificationSettings() }, compactLayout())
        root.addView(button("알림 접근 설정 열기") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, compactLayout())
        root.addView(button("알림 리스너 재연결") {
            NotificationMirrorListener.requestListenerRebind(this)
            toast("알림 리스너 재연결을 요청했습니다.")
        }, compactLayout())

        manualToggleButton = button("고급/수동 입력 보기") { toggleManualSection() }
        root.addView(manualToggleButton, spacedLayout())

        manualSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        manualSection.addView(text("고급/수동 입력", 17f, TEXT_PRIMARY).apply {
            setPadding(0, 8, 0, 8)
        })

        hostInput = editText("Mac IP")
        portInput = editText("포트").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        tokenInput = editText("페어링 토큰").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        macIdInput = editText("Mac ID").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        tlsFingerprintInput = editText("TLS fingerprint").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        manualSection.addView(hostInput, compactLayout())
        manualSection.addView(portInput, compactLayout())
        manualSection.addView(tokenInput, compactLayout())
        manualSection.addView(macIdInput, compactLayout())
        manualSection.addView(tlsFingerprintInput, compactLayout())
        enableFocusScroll(hostInput)
        enableFocusScroll(portInput)
        enableFocusScroll(tokenInput)
        enableFocusScroll(macIdInput)
        enableFocusScroll(tlsFingerprintInput)
        manualSection.addView(button("수동 입력 저장") { savePairing() }, compactLayout())
        root.addView(manualSection, compactLayout())

        root.addView(button("Android 클립보드를 Mac으로 보내기") {
            startActivity(Intent(this, SendClipboardActivity::class.java))
        }, compactLayout())

        root.addView(text("One UI 설정에서 이 앱의 배터리 사용량을 제한 없음으로 두면 잠금 상태에서 더 안정적입니다.", 14f, TEXT_SECONDARY).apply {
            setPadding(0, 24, 0, 0)
        })

        setContentView(scrollView)
        installKeyboardAwareScrolling()
        fillFields(saved)
        refreshStatus()
        maybeAutoStartService(saved)
    }

    private fun toggleManualSection() {
        val shouldShow = manualSection.visibility != View.VISIBLE
        manualSection.visibility = if (shouldShow) View.VISIBLE else View.GONE
        manualToggleButton.text = if (shouldShow) "고급/수동 입력 숨기기" else "고급/수동 입력 보기"
    }

    private fun fillFields(saved: PairingConfig) {
        if (::hostInput.isInitialized) {
            hostInput.setText(saved.host)
            portInput.setText(saved.port.toString())
            tokenInput.setText(saved.token)
            macIdInput.setText(saved.macId)
            tlsFingerprintInput.setText(saved.tlsFingerprint)
        }
    }

    private fun savePairing() {
        val pairing = PairingDetails(
            host = hostInput.text.toString().trim(),
            port = portInput.text.toString().toIntOrNull() ?: 0,
            token = tokenInput.text.toString().trim(),
            macId = macIdInput.text.toString().trim(),
            tlsFingerprint = tlsFingerprintInput.text.toString().trim(),
        )
        if (!pairing.isComplete()) {
            statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "0.2.0 보안 QR 또는 v2 수동 값을 입력하세요."))
            refreshStatus()
            toast("페어링 정보가 올바르지 않습니다.")
            return
        }

        debugLogStore.append("activity manual pairing saved host=${pairing.host}:${pairing.port}")
        config.save(pairing)
        statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.IDLE, "페어링 정보 저장됨. 서비스 시작을 누르세요."))
        refreshStatus()
        toast("페어링 정보 저장됨")
    }

    private fun parsePairingUri(raw: String?) {
        val pairing = PairingUriParser.parse(raw) ?: return
        debugLogStore.append("activity pairing uri imported host=${pairing.host}:${pairing.port}")
        config.save(pairing)
        statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.IDLE, "페어링 정보 저장됨. 서비스 시작을 누르세요."))
        toast("페어링 정보 저장됨")
    }

    private fun scanPairingQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val pairing = PairingUriParser.parse(barcode.rawValue)
                if (pairing == null) {
                    debugLogStore.append("activity qr rejected length=${barcode.rawValue?.length ?: 0}")
                    statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "0.2.0 보안 페어링 QR이 아닙니다."))
                    toast("0.2.0 보안 페어링 QR이 아닙니다.")
                } else {
                    debugLogStore.append("activity qr saved host=${pairing.host}:${pairing.port}")
                    config.save(pairing)
                    statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.IDLE, "페어링 정보 저장됨. 서비스 시작을 누르세요."))
                    fillFields(config.load())
                    refreshStatus()
                    toast("페어링 정보 저장됨")
                }
            }
            .addOnCanceledListener { toast("QR 스캔이 취소되었습니다.") }
            .addOnFailureListener { error ->
                debugLogStore.append("activity qr scan failed ${error.javaClass.simpleName}: ${error.localizedMessage}")
                statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.FAILED, error.localizedMessage ?: "QR 스캔 실패"))
                refreshStatus()
                toast("QR 스캔 실패")
            }
    }

    private fun requestNotificationPermissionThenStart() {
        if (!config.load().isComplete()) {
            statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "먼저 Mac의 0.2.0 QR을 스캔하세요."))
            refreshStatus()
            toast("먼저 QR로 페어링하세요.")
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            return
        }

        startServiceAfterChecks()
    }

    private fun startServiceAfterChecks() {
        debugLogStore.append("activity start service requested")
        if (!NotificationAccess.isEnabled(this)) {
            debugLogStore.append("activity notification access missing")
            toast("실제 알림 전달에는 알림 접근 권한이 필요합니다.")
        }
        statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.CONNECTING, "서비스 시작 중"))
        refreshStatus()
        ConnectionService.start(this)
    }

    private fun maybeAutoStartService(saved: PairingConfig) {
        if (!saved.serviceEnabled || !saved.isComplete()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            statusStore.save(ConnectionStatusSnapshot(ConnectionPhase.PAIRING_REQUIRED, "자동 연결에는 알림 권한이 필요합니다."))
            refreshStatus()
            return
        }
        debugLogStore.append("activity auto start service")
        ConnectionService.start(this)
    }

    private fun copyDebugLog() {
        val report = debugLogStore.buildReport(config.load(), statusStore.load()) +
            "\nnotificationAccess=${NotificationAccess.statusText(this)}"
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MacDroid Notify debug", report))
        toast("디버그 로그를 복사했습니다.")
    }

    private fun openConnectionNotificationSettings() {
        ConnectionService.ensureNotificationChannel(this)
        ConnectionService.ensureClipboardNotificationChannel(this)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ConnectionService.CHANNEL_ID)
        startActivity(intent)
    }

    private fun refreshStatus() {
        if (!::statusTitle.isInitialized) return
        val status = statusStore.load()
        val pairing = config.load()
        statusTitle.text = status.title
        statusDetail.text = status.description
        val notificationAccess = NotificationAccess.statusText(this)
        pairingSummary.text = if (pairing.isComplete()) {
            "페어링 정보: ${pairing.host}:${pairing.port}\nMac ID: ${pairing.macId}\n알림 접근: $notificationAccess"
        } else {
            "페어링 정보 없음\n알림 접근: $notificationAccess"
        }
    }

    private fun text(value: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
        }
    }

    private fun editText(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            backgroundTintList = ColorStateList.valueOf(BORDER)
        }
    }

    private fun enableFocusScroll(input: EditText) {
        input.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                scrollFocusedView(view)
            }
        }
    }

    private fun installKeyboardAwareScrolling() {
        val visibleFrame = Rect()
        scrollView.viewTreeObserver.addOnGlobalLayoutListener {
            scrollView.getWindowVisibleDisplayFrame(visibleFrame)
            val rootHeight = scrollView.rootView.height
            val keyboardHeight = (rootHeight - visibleFrame.bottom).coerceAtLeast(0)
            val keyboardVisible = keyboardHeight > rootHeight * 0.15
            val bottomPadding = if (keyboardVisible) keyboardHeight + 96 else 48
            if (scrollView.paddingBottom != bottomPadding) {
                scrollView.setPadding(0, 0, 0, bottomPadding)
            }
            if (keyboardVisible) {
                currentFocus?.let { scrollFocusedView(it) }
            }
        }
    }

    private fun scrollFocusedView(view: View) {
        scrollView.postDelayed({
            scrollView.smoothScrollTo(0, view.bottom + 360)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }, 250L)
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(ACCENT)
            setOnClickListener { action() }
        }
    }

    private fun spacedLayout(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, 12, 0, 12)
        }
    }

    private fun compactLayout(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, 6, 0, 6)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 10
        val BACKGROUND: Int = Color.rgb(12, 18, 26)
        val SURFACE: Int = Color.rgb(25, 34, 46)
        val BORDER: Int = Color.rgb(61, 75, 92)
        val ACCENT: Int = Color.rgb(33, 128, 141)
        val TEXT_PRIMARY: Int = Color.rgb(245, 248, 250)
        val TEXT_SECONDARY: Int = Color.rgb(190, 201, 212)
        val TEXT_MUTED: Int = Color.rgb(135, 150, 164)
    }
}
