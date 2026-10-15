package au.chris.auxlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Type a notification reply in a normal Activity window — not a focusable
 * SYSTEM_ALERT_WINDOW overlay. Focusable overlays from the background service
 * make some head units (SMO / Screenmate) revoke "Display over other apps".
 */
class OverlayReplyActivity : Activity() {
    companion object {
        private const val EXTRA_HINT = "hint"
        @Volatile private var pending: ((String) -> Unit)? = null

        fun start(ctx: Context, hint: String, onSend: (String) -> Unit) {
            pending = onSend
            ctx.startActivity(
                Intent(ctx, OverlayReplyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_HINT, hint)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val hint = intent.getStringExtra(EXTRA_HINT).orEmpty().ifBlank { "Reply" }
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(0xFF9AA0A6.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0x00000000)
            setPadding(px(4), px(8), px(4), px(8))
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        fun pill(label: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) 0xFF3B82F6.toInt() else 0xFF3C4043.toInt())
                cornerRadius = 20 * dp
            }
            setPadding(px(16), 0, px(16), 0)
            minHeight = 0; minimumHeight = px(40)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(40)
            ).apply { marginStart = px(8) }
        }
        fun send() {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            val cb = pending
            pending = null
            cb?.invoke(text)
            finish()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = GradientDrawable().apply {
                setColor(0xF2222124.toInt())
                cornerRadius = 20 * dp
            }
            addView(TextView(this@OverlayReplyActivity).apply {
                text = "Reply"; setTextColor(Color.WHITE); textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) })
            addView(LinearLayout(this@OverlayReplyActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, px(12), 0, 0)
                addView(pill("Cancel", false) {
                    pending = null
                    finish()
                })
                addView(pill("Send", true) { send() })
            })
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        setContentView(box)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(px(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        input.requestFocus()
    }

    override fun onDestroy() {
        if (isFinishing) pending = null
        super.onDestroy()
    }
}
