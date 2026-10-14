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
        if (!allowed()) {
            lastProblem = "\"Display over other apps\" is off for Phone Link"
            return hide()
        }
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
            lastProblem = ""
        } catch (e: Exception) {
            // Permission taken away meanwhile, or the system refused: the
            // notification is still there. The app screen shows why.
            lastProblem = "Android refused: ${e.javaClass.simpleName}: ${e.message}"
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
        /** Why the bar last couldn't be shown ("" = it could). */
        @Volatile var lastProblem = ""
    }
}

/**
 * Heads-up banner for a new text or mirrored phone notification: app icon +
 * app name up top, then title and body. Floats over other apps; tap to open.
 */
class TextPopup(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: View? = null
    private val close = Runnable { hide() }

    /**
     * @param app   source app label ("Messages", "WhatsApp", …)
     * @param title sender / notification title (may be empty)
     * @param body  preview text
     * @param icon  app icon (bitmap or tinted drawable); optional
     * @return false if the overlay couldn't be shown (caller should fall back
     *         to a notification)
     */
    fun show(
        app: String,
        title: String,
        body: String,
        icon: android.graphics.drawable.Drawable? = null,
        stayMs: Long = 10000,
        lines: Int = 4,
        onTap: () -> Unit = {},
    ): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        return try {
            hide()
            val pad = (14 * dp).toInt()
            val iconSize = (40 * dp).toInt()
            val gap = (10 * dp).toInt()

            val iconView = android.widget.ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF3C4043.toInt())
                }
                val inset = (6 * dp).toInt()
                setPadding(inset, inset, inset, inset)
                if (icon != null) {
                    setImageDrawable(icon)
                    // Bitmaps fill the circle; vectors keep a small inset.
                    if (icon is android.graphics.drawable.BitmapDrawable) {
                        setPadding(0, 0, 0, 0)
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                    }
                } else {
                    setImageResource(android.R.drawable.ic_dialog_info)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }
            }

            val appLabel = TextView(ctx).apply {
                text = app.ifEmpty { "Notification" }
                setTextColor(0xFFBDC1C6.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val titleView = TextView(ctx).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = if (title.isBlank()) View.GONE else View.VISIBLE
            }
            val bodyView = TextView(ctx).apply {
                text = body
                setTextColor(0xFFE8EAED.toInt())
                textSize = 15f
                maxLines = lines
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = if (body.isBlank()) View.GONE else View.VISIBLE
                setPadding(0, (2 * dp).toInt(), 0, 0)
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(appLabel)
                addView(titleView)
                addView(bodyView)
            }

            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xF2222124.toInt())
                    cornerRadius = 20 * dp
                    setStroke((1 * dp).toInt().coerceAtLeast(1), 0x33FFFFFF)
                }
                elevation = 8 * dp
                addView(iconView)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = gap
                })
                setOnClickListener { hide(); onTap() }
                // Slide + fade in.
                alpha = 0f
                translationY = (-24 * dp)
            }
            val p = WindowManager.LayoutParams(
                (440 * dp).toInt().coerceAtMost(ctx.resources.displayMetrics.widthPixels - (16 * dp).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (140 * dp).toInt()   // below the call bar, if there is one
            }
            wm.addView(box, p)
            root = box
            box.animate().alpha(1f).translationY(0f).setDuration(220).start()
            main.postDelayed(close, stayMs)
            true
        } catch (e: Exception) {
            hide()
            false
        }
    }

    fun hide() {
        main.removeCallbacks(close)
        root?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        root = null
    }
}
