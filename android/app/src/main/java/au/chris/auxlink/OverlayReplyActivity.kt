package au.chris.auxlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Type a notification reply in a normal Activity — not a focusable
 * SYSTEM_ALERT_WINDOW overlay (those make some SMOs revoke overlay permission).
 *
 * Shown as a full-screen dim layer so Reply from the notification shade is
 * visible even if the shade doesn't fully collapse. Includes Mic for
 * voice-to-text (same RECORD_AUDIO ask as Setup).
 */
class OverlayReplyActivity : Activity() {
    companion object {
        private const val EXTRA_HINT = "hint"
        @Volatile private var pending: ((String) -> Unit)? = null

        fun start(ctx: Context, hint: String, onSend: (String) -> Unit) {
            pending = onSend
            SystemUi.collapseShade(ctx)
            ctx.startActivity(
                Intent(ctx, OverlayReplyActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                    .putExtra(EXTRA_HINT, hint)
            )
        }
    }

    private var micBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemUi.collapseShade(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = 0xCC000000.toInt()
        window.navigationBarColor = 0xCC000000.toInt()

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
            VoiceToText.stop()
            val cb = pending
            pending = null
            cb?.invoke(text)
            finish()
        }
        fun setMicListening(listening: Boolean) {
            micBtn?.text = if (listening) "Listening…" else "Mic"
            micBtn?.isEnabled = !listening
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = GradientDrawable().apply {
                setColor(0xF2222124.toInt())
                cornerRadius = 20 * dp
            }
            elevation = 12 * dp
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
                micBtn = pill("Mic", false) {
                    VoiceToText.listen(
                        this@OverlayReplyActivity,
                        onText = { spoken ->
                            input.setText(spoken)
                            input.setSelection(spoken.length)
                        },
                        onState = { setMicListening(it) }
                    )
                }
                addView(micBtn)
                addView(pill("Cancel", false) {
                    VoiceToText.stop()
                    pending = null
                    finish()
                })
                addView(pill("Send", true) { send() })
            })
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener {
                // Tap dim: don't dismiss — easy mis-tap while shade is closing.
            }
            val w = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(px(440))
            addView(card, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
        }
        setContentView(root)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        // Collapse again after we're up — shade often re-covers dialog-only themes.
        window.decorView.post { SystemUi.collapseShade(this) }
        window.decorView.postDelayed({ SystemUi.collapseShade(this) }, 200)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        VoiceToText.onPermissionResult(this, requestCode, permissions, grantResults)
    }

    override fun onResume() {
        super.onResume()
        SystemUi.collapseShade(this)
    }

    override fun onDestroy() {
        VoiceToText.stop()
        if (isFinishing) pending = null
        super.onDestroy()
    }
}
