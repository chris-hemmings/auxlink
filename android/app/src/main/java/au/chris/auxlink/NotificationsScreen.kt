package au.chris.auxlink

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Alerts tab: forwarder controls (on the phone) + scrollback of mirrored
 * notifications. Tap a row for the full text.
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
                    "Mirrored notifications from your phone. Tap one to read the full text.",
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
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(item.app + if (item.title.isNotEmpty()) "  ·  ${item.title}" else "")
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
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
