package au.chris.auxlink

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
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
    private val favPane: android.widget.FrameLayout
    private val favList: ListView
    private val favEmpty: LinearLayout
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
        contactList.setOnItemLongClickListener { _, _, i, _ -> choose(filtered[i]); true }
        contactEmpty = ui.empty("", "")
        contactsPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, ui.matchWrap(bottom = 8))
            addView(contactEmpty, ui.matchWrap())
            addView(withIndex(contactList), ui.fill())
        }

        // --- Favourites (the phone's starred contacts) ---
        favList = list()
        favList.adapter = object : BaseAdapter() {
            override fun getCount() = PhoneData.favourites.size
            override fun getItem(i: Int) = PhoneData.favourites[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?) = contactRow(PhoneData.favourites[i])
        }
        favList.setOnItemLongClickListener { _, _, i, _ -> choose(PhoneData.favourites[i]); true }
        favEmpty = ui.empty("No favourites yet",
            "Star contacts on your phone: they show here, and in the car, after the next sync.")
        favPane = android.widget.FrameLayout(ctx).apply { addView(favList); addView(favEmpty) }

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
            addView(favPane); addView(contactsPane); addView(recentsPane)
            if (!ui.wide) addView(keypadPane)
        }
        val tabs = if (ui.wide) listOf("Favourites", "Contacts", "Recents")
                   else listOf("Favourites", "Contacts", "Recents", "Keypad")
        val (bar, select) = ui.segments(tabs) { show(it) }
        select(0)
        val lists = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(16), ui.px(16), 0)
            addView(ui.text("Phone", 26f, ui.text1, true), ui.matchWrap(bottom = 12))
            addView(bar, ui.matchWrap(bottom = 12))
            addView(content, ui.fill())
        }
        view = if (ui.wide) {
            // Tablet: the lists on the left, the keypad always on the right.
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(lists, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(View(ctx).apply { setBackgroundColor(ui.line) },
                    LinearLayout.LayoutParams(ui.px(1), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(FrameLayout(ctx).apply {
                    setPadding(ui.px(16), ui.px(16), ui.px(16), ui.px(16))
                    addView(keypadPane, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                }, LinearLayout.LayoutParams(ui.px(420), ViewGroup.LayoutParams.MATCH_PARENT))
            }
        } else lists
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
        ui.gone(favPane, i != 0); ui.gone(contactsPane, i != 1); ui.gone(recentsPane, i != 2)
        if (!ui.wide) ui.gone(keypadPane, i != 3)
    }

    private fun refresh() {
        filter()
        (recentList.adapter as BaseAdapter).notifyDataSetChanged()
        ui.gone(recentEmpty, PhoneData.recents.isNotEmpty())
        (favList.adapter as BaseAdapter).notifyDataSetChanged()
        ui.gone(favEmpty, PhoneData.favourites.isNotEmpty())
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        val qd = q.filter { it.isDigit() }
        filtered = if (q.isEmpty()) PhoneData.contacts else PhoneData.contacts.filter { c ->
            c.name.lowercase().contains(q) ||
                (qd.length >= 3 && c.numbers.any { it.second.filter { ch -> ch.isDigit() }.contains(qd) })
        }
        (contactList.adapter as BaseAdapter).notifyDataSetChanged()
        val linked = NowPlayingService.connected()
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

    // ------------------------------------------------------------ A-Z index

    /** The contacts list with an A-Z bar on its right: tap a letter, or press
     *  and slide along the bar, to jump there (a big letter shows where). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun withIndex(list: ListView): View {
        val letters = listOf("#") + ('A'..'Z').map { it.toString() }
        val bubble = TextView(ctx).apply {
            textSize = 40f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; background = ui.round(ui.accent, 20f)
            visibility = View.GONE
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(4))
            for (l in letters) addView(ui.text(l, 11f, ui.accent, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        fun jump(y: Float) {
            val i = ((y / bar.height.coerceAtLeast(1)) * letters.size).toInt().coerceIn(0, letters.size - 1)
            val letter = letters[i]
            // The first contact at or after this letter ("#": names not starting with a letter).
            val pos = if (letter == "#") 0 else filtered.indexOfFirst {
                val c = it.name.trim().firstOrNull()?.uppercaseChar() ?: ' '
                c.isLetter() && c.toString() >= letter
            }.let { if (it < 0) filtered.size - 1 else it }
            if (pos >= 0) list.setSelection(pos)
            bubble.text = letter
            bubble.visibility = View.VISIBLE
        }
        bar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    bar.background = ui.round(ui.cardBg, 14f)
                    jump(e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bar.background = null
                    bubble.postDelayed({ bubble.visibility = View.GONE }, 400)
                }
            }
            true
        }
        return FrameLayout(ctx).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(bar, LinearLayout.LayoutParams(ui.px(32), ViewGroup.LayoutParams.MATCH_PARENT))
            })
            addView(bubble, FrameLayout.LayoutParams(ui.px(84), ui.px(84), Gravity.CENTER))
        }
    }

    // ------------------------------------------------------------ actions

    private fun call(number: String, name: String) {
        val svc = NowPlayingService.instance
        if (svc == null || !NowPlayingService.connected()) {
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

    private fun callButton(number: String, name: String) =
        ui.iconButton(R.drawable.ic_call, ui.good) { call(number, name) }

    private fun textButton(number: String, name: String) =
        ui.iconButton(R.drawable.ic_text, ui.accent) { openChat(number, name) }

    /** The number to call / text first: the mobile one if there is one. */
    private fun mainNumber(c: PhoneData.Contact) =
        (c.numbers.firstOrNull { it.first == "mobile" } ?: c.numbers.firstOrNull())?.second

    private fun contactRow(c: PhoneData.Contact): View {
        val first = c.numbers.firstOrNull()
        val sub = (first?.let { (if (it.first.isEmpty()) "" else it.first + "  ") + it.second } ?: "") +
            if (c.numbers.size > 1) "   +${c.numbers.size - 1} more" else ""
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(c.name, 17f, ui.text1, lines = 1))
            addView(ui.text(sub, 13f, ui.text2, lines = 1))
        }
        val main = mainNumber(c)
        return row(ui.avatar(c.name), texts.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }).apply {
            if (main != null) { addView(textButton(main, c.name)); addView(callButton(main, c.name)) }
        }
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
        val back = ui.text("⌫", 26f, ui.text2).apply {
            gravity = Gravity.CENTER
            setOnClickListener { dialled.text = dialled.text.dropLast(1) }
            setOnLongClickListener { dialled.text = ""; true }
        }
        val callKey = ui.iconButton(R.drawable.ic_call, ui.good, 76) {
            val n = dialled.text.toString()
            if (n.isNotEmpty()) call(n, PhoneData.nameFor(n))
        }
        // Bottom row in the keypad's three columns: (empty) | call | backspace,
        // so the call button sits centred under 0.
        fun cell(v: View?) = FrameLayout(ctx).apply {
            if (v != null) addView(v, FrameLayout.LayoutParams(ui.px(76), ui.px(76), Gravity.CENTER))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(dialled, ui.matchWrap())
            addView(grid)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.px(6), 0, ui.px(10))
                for (v in listOf(null, callKey, back))
                    addView(cell(v), LinearLayout.LayoutParams(ui.px(96), ui.px(88)))
            })
        }
    }
}
