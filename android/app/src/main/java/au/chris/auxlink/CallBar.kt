package au.chris.auxlink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small bar floating over whatever is on screen while the phone has a
 * call: who it is, how long it has been going, and Answer / Decline or
 * Hang up. Notifications slide away after a few seconds; this stays until
 * the call ends. Needs "Display over other apps" (the app screen has a
 * button for it); without it only the notification is shown.
 * Drag it up or down if it covers something.
 */
class CallBar(private val ctx: Context, private val onCmd: (String) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var who: TextView
    private lateinit var timer: Chronometer
    private lateinit var buttons: LinearLayout
    private var params: WindowManager.LayoutParams? = null
    private var shown = ""

    fun allowed() = Settings.canDrawOverlays(ctx)

    /** [state]: incoming, outgoing or active. [since]: when it was answered
     *  (SystemClock.elapsedRealtime), for the timer. */
    fun show(state: String, name: String, since: Long) {
        if (!allowed()) return hide()
        try {
            if (root == null) build()
            title.text = when (state) {
                "incoming" -> "Incoming call"
                "outgoing" -> "Calling..."
                else -> "On a call"
            }
            who.text = name
            who.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
            if (state == "active") {
                timer.base = since
                timer.visibility = View.VISIBLE
                timer.start()
            } else {
                timer.stop()
                timer.visibility = View.GONE
            }
            if (state != shown) {
                buttons.removeAllViews()
                if (state == "incoming") {
                    buttons.addView(button("Answer", 0xFF2E7D32.toInt()) { onCmd("answer") })
                    buttons.addView(button("Decline", 0xFFC62828.toInt()) { onCmd("decline") })
                } else {
                    buttons.addView(button("Hang up", 0xFFC62828.toInt()) { onCmd("decline") })
                }
                shown = state
            }
        } catch (e: Exception) {
            // Permission taken away meanwhile, or the system refused: the
            // notification is still there.
            hide()
        }
    }

    fun hide() {
        root?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        root = null
        shown = ""
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val pad = (12 * dp).toInt()
        title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; alpha = 0.8f }
        who = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 20f }
        timer = Chronometer(ctx).apply { setTextColor(Color.WHITE); textSize = 16f }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(who); addView(timer)
        }
        buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xEE202124.toInt())
                cornerRadius = 16 * dp
            }
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = pad
            })
            addView(buttons)
        }
        val p = WindowManager.LayoutParams(
            (420 * dp).toInt().coerceAtMost(ctx.resources.displayMetrics.widthPixels - (16 * dp).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * dp).toInt()
        }
        // Drag (on the text side) to move it up or down.
        var downY = 0f
        var startY = 0
        text.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = p.y }
                MotionEvent.ACTION_MOVE -> {
                    p.y = (startY + (e.rawY - downY)).toInt().coerceAtLeast(0)
                    try { wm.updateViewLayout(bar, p) } catch (_: Exception) {}
                }
            }
            true
        }
        wm.addView(bar, p)
        root = bar
        params = p
    }

    private fun button(label: String, color: Int, click: () -> Unit) = Button(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        isAllCaps = false
        background = GradientDrawable().apply { setColor(color); cornerRadius = 24 * dp }
        setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()
        ).apply { marginStart = (8 * dp).toInt() }
    }

    companion object {
        fun now() = SystemClock.elapsedRealtime()
    }
}
