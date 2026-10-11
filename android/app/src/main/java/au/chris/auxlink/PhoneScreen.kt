package au.chris.auxlink

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * The Phone tab: your phone's contacts, recent calls and a keypad, through
 * the Pi. Calling makes the call on your phone; the sound goes through the
 * car as with any call. [openChat] opens a conversation on the Messages tab.
 */
class PhoneScreen(private val ctx: Context, private val ui: Ui,
                  private val openChat: (number: String, name: String) -> Unit) {
    val view: LinearLayout
    private val contactsPane: LinearLayout
    private val recentsPane: FrameLayout
    private val keypadPane: LinearLayout
    private val search: EditText
    private val contactList: ListView
    private val contactEmpty: LinearLayout
    private val recentList: ListView
    private val recentEmpty: LinearLayout
    private val dialled: TextView
    private var filtered: List<PhoneData.Contact> = emptyList()
    private val onData = { refresh() }

    init {
        // --- Contacts ---
        search = EditText(ctx).apply {
            hint = "Search contacts"; setHintTextColor(ui.text2); setTextColor(ui.text1); textSize = 16f
            background = ui.round(ui.cardBg, 12f); setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12))
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = filter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        contactList = list()
        contactList.adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(i: Int) = filtered[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?) = contactRow(filtered[i])
        }
        contactList.setOnItemClickListener { _, _, i, _ -> choose(filtered[i]) }
        contactEmpty = ui.empty("", "")
        contactsPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, ui.matchWrap(bottom = 8))
            addView(contactEmpty, ui.matchWrap())
            addView(contactList, ui.fill())
        }

        // --- Recents ---
        recentList = list()
        recentList.adapter = object : BaseAdapter() {
            override fun getCount() = PhoneData.recents.size
            override fun getItem(i: Int) = PhoneData.recents[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?) = recentRow(PhoneData.recents[i])
        }
        recentList.setOnItemClickListener { _, _, i, _ ->
            val r = PhoneData.recents[i]
            if (r.number.isNotEmpty()) call(r.number, r.name.ifEmpty { PhoneData.nameFor(r.number) })
        }
        recentEmpty = ui.empty("No recent calls yet",
            "Your phone's call history appears here a minute after it connects to the Pi.")
        recentsPane = FrameLayout(ctx).apply { addView(recentList); addView(recentEmpty) }

        // --- Keypad ---
        dialled = ui.text("", 32f, ui.text1).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.px(8), 0, ui.px(8)); maxLines = 1
        }
        keypadPane = keypad()

        val content = FrameLayout(ctx).apply {
            addView(contactsPane); addView(recentsPane); addView(keypadPane)
        }
        val (bar, select) = ui.segments(listOf("Contacts", "Recents", "Keypad")) { show(it) }
        select(0)
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(16), ui.px(16), 0)
            addView(ui.text("Phone", 26f, ui.text1, true), ui.matchWrap(bottom = 12))
            addView(bar, ui.matchWrap(bottom = 12))
            addView(content, ui.fill())
        }
        show(0)
        PhoneData.listen(onData)
        refresh()
    }

    /** The tab was opened: ask the Pi for fresh contacts and calls. */
    fun onShow() {
        NowPlayingService.instance?.request(JSONObject().put("req", "phone"))
        refresh()
    }

    fun close() = PhoneData.unlisten(onData)

    private fun show(i: Int) {
        ui.gone(contactsPane, i != 0); ui.gone(recentsPane, i != 1); ui.gone(keypadPane, i != 2)
    }

    private fun refresh() {
        filter()
        (recentList.adapter as BaseAdapter).notifyDataSetChanged()
        ui.gone(recentEmpty, PhoneData.recents.isNotEmpty())
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val qd = q.filter { it.isDigit() }
        filtered = if (q.isEmpty()) PhoneData.contacts else PhoneData.contacts.filter { c ->
            c.name.lowercase().contains(q) ||
                (qd.length >= 3 && c.numbers.any { it.second.filter { ch -> ch.isDigit() }.contains(qd) })
        }
        (contactList.adapter as BaseAdapter).notifyDataSetChanged()
        val linked = NowPlayingService.linkOpen
        val (t, b) = when {
            filtered.isNotEmpty() -> "" to ""
            !linked -> "Not connected to the Pi" to "Plug this device into the Pi to see your phone's contacts."
            !PhoneData.contactsLoaded -> "Loading contacts…" to "Getting them from your phone through the Pi."
            q.isNotEmpty() -> "No matches" to "No contact matches \"$q\"."
            else -> "No contacts yet" to "The Pi copies your phone's contacts a minute after the phone " +
                "connects. On the phone, allow contact sharing for AuxLink-phone."
        }
        (contactEmpty.getChildAt(0) as TextView).text = t
        (contactEmpty.getChildAt(1) as TextView).text = b
        ui.gone(contactEmpty, t.isEmpty())
    }

    // ------------------------------------------------------------ actions

    private fun call(number: String, name: String) {
        val svc = NowPlayingService.instance
        if (svc == null || !NowPlayingService.linkOpen) {
            Toast.makeText(ctx, "Not connected to the Pi", Toast.LENGTH_SHORT).show(); return
        }
        svc.request(JSONObject().put("dial", number))
        Toast.makeText(ctx, "Calling " + name.ifEmpty { number } + " on your phone…", Toast.LENGTH_SHORT).show()
    }

    /** A contact was tapped: call or text one of their numbers. */
    private fun choose(c: PhoneData.Contact) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        for ((kind, num) in c.numbers) {
            val label = if (kind.isEmpty()) num else "$kind  $num"
            options += ("Call  $label") to { call(num, c.name) }
            options += ("Text  $label") to { openChat(num, c.name) }
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(c.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ rows

    private fun list() = ListView(ctx).apply {
        divider = null; dividerHeight = 0; selector = ui.round(ui.line, 12f)
        isVerticalScrollBarEnabled = false
    }

    private fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.px(6), ui.px(10), ui.px(6), ui.px(10))
        views.forEach { addView(it) }
    }

    private fun callButton(number: String, name: String) = TextView(ctx).apply {
        text = "✆"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = ui.oval(ui.good)
        layoutParams = LinearLayout.LayoutParams(ui.px(42), ui.px(42)).apply { marginStart = ui.px(10) }
        isFocusable = false
        setOnClickListener { call(number, name) }
    }

    private fun contactRow(c: PhoneData.Contact): View {
        val first = c.numbers.firstOrNull()
        val sub = (first?.let { (if (it.first.isEmpty()) "" else it.first + "  ") + it.second } ?: "") +
            if (c.numbers.size > 1) "   +${c.numbers.size - 1} more" else ""
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(c.name, 17f, ui.text1, lines = 1))
            addView(ui.text(sub, 13f, ui.text2, lines = 1))
        }
        return row(ui.avatar(c.name), texts.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }).apply { if (first != null) addView(callButton(first.second, c.name)) }
    }

    private fun recentRow(r: PhoneData.Recent): View {
        val name = r.name.ifEmpty { PhoneData.nameFor(r.number) }.ifEmpty { r.number.ifEmpty { "Unknown" } }
        val (arrow, colour, what) = when (r.kind) {
            "missed" -> Triple("↙", ui.bad, "Missed")
            "in" -> Triple("↙", ui.good, "Incoming")
            "out" -> Triple("↗", ui.accent, "Outgoing")
            else -> Triple("•", ui.text2, "Call")
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(name, 17f, if (r.kind == "missed") ui.bad else ui.text1, lines = 1))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text("$arrow ", 13f, colour, true))
                addView(ui.text(what + (if (r.name.isNotEmpty() && r.number.isNotEmpty()) "  ·  ${r.number}" else ""),
                    13f, ui.text2, lines = 1))
            })
        }
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return row(ui.avatar(name), texts, ui.text(PhoneData.shortTime(r.at), 13f, ui.text2))
            .apply { if (r.number.isNotEmpty()) addView(callButton(r.number, name)) }
    }

    // ------------------------------------------------------------ keypad

    private fun keypad(): LinearLayout {
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        val grid = GridLayout(ctx).apply { columnCount = 3; useDefaultMargins = false }
        for ((digit, letters) in keys) {
            val key = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ui.oval(ui.cardBg)
                addView(ui.text(digit, 28f, ui.text1).apply { gravity = Gravity.CENTER })
                if (letters.isNotEmpty()) addView(ui.text(letters, 10f, ui.text2).apply {
                    gravity = Gravity.CENTER; letterSpacing = 0.15f
                })
                setOnClickListener { dialled.append(digit) }
                if (digit == "0") setOnLongClickListener { dialled.append("+"); true }
            }
            grid.addView(key, GridLayout.LayoutParams().apply {
                width = ui.px(76); height = ui.px(76)
                setMargins(ui.px(10), ui.px(6), ui.px(10), ui.px(6))
            })
        }
        val back = ui.text("⌫", 24f, ui.text2).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.px(64), ui.px(64))
            setOnClickListener { dialled.text = dialled.text.dropLast(1) }
            setOnLongClickListener { dialled.text = ""; true }
        }
        val callKey = TextView(ctx).apply {
            text = "✆"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = ui.oval(ui.good)
            layoutParams = LinearLayout.LayoutParams(ui.px(72), ui.px(72)).apply {
                marginStart = ui.px(64); marginEnd = ui.px(0)
            }
            setOnClickListener {
                val n = dialled.text.toString()
                if (n.isNotEmpty()) call(n, PhoneData.nameFor(n))
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(dialled, ui.matchWrap())
            addView(grid)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.px(10), 0, ui.px(10))
                addView(callKey); addView(back)
            })
        }
    }
}
