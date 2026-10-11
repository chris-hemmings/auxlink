package au.chris.auxlink

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * The Messages tab: your phone's texts through the Pi, as conversations,
 * with replies sent by your phone. Needs "Text messages in the car" on in
 * the Pi's settings.
 */
class MessagesScreen(private val ctx: Context, private val ui: Ui) {
    data class Convo(val key: String, val number: String, val name: String,
                     val last: PhoneData.Message, val items: List<PhoneData.Message>)

    val view: FrameLayout
    private val listPane: LinearLayout
    private val chatPane: LinearLayout
    private val convoList: ListView
    private val listEmpty: LinearLayout
    private val chatTitle: TextView
    private val chatSub: TextView
    private val bubbles: LinearLayout
    private val bubbleScroll: ScrollView
    private val input: EditText
    private var convos: List<Convo> = emptyList()
    private var openNumber = ""      // the conversation shown ("" = the list)
    private var openName = ""
    private val onData = { refresh() }

    init {
        // --- Conversation list ---
        convoList = ListView(ctx).apply {
            divider = null; dividerHeight = 0; selector = ui.round(ui.line, 12f)
            isVerticalScrollBarEnabled = false
        }
        convoList.adapter = object : BaseAdapter() {
            override fun getCount() = convos.size
            override fun getItem(i: Int) = convos[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?) = convoRow(convos[i])
        }
        convoList.setOnItemClickListener { _, _, i, _ -> open(convos[i].number, convos[i].name) }
        listEmpty = ui.empty("", "")
        val newBtn = ui.button("New message", ui.accent) { newMessage() }
        listPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(16), ui.px(16), 0)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Messages", 26f, ui.text1, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(newBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(42)))
            }, ui.matchWrap(bottom = 12))
            addView(listEmpty, ui.matchWrap())
            addView(convoList, ui.fill())
        }

        // --- One conversation ---
        chatTitle = ui.text("", 19f, ui.text1, true, lines = 1)
        chatSub = ui.text("", 13f, ui.text2, lines = 1)
        val back = ui.text("‹", 34f, ui.text1).apply {
            setPadding(ui.px(4), 0, ui.px(16), ui.px(4))
            setOnClickListener { close() }
        }
        val callBtn = TextView(ctx).apply {
            text = "✆"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = ui.oval(ui.good)
            layoutParams = LinearLayout.LayoutParams(ui.px(42), ui.px(42))
            setOnClickListener {
                NowPlayingService.instance?.request(JSONObject().put("dial", openNumber))
                Toast.makeText(ctx, "Calling " + openName.ifEmpty { openNumber } + " on your phone…",
                    Toast.LENGTH_SHORT).show()
            }
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(12), ui.px(10), ui.px(16), ui.px(10))
            background = ui.round(ui.cardBg, 0f)
            addView(back)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(chatTitle); addView(chatSub)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(callBtn)
        }
        bubbles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12))
        }
        bubbleScroll = ScrollView(ctx).apply { addView(bubbles); isVerticalScrollBarEnabled = false }

        // Quick replies: one tap, nothing to type while driving.
        val quick = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (q in listOf("On my way", "Driving, I'll call you soon", "Yes", "No", "OK 👍", "Running late")) {
            quick.addView(ui.text(q, 14f, ui.text1).apply {
                background = ui.round(ui.line, 18f)
                setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8))
                setOnClickListener { send(q) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.px(8) })
        }
        input = EditText(ctx).apply {
            hint = "Text message"; setHintTextColor(ui.text2); setTextColor(ui.text1); textSize = 16f
            background = ui.round(ui.cardBg, 22f); setPadding(ui.px(16), ui.px(10), ui.px(16), ui.px(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val sendBtn = ui.button("Send", ui.accent) { send(input.text.toString()) }
        chatPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, ui.matchWrap())
            addView(bubbleScroll, ui.fill())
            addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6))
                addView(quick)
            }, ui.matchWrap())
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.px(12), ui.px(4), ui.px(12), ui.px(12))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(44)).apply {
                    marginStart = ui.px(8)
                })
            }, ui.matchWrap())
        }
        view = FrameLayout(ctx).apply { addView(listPane); addView(chatPane) }
        ui.gone(chatPane, true)
        PhoneData.listen(onData)
        refresh()
    }

    /** The tab was opened: ask the Pi for the latest texts. */
    fun onShow() {
        NowPlayingService.instance?.request(JSONObject().put("req", "messages"))
        if (PhoneData.contacts.isEmpty())   // names for numbers
            NowPlayingService.instance?.request(JSONObject().put("req", "phone"))
        refresh()
    }

    fun destroy() = PhoneData.unlisten(onData)

    /** Back from a conversation; false if already on the list. */
    fun back(): Boolean { if (openNumber.isEmpty()) return false; close(); return true }

    fun open(number: String, name: String) {
        openNumber = number
        openName = name.ifEmpty { PhoneData.nameFor(number) }
        ui.gone(listPane, true); ui.gone(chatPane, false)
        refresh()
    }

    private fun close() {
        openNumber = ""
        ui.gone(chatPane, true); ui.gone(listPane, false)
        refresh()
    }

    // ------------------------------------------------------------ data

    private fun allMessages() = (PhoneData.messages + PhoneData.sending.values).sortedBy { it.ts }

    private fun build(): List<Convo> =
        allMessages().filter { it.number.isNotEmpty() }.groupBy { PhoneData.key(it.number) }.map { (k, items) ->
            val number = items.last().number
            val name = items.lastOrNull { it.who.isNotEmpty() }?.who?.takeIf { it != number }
                ?: PhoneData.nameFor(number)
            Convo(k, number, name, items.last(), items)
        }.sortedByDescending { it.last.ts }

    private fun refresh() {
        convos = build()
        (convoList.adapter as BaseAdapter).notifyDataSetChanged()
        val (t, b) = when {
            !PhoneData.messagesEnabled -> "Text messages are off" to
                "Turn on \"Text messages in the car\" on the Pi's setup page (Settings tab)."
            convos.isNotEmpty() -> "" to ""
            !NowPlayingService.linkOpen -> "Not connected to the Pi" to
                "Plug this device into the Pi to see your phone's texts."
            !PhoneData.messagesLoaded -> "Loading texts…" to "Getting them from your phone through the Pi."
            else -> "No texts yet" to "New texts to your phone show up here. Tap New message to send one."
        }
        (listEmpty.getChildAt(0) as TextView).text = t
        (listEmpty.getChildAt(1) as TextView).text = b
        ui.gone(listEmpty, t.isEmpty())
        if (openNumber.isNotEmpty()) showChat()
    }

    private fun showChat() {
        chatTitle.text = openName.ifEmpty { openNumber }
        chatSub.text = if (openName.isNotEmpty()) openNumber else "Sent from your phone"
        val key = PhoneData.key(openNumber)
        val items = allMessages().filter { PhoneData.key(it.number) == key }
        bubbles.removeAllViews()
        if (items.isEmpty())
            bubbles.addView(ui.text("No texts with ${openName.ifEmpty { openNumber }} yet. " +
                "Type below, or tap a quick reply.", 14f, ui.text2).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.px(24), 0, 0)
            }, ui.matchWrap())
        for (m in items) bubbles.addView(bubble(m))
        bubbleScroll.post { bubbleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ sending

    private fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || openNumber.isEmpty()) return
        val svc = NowPlayingService.instance
        if (svc == null || !NowPlayingService.linkOpen) {
            Toast.makeText(ctx, "Not connected to the Pi", Toast.LENGTH_SHORT).show(); return
        }
        if (!PhoneData.messagesEnabled) {
            Toast.makeText(ctx, "Text messages are off in the Pi's settings", Toast.LENGTH_LONG).show(); return
        }
        val id = System.currentTimeMillis().toString()
        val at = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).format(java.util.Date())
        PhoneData.sending[id] = PhoneData.Message(false, "", openNumber, t, at, "sending", System.currentTimeMillis())
        svc.request(JSONObject().put("sms", JSONObject().put("to", openNumber).put("text", t).put("id", id)))
        input.setText("")
        refresh()
    }

    /** New message: pick a contact or type a number. */
    private fun newMessage() {
        val names = PhoneData.contacts.flatMap { c ->
            c.numbers.map { (kind, num) -> Triple(c.name, num, if (kind.isEmpty()) num else "$kind  $num") }
        }
        val field = EditText(ctx).apply {
            hint = "Name or number"; inputType = InputType.TYPE_CLASS_TEXT
            setPadding(ui.px(20), ui.px(14), ui.px(20), ui.px(14))
        }
        val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("New message")
            .setView(field)
            .setPositiveButton("Next", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = field.text.toString().trim()
                val digits = q.filter { it.isDigit() || it == '+' }
                val matches = names.filter { it.first.contains(q, ignoreCase = true) }
                when {
                    q.isEmpty() -> {}
                    digits.length >= 3 && digits.length >= q.count { !it.isWhitespace() } - 1 -> {
                        dlg.dismiss(); open(digits, "")
                    }
                    matches.size == 1 -> { dlg.dismiss(); open(matches[0].second, matches[0].first) }
                    matches.isNotEmpty() -> {
                        dlg.dismiss()
                        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Text who?")
                            .setItems(matches.take(30).map { it.first + "  ·  " + it.third }.toTypedArray()) { _, i ->
                                open(matches[i].second, matches[i].first)
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    else -> Toast.makeText(ctx, "No contact matches \"$q\"", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dlg.show()
    }

    // ------------------------------------------------------------ rows

    private fun convoRow(c: Convo): View {
        val name = c.name.ifEmpty { c.number }
        val preview = (if (c.last.incoming) "" else "You: ") + c.last.text.replace("\n", " ")
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text(name, 17f, ui.text1, lines = 1),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text(PhoneData.shortTime(c.last.ts), 13f, ui.text2))
            })
            addView(ui.text(preview, 14f, ui.text2, lines = 2))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(6), ui.px(10), ui.px(6), ui.px(10))
            addView(ui.avatar(name, 48))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun bubble(m: PhoneData.Message): View {
        val mine = !m.incoming
        val failed = m.status.startsWith("failed")
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(if (failed) 0xFF5B1D1D.toInt() else if (mine) ui.accent else ui.cardBg, 18f)
            setPadding(ui.px(14), ui.px(9), ui.px(14), ui.px(9))
            addView(ui.text(m.text, 16f, if (mine) Color.WHITE else ui.text1).apply { setTextIsSelectable(true) })
            val status = when {
                m.status == "sending" -> "Sending…"
                failed -> "Not sent: " + m.status.removePrefix("failed: ").ifEmpty { "error" }
                else -> PhoneData.shortTime(m.ts)
            }
            if (status.isNotEmpty())
                addView(ui.text(status, 11f, if (mine) 0xCCFFFFFF.toInt() else ui.text2).apply {
                    gravity = if (mine) Gravity.END else Gravity.START
                })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(if (mine) ui.px(48) else 0, ui.px(4), if (mine) 0 else ui.px(48), ui.px(4))
            addView(b)
        }
    }
}
