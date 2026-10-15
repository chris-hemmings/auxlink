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
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
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
 * Floating heads-up banners. SMS from the Pi uses [showMessage] (simple
 * sender + body, tap opens the chat). Mirrored phone notifications use [show]
 * (app icon + name).
 */
class TextPopup(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: View? = null
    private val close = Runnable { hide() }

    /**
     * Classic SMS pop-up (Pi Messages path → Tesla / Phone Link): messaging
     * app icon, sender, and body. Tap opens the conversation.
     */
    fun showMessage(
        from: String,
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
            val iconView = android.widget.ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF3C4043.toInt())
                }
                isClickable = false
                isFocusable = false
                if (icon != null) {
                    setImageDrawable(icon)
                    setPadding(0, 0, 0, 0)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                } else {
                    val inset = (6 * dp).toInt()
                    setPadding(inset, inset, inset, inset)
                    setImageResource(android.R.drawable.sym_action_chat)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = false
                isFocusable = false
                addView(TextView(ctx).apply {
                    text = from; setTextColor(Color.WHITE); textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = false; isFocusable = false
                })
                addView(TextView(ctx).apply {
                    text = body; setTextColor(0xFFE8EAED.toInt()); textSize = 15f
                    maxLines = lines; alpha = 0.95f
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = false; isFocusable = false
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xF2222124.toInt()); cornerRadius = 20 * dp
                    setStroke((1 * dp).toInt().coerceAtLeast(1), 0x33FFFFFF)
                }
                elevation = 8 * dp
                isClickable = true
                isFocusable = false
                addView(iconView)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * dp).toInt()
                })
                setOnClickListener { hide(); onTap() }
                alpha = 0f
                translationY = (-24 * dp)
            }
            val p = WindowManager.LayoutParams(
                (440 * dp).toInt().coerceAtMost(ctx.resources.displayMetrics.widthPixels - (16 * dp).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    /**
     * Mirrored phone notification banner: app icon + app name up top, then
     * title and body. Optional Stage 3 action pills (Reply / Mark as read).
     *
     * @param app   source app label ("WhatsApp", …)
     * @param title notification title (may be empty)
     * @param body  preview text
     * @param icon  app icon (bitmap or tinted drawable); optional
     * @param actions reply / mark-read buttons from the phone notification
     * @param onAction (action, replyText?) — replyText set only for reply sends
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
        actions: List<NotifLog.Action> = emptyList(),
        onAction: (NotifLog.Action, String?) -> Unit = { _, _ -> },
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

            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = gap
                })
                setOnClickListener { hide(); onTap() }
            }

            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xF2222124.toInt())
                    cornerRadius = 20 * dp
                    setStroke((1 * dp).toInt().coerceAtLeast(1), 0x33FFFFFF)
                }
                elevation = 8 * dp
                addView(head)
                if (actions.isNotEmpty()) {
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(0, (10 * dp).toInt(), 0, 0)
                        for (act in actions.take(3)) {
                            addView(actionPill(act.label) {
                                main.removeCallbacks(close)
                                if (act.reply) {
                                    showReply(act.label.ifBlank { "Reply" }) { text ->
                                        hide()
                                        onAction(act, text)
                                    }
                                } else {
                                    hide()
                                    onAction(act, null)
                                }
                            })
                        }
                    })
                }
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

    /** Focusable reply strip so the soft keyboard can type a response. */
    private fun showReply(placeholder: String, onSend: (String) -> Unit) {
        hide()
        if (!Settings.canDrawOverlays(ctx)) return
        try {
            val pad = (12 * dp).toInt()
            val input = EditText(ctx).apply {
                hint = placeholder
                setHintTextColor(0xFF9AA0A6.toInt())
                setTextColor(Color.WHITE)
                textSize = 16f
                setBackgroundColor(0x00000000)
                setPadding(pad, pad, pad, pad)
                maxLines = 4
                imeOptions = EditorInfo.IME_ACTION_SEND
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val send = actionPill("Send") {
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@actionPill
                hide()
                onSend(text)
            }
            val cancel = actionPill("Cancel") { hide() }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xF2222124.toInt())
                    cornerRadius = 20 * dp
                    setStroke((1 * dp).toInt().coerceAtLeast(1), 0x33FFFFFF)
                }
                elevation = 8 * dp
                addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(cancel)
                    addView(send)
                })
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) { hide(); onSend(text) }
                    true
                } else false
            }
            val p = WindowManager.LayoutParams(
                (440 * dp).toInt().coerceAtMost(ctx.resources.displayMetrics.widthPixels - (16 * dp).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (140 * dp).toInt()
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            }
            wm.addView(box, p)
            root = box
            input.requestFocus()
            main.postDelayed(close, 60_000)
        } catch (_: Exception) {
            hide()
        }
    }

    private fun actionPill(label: String, click: () -> Unit) = Button(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(0xFF3C4043.toInt()); cornerRadius = 20 * dp
        }
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        minHeight = 0
        minimumHeight = (36 * dp).toInt()
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, (36 * dp).toInt()
        ).apply { marginStart = (8 * dp).toInt() }
    }

    fun hide() {
        main.removeCallbacks(close)
        root?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        root = null
    }
}
