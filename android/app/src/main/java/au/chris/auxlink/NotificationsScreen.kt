package au.chris.auxlink

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Alerts tab: forwarder controls (on the phone) + scrollback of mirrored
 * notifications. Tap a row for the full text and Stage 3 actions.
 */
class NotificationsScreen(
    private val ctx: Context,
    private val ui: Ui,
    /** Optional block above the list (forwarder switches / buttons). */
    settings: View? = null,
) {
    val view: FrameLayout
    private val list: ListView
    private val empty: LinearLayout
    private var rows: List<NotifLog.Item> = emptyList()
    private val onData = { refresh() }

    init {
        list = ListView(ctx).apply {
            divider = null; dividerHeight = 0; selector = ui.round(ui.line, 12f)
            isVerticalScrollBarEnabled = false
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = rows[i].id
            override fun getView(i: Int, convert: View?, parent: ViewGroup?) = row(rows[i])
        }
        list.setOnItemClickListener { _, _, i, _ -> detail(rows[i]) }
        empty = ui.empty("", "")
        val clear = ui.button("Clear", ui.line, ui.text1) {
            NotifLog.clear()
        }
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(16), ui.px(16), 0)
            setBackgroundColor(ui.bg)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Alerts", 26f, ui.text1, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(42)))
            }, ui.matchWrap(bottom = 8))
            if (settings != null) {
                addView(settings, ui.matchWrap(bottom = 12))
            } else {
                addView(ui.text(
                    "Mirrored notifications from your phone. Tap one to read or reply.",
                    13f, ui.text2
                ), ui.matchWrap(bottom = 8))
            }
            addView(empty, ui.matchWrap())
            addView(list, ui.fill())
        }
        view = FrameLayout(ctx).apply {
            setBackgroundColor(ui.bg)
            addView(pane)
        }
        NotifLog.listen(onData)
        refresh()
    }

    fun onShow() = refresh()

    fun destroy() = NotifLog.unlisten(onData)

    private fun refresh() {
        rows = NotifLog.all()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        val (t, b) = when {
            rows.isNotEmpty() -> "" to ""
            else -> "No alerts yet" to
                "Turn on forwarding on your phone. New ones show up here and as a pop-up."
        }
        (empty.getChildAt(0) as TextView).text = t
        (empty.getChildAt(1) as TextView).text = b
        ui.gone(empty, t.isEmpty())
    }

    private fun row(item: NotifLog.Item): View {
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ui.px(44), ui.px(44)).apply { marginEnd = ui.px(14) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.oval(ui.line)
            val pad = ui.px(6)
            setPadding(pad, pad, pad, pad)
            val d = decodeIcon(item.iconB64)
            if (d != null) {
                setImageDrawable(d)
                setPadding(0, 0, 0, 0)
            } else {
                setImageResource(android.R.drawable.ic_dialog_info)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text(item.app, 13f, ui.text2, lines = 1),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text(PhoneData.shortTime(item.whenMs), 13f, ui.text2))
            })
            addView(ui.text(item.title.ifEmpty { item.preview() }, 16f, ui.text1, true, lines = 1))
            if (item.title.isNotEmpty() && item.preview().isNotEmpty())
                addView(ui.text(item.preview(), 14f, ui.text2, lines = 2))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(6), ui.px(10), ui.px(6), ui.px(10))
            addView(icon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun detail(item: NotifLog.Item) {
        val body = item.body().ifEmpty { "(no text)" }
        val title = item.app + if (item.title.isNotEmpty()) "  ·  ${item.title}" else ""
        if (item.actions.isEmpty() || item.nid.isEmpty()) {
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val pad = ui.px(20)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ui.px(12), pad, ui.px(8))
            addView(ui.text(body, 15f, ui.text1))
        }
        val replyAct = item.actions.firstOrNull { it.reply }
        val input = if (replyAct != null) {
            EditText(ctx).apply {
                hint = replyAct.label.ifBlank { "Reply" }
                setTextColor(ui.text1)
                setHintTextColor(ui.text2)
                textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND
                setPadding(0, ui.px(12), 0, ui.px(8))
            }.also { col.addView(it) }
        } else null
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, ui.px(8), 0, 0)
        }
        val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(col)
            .setNegativeButton("Close", null)
            .create()
        fun fire(act: NotifLog.Action, text: String = "") {
            val svc = NowPlayingService.instance
            if (svc == null) {
                Toast.makeText(ctx, "Phone Link isn't running.", Toast.LENGTH_SHORT).show()
                return
            }
            svc.sendNotifAction(item.nid, act.id, text)
            Toast.makeText(ctx,
                if (text.isNotEmpty()) "Reply sent" else act.label,
                Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        if (replyAct != null && input != null) {
            buttons.addView(ui.button("Send", ui.accent) {
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(ctx, "Type a reply first.", Toast.LENGTH_SHORT).show()
                } else fire(replyAct, text)
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(42)
                ).apply { marginEnd = ui.px(8) }
            })
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) fire(replyAct, text)
                    true
                } else false
            }
        }
        for (act in item.actions.filter { !it.reply }.take(2)) {
            buttons.addView(ui.button(act.label, ui.line, ui.text1) {
                fire(act)
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(42)
                ).apply { marginStart = ui.px(4) }
            })
        }
        if (buttons.childCount > 0) col.addView(buttons)
        dlg.show()
    }

    private fun decodeIcon(b64: String): BitmapDrawable? {
        if (b64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            BitmapDrawable(ctx.resources, bmp)
        } catch (_: Exception) { null }
    }
}
