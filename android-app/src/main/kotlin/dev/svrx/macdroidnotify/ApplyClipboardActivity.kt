package dev.svrx.macdroidnotify

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ApplyClipboardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var debugLogStore: DebugLogStore
    private var applied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugLogStore = DebugLogStore(this)
        setContentView(focusView("Mac 클립보드 적용 중"))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !applied) {
            applied = true
            handler.postDelayed({ applyClipboard() }, 120L)
        }
    }

    private fun applyClipboard() {
        val text = ClipboardText.sendableText(intent.getStringExtra(EXTRA_TEXT))
        if (text == null) {
            debugLogStore.append("clipboard apply failed empty text")
            Toast.makeText(this, "적용할 Mac 클립보드가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Mac clipboard", text))
        debugLogStore.append("clipboard apply success textLen=${text.length}")
        Toast.makeText(this, "Mac 클립보드를 Android에 넣었습니다.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun focusView(message: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(12, 18, 26))
            isFocusableInTouchMode = true
            addView(TextView(context).apply {
                text = message
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            requestFocus()
        }
    }

    companion object {
        private const val EXTRA_TEXT = "dev.svrx.macdroidnotify.CLIPBOARD_TEXT"

        fun intent(context: Context, text: String): Intent {
            return Intent(context, ApplyClipboardActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
