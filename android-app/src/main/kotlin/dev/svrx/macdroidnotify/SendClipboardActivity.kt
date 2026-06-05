package dev.svrx.macdroidnotify

import android.app.Activity
import android.content.ClipboardManager
import android.content.ClipDescription
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SendClipboardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var debugLogStore: DebugLogStore
    private lateinit var statusText: TextView
    private lateinit var input: EditText
    private var attempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugLogStore = DebugLogStore(this)
        debugLogStore.append("clipboard send activity create")
        setContentView(contentView())
        input.requestFocus()
        handler.postDelayed({ showKeyboard() }, 180L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        debugLogStore.append("clipboard send window focus=$hasFocus attempted=$attempted")
        if (hasFocus && !attempted) {
            attempted = true
            handler.postDelayed({ trySendFocusedClipboard() }, 350L)
        }
    }

    private fun trySendFocusedClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val description = clipboard.primaryClipDescription
        val clip = clipboard.primaryClip
        val itemCount = clip?.itemCount ?: 0
        val rawText = clip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
        val text = ClipboardText.sendableText(rawText)

        debugLogStore.append(
            "clipboard send read focus=${hasWindowFocus()} clip=${clip != null} " +
                "items=$itemCount mime=${mimeSummary(description)} textLen=${text?.length ?: 0}",
        )

        if (text == null) {
            statusText.text = "자동 읽기 실패. 아래 칸에 붙여넣은 뒤 보내세요."
            Toast.makeText(this, "붙여넣기 후 보내기를 눌러주세요.", Toast.LENGTH_SHORT).show()
        } else {
            sendText(text, source = "auto")
        }
    }

    private fun sendManualText() {
        val text = ClipboardText.sendableText(input.text)
        if (text == null) {
            debugLogStore.append("clipboard send manual empty")
            Toast.makeText(this, "보낼 텍스트가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        sendText(text, source = "manual")
    }

    private fun sendText(text: String, source: String) {
        debugLogStore.append("clipboard send $source textLen=${text.length}")
        ConnectionService.sendClipboard(this, text)
        Toast.makeText(this, "클립보드를 Mac으로 보냈습니다.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun contentView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(12, 18, 26))
            setPadding(32, 36, 32, 32)
            addView(TextView(context).apply {
                text = "Android 클립보드 보내기"
                textSize = 22f
                setTextColor(Color.WHITE)
            }, compactLayout())
            statusText = TextView(context).apply {
                text = "클립보드를 자동으로 읽는 중입니다."
                textSize = 15f
                setTextColor(Color.rgb(190, 201, 212))
                gravity = Gravity.CENTER
            }
            addView(statusText, compactLayout())
            input = EditText(context).apply {
                hint = "여기에 붙여넣기"
                minLines = 4
                gravity = Gravity.TOP
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(135, 150, 164))
                backgroundTintList = ColorStateList.valueOf(Color.rgb(61, 75, 92))
            }
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 20, 0, 14)
            })
            addView(button("붙여넣은 텍스트 보내기") { sendManualText() }, compactLayout())
            addView(button("닫기") { finish() }, compactLayout())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(33, 128, 141))
            setOnClickListener { action() }
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

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun mimeSummary(description: ClipDescription?): String {
        if (description == null) return "none"
        return (0 until description.mimeTypeCount).joinToString(",") { index ->
            description.getMimeType(index)
        }.ifBlank { "none" }
    }
}
