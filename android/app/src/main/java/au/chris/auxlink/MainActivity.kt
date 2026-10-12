package au.chris.auxlink

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The app's one screen: is it connected, what is playing, what still needs
 * allowing (and why), and a few tools. Built in code (no layout files), in
 * a dark style that suits a car screen.
 */
class MainActivity : Activity() {
    companion object {
        /** Open a conversation on the Messages tab (from a text pop-up or notification). */
        const val EXTRA_CHAT_NUMBER = "chat_number"
        const val EXTRA_CHAT_NAME = "chat_name"
    }
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { update(); main.postDelayed(this, 1000) }
    }
    private val dp by lazy { resources.displayMetrics.density }

    // Colours
    private val bg = 0xFF0E1013.toInt()
    private val cardBg = 0xFF181B20.toInt()
    private val line = 0xFF262A31.toInt()
    private val text1 = 0xFFF2F4F7.toInt()
    private val text2 = 0xFF9AA3AF.toInt()
    private val accent = 0xFF3B82F6.toInt()
    private val good = 0xFF22C55E.toInt()
    private val warn = 0xFFF59E0B.toInt()
    private val bad = 0xFFEF4444.toInt()

    // Live parts of the screen
    private lateinit var pill: TextView
    private lateinit var linkTitle: TextView
    private lateinit var linkSub: TextView
    private lateinit var linkDot: View
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npState: TextView
    private lateinit var setupHead: TextView
    private lateinit var piRows: LinearLayout
    private lateinit var btRows: LinearLayout
    private lateinit var flowRows: LinearLayout
    private lateinit var checkRows: LinearLayout
    private var btShown = ""
    private var flowShown = ""
    private var checkShown: org.json.JSONObject? = null
    private var btResultShown: org.json.JSONObject? = null
    private var checking = false
    private var ticks = 0
    private lateinit var note: TextView
    private lateinit var details: TextView
    private val steps = mutableListOf<Step>()

    // Tabs: Phone, Messages, and this status screen ("Status")
    private lateinit var phone: PhoneScreen
    private lateinit var msgs: MessagesScreen
    private lateinit var pages: List<View>
    private lateinit var tabViews: List<TextView>
    private var tab = 2

    /** One line of the setup checklist. */
    private inner class Step(
        val title: String,
        val why: String,
        val action: String,
        val optional: Boolean,
        val shown: () -> Boolean,
        val done: () -> Boolean,
        val onTap: () -> Unit,
    ) {
        lateinit var row: View
        lateinit var icon: TextView
        lateinit var button: Button
        lateinit var state: TextView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(28))
        }
        col.addView(header())
        if (resources.configuration.screenWidthDp >= 840) {
            // Tablet: two columns of cards.
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(connectionCard()); left.addView(piCard()); left.addView(btCard()); left.addView(setupCard())
            right.addView(flowCard()); right.addView(toolsCard()); right.addView(detailsCard())
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) })
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(8) })
            })
        } else {
            col.addView(connectionCard())
            col.addView(piCard())
            col.addView(btCard())
            col.addView(flowCard())
            col.addView(setupCard())
            col.addView(toolsCard())
            col.addView(detailsCard())
        }
        col.addView(TextView(this).apply {
            text = "Phone Link v" + appVersion()
            setTextColor(text2); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, px(12), 0, 0)
        })
        val status = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(col)
        }
        val ui = Ui(this)
        phone = PhoneScreen(this, ui) { number, name -> showTab(1); msgs.open(number, name) }
        msgs = MessagesScreen(this, ui)
        pages = listOf(phone.view, msgs.view, status)
        val content = android.widget.FrameLayout(this).apply { pages.forEach { addView(it) } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardBg)
            setPadding(0, px(6), 0, px(6))
        }
        tabViews = listOf("✆" to "Phone", "✉" to "Messages", "♪" to "Status").mapIndexed { i, (icon, name) ->
            TextView(this).apply {
                text = icon + "\n" + name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, px(6), 0, px(6))
                setOnClickListener { showTab(i) }
                bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        // Back where you left off; the status screen until setup is done.
        val saved = try { getPreferences(MODE_PRIVATE).getInt("tab", 2) } catch (e: Exception) { 2 }
        showTab(if (hasAccess()) saved else 2)
        openChatFrom(intent)

        // First run: ask for what Android lets an app ask for directly.
        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !BtLink(this).permitted())
            want += Manifest.permission.BLUETOOTH_CONNECT
        if (!micAllowed()) want += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed())
            want += Manifest.permission.POST_NOTIFICATIONS
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openChatFrom(intent)
    }

    /** A tapped text: its conversation, found by number (or by name). */
    private fun openChatFrom(i: Intent?) {
        if (i == null || !i.hasExtra(EXTRA_CHAT_NUMBER)) return
        var number = i.getStringExtra(EXTRA_CHAT_NUMBER).orEmpty()
        val name = i.getStringExtra(EXTRA_CHAT_NAME).orEmpty()
        i.removeExtra(EXTRA_CHAT_NUMBER)
        if (number.isEmpty() && name.isNotEmpty()) {
            number = PhoneData.messages.lastOrNull { it.who == name }?.number
                ?: PhoneData.contacts.firstOrNull { it.name == name }?.numbers?.firstOrNull()?.second ?: ""
        }
        if (number.isEmpty()) { showTab(1); return }
        showTab(1)
        msgs.open(number, name)
    }

    private fun showTab(i: Int) {
        tab = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { k, t -> t.setTextColor(if (k == i) accent else text2) }
        try { getPreferences(MODE_PRIVATE).edit().putInt("tab", i).apply() } catch (e: Exception) {}
        when (i) { 0 -> phone.onShow(); 1 -> msgs.onShow() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 1 && msgs.back()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        phone.close(); msgs.destroy()
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); main.post(refresh); if (::pages.isInitialized) showTab(tab) }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }

    // ------------------------------------------------------------ sections

    private fun header(): View {
        val logo = TextView(this).apply {
            text = "✆"
            setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
            background = round(0xFF0D9488.toInt(), 14f)
            layoutParams = LinearLayout.LayoutParams(px(48), px(48))
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Phone Link"; setTextColor(text1); textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "Your phone and music, linked to your car"
                setTextColor(text2); textSize = 14f
            })
        }
        pill = TextView(this).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(px(12), px(6), px(12), px(6))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(16))
            addView(logo)
            addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill)
        }
    }

    private fun connectionCard(): View {
        val c = card()
        linkDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(12), px(12)).apply { marginEnd = px(12) }
        }
        linkTitle = TextView(this).apply { setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        linkSub = TextView(this).apply { setTextColor(text2); textSize = 14f }
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(linkDot)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(linkTitle); addView(linkSub)
            })
        })
        c.addView(divider())
        c.addView(label("NOW PLAYING"))
        npTitle = TextView(this).apply {
            setTextColor(text1); textSize = 20f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        npArtist = TextView(this).apply {
            setTextColor(text2); textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        npState = TextView(this).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(6), 0, 0) }
        c.addView(npTitle); c.addView(npArtist); c.addView(npState)
        return c
    }

    private fun piCard(): View {
        val c = card()
        c.addView(TextView(this).apply {
            text = "The Pi"; setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        piRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6), 0, 0) }
        c.addView(piRows)
        return c
    }

    /** One "label  value" line of the Pi card, with an optional coloured dot. */
    private fun piRow(label: String, value: String, dot: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(6), 0, px(6))
        addView(TextView(this@MainActivity).apply {
            text = label; setTextColor(text2); textSize = 14f
        }, LinearLayout.LayoutParams(px(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (dot != null) addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
        }, LinearLayout.LayoutParams(px(10), px(10)).apply { marginEnd = px(8) })
        addView(TextView(this@MainActivity).apply {
            text = value; setTextColor(text1); textSize = 15f; setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun updatePi(linked: Boolean) {
        // Ask every 5 s while this tab is open.
        if (linked && tab == 2 && ticks++ % 5 == 0)
            NowPlayingService.instance?.request(org.json.JSONObject().put("req", "pi"))
        val st = NowPlayingService.piStatus
        val fresh = st != null && System.currentTimeMillis() - NowPlayingService.piStatusAt < 20000
        piRows.removeAllViews()
        if (!linked || !fresh) {
            piRows.addView(TextView(this).apply {
                text = if (!linked) "Connect to the Pi to see its Wi-Fi and status."
                       else "Asking the Pi… (needs Pi update 1.0.77 or newer)"
                setTextColor(text2); textSize = 14f
            })
            return
        }
        st!!
        when (st.optString("wifi")) {
            "connected" -> {
                val sig = if (st.isNull("signal")) "" else "  ·  signal ${st.optInt("signal")}%"
                piRows.addView(piRow("Wi-Fi", "Connected to " + st.optString("ssid") + sig, good))
            }
            "setup" -> piRows.addView(piRow("Wi-Fi", "Setup Wi-Fi on (AuxLink-setup)", accent))
            "connecting" -> piRows.addView(piRow("Wi-Fi", "Connecting…", warn))
            "disconnected" -> piRows.addView(piRow("Wi-Fi", "Not connected", bad))
            else -> piRows.addView(piRow("Wi-Fi", "Off", text2))
        }
        if (st.optString("ip").isNotEmpty()) piRows.addView(piRow("IP address", st.optString("ip")))
        if (st.optString("eth_ip").isNotEmpty()) piRows.addView(piRow("Ethernet", st.optString("eth_ip"), good))
        val page = st.optString("ip").ifEmpty { st.optString("eth_ip") }
        if (page.isNotEmpty()) piRows.addView(piRow("Setup page", "http://$page"))
        if (st.optString("version").isNotEmpty()) piRows.addView(piRow("Software", "v" + st.optString("version")))
        val up = st.optLong("uptime")
        if (up > 0) piRows.addView(piRow("Running for",
            when { up < 3600 -> "${up / 60} min"; up < 86400 -> "${up / 3600} h ${up % 3600 / 60} min"
                   else -> "${up / 86400} days" }))
        if (st.optString("host").isNotEmpty()) piRows.addView(piRow("Name", st.optString("host")))
    }

    // ------------------------------------------------------------ Bluetooth

    private fun btCard(): View {
        val c = card()
        c.addView(TextView(this).apply {
            text = "Bluetooth"; setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        btRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(4), 0, 0) }
        c.addView(btRows)
        return c
    }

    private fun btDo(action: String, role: String) {
        NowPlayingService.instance?.request(org.json.JSONObject().put("bt_do",
            org.json.JSONObject().put("action", action).put("role", role)))
        Toast.makeText(this, when (action) {
            "pair" -> "Pairing open for 2 minutes"
            "connect" -> "Connecting… (up to 30 s)"
            "forget" -> "Forgetting…"
            else -> "Done"
        }, Toast.LENGTH_SHORT).show()
    }

    private fun updateBt(linked: Boolean) {
        val r = NowPlayingService.btResult
        if (r != null && r !== btResultShown) {
            btResultShown = r
            if (r.optString("error").isNotEmpty())
                Toast.makeText(this, "Couldn't do that: " + r.optString("error"), Toast.LENGTH_LONG).show()
        }
        val st = NowPlayingService.btStatus
        val key = (if (linked) "1" else "0") + (st?.toString() ?: "")
        if (key == btShown) return          // rebuild only on change (taps stay reliable)
        btShown = key
        btRows.removeAllViews()
        if (!linked || st == null) {
            btRows.addView(TextView(this).apply {
                text = if (!linked) "Connect to the Pi to see and manage Bluetooth."
                       else "Asking the Pi… (needs Pi update 1.0.84 or newer)"
                setTextColor(text2); textSize = 14f
            })
            return
        }
        if (st.optString("error").isNotEmpty()) {
            btRows.addView(TextView(this).apply { text = st.optString("error"); setTextColor(warn); textSize = 14f })
            return
        }
        val w = st.optJSONObject("window")
        if (w != null) {
            val role = w.optString("role")
            val name = when (role) { "car" -> "AuxLink"; "phone" -> "AuxLink-phone"; else -> "AuxLink-music" }
            val left = w.optInt("seconds_left")
            btRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = round(0xFF1F2A3D.toInt(), 10f)
                setPadding(px(12), px(10), px(8), px(10))
                addView(TextView(this@MainActivity).apply {
                    text = "Pairing open: on the ${if (role == "source") "music source" else role}, add Bluetooth " +
                        "device \"$name\" (${left / 60}:${"%02d".format(left % 60)} left)"
                    setTextColor(text1); textSize = 14f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pillButton("Cancel", false) { btDo("pair/cancel", role) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6); bottomMargin = px(4) })
        }
        val roles = mutableListOf("car" to "Car", "phone" to "Your phone")
        if (st.optString("source_kind") == "bluetooth") roles += "source" to "Music source"
        for ((role, label) in roles) {
            val d = st.optJSONObject(role) ?: org.json.JSONObject()
            val paired = d.optString("address").isNotEmpty()
            val connected = d.optBoolean("connected")
            val dotColour = if (connected) good else if (paired) warn else line
            val detail = if (!paired) "Not paired" else
                (d.optString("name").ifEmpty { d.optString("address") }) +
                    "  ·  " + (if (connected) "connected" else "not connected")
            btRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10), 0, px(4))
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColour) }
                }, LinearLayout.LayoutParams(px(12), px(12)).apply { marginEnd = px(12) })
                addView(textBlock(label, detail),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (paired && !connected) addView(pillButton("Connect", true) { btDo("connect", role) })
                if (paired) addView(pillButton("Forget", false) {
                    android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Forget the ${label.lowercase()}?")
                        .setMessage("It will need pairing again. On the ${label.lowercase()} too, remove the AuxLink entry before pairing again.")
                        .setPositiveButton("Forget") { _, _ -> btDo("forget", role) }
                        .setNegativeButton("Cancel", null).show()
                })
                if (!paired) addView(pillButton("Pair", true) { btDo("pair", role) })
            })
        }
    }

    // ------------------------------------------------------------ audio path

    private fun flowCard(): View {
        val c = card()
        c.addView(TextView(this).apply {
            text = "Audio path"; setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        flowRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(4), 0, 0) }
        c.addView(flowRows)
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, px(12), 0, 0)
            addView(pillButton("Full check", false) { runCheck(false) })
            addView(pillButton("Check and fix", true) { runCheck(true) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        checkRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, 0) }
        c.addView(checkRows)
        return c
    }

    private fun runCheck(fix: Boolean) {
        if (!NowPlayingService.connected()) { say("Not connected to the Pi"); return }
        checking = true
        NowPlayingService.instance?.request(org.json.JSONObject().put("req", "check").put("fix", fix))
        checkRows.removeAllViews()
        checkRows.addView(TextView(this).apply {
            text = if (fix) "Checking and fixing… (up to a minute)" else "Checking… (up to a minute)"
            setTextColor(text2); textSize = 14f
        })
    }

    private fun stColour(st: String) = when (st) { "ok" -> good; "warn" -> warn; "bad" -> bad; else -> text2 }

    private fun updateFlow(linked: Boolean) {
        val f = NowPlayingService.flow
        val key = (if (linked) "1" else "0") + (f?.toString() ?: "")
        if (key != flowShown) {
            flowShown = key
            flowRows.removeAllViews()
            if (!linked || f == null) {
                flowRows.addView(TextView(this).apply {
                    text = if (!linked) "Connect to the Pi to see the audio path."
                           else "Asking the Pi… (needs Pi update 1.0.84 or newer)"
                    setTextColor(text2); textSize = 14f
                })
            } else {
                val items = (0 until f.length()).mapNotNull { f.optJSONObject(it) }
                for ((sec, label) in listOf("input" to "INPUT", "processing" to "PROCESSING", "output" to "OUTPUT")) {
                    val part = items.filter { it.optString("sec") == sec }
                    if (part.isEmpty()) continue
                    val worst = part.map { it.optString("st") }.let {
                        when { "bad" in it -> "bad"; "warn" in it -> "warn"; "ok" in it -> "ok"; else -> "idle" }
                    }
                    flowRows.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, px(12), 0, px(2))
                        addView(View(this@MainActivity).apply {
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(stColour(worst)) }
                        }, LinearLayout.LayoutParams(px(10), px(10)).apply { marginEnd = px(10) })
                        addView(TextView(this@MainActivity).apply {
                            text = label; setTextColor(text1); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                            letterSpacing = 0.08f
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "  " + when (worst) { "ok" -> "Working"; "warn" -> "Check"; "bad" -> "Problem"; else -> "Idle" }
                            setTextColor(stColour(worst)); textSize = 12f
                        })
                    })
                    for (it in part) {
                        flowRows.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(px(4), px(4), 0, px(4))
                            addView(View(this@MainActivity).apply { setBackgroundColor(line) },
                                LinearLayout.LayoutParams(px(2), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = px(14) })
                            addView(LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(TextView(this@MainActivity).apply {
                                    text = it.optString("title"); setTextColor(text1); textSize = 15f
                                })
                                addView(TextView(this@MainActivity).apply {
                                    text = it.optString("detail"); setTextColor(stColour(it.optString("st")).let { c ->
                                        if (it.optString("st") == "ok" || it.optString("st") == "idle") text2 else c })
                                    textSize = 13f
                                })
                            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        })
                    }
                }
            }
        }
        // Full check result: its numbered steps, each line marked OK / WARN / FAIL.
        val r = NowPlayingService.checkResult
        if (r != null && r !== checkShown) {
            checkShown = r
            checking = false
            checkRows.removeAllViews()
            checkRows.addView(TextView(this).apply {
                text = if (r.optBoolean("fix")) "Check and fix" else "Full check"
                setTextColor(text1); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, px(4), 0, px(4))
            })
            for (raw in r.optString("output").lines()) {
                val l = raw.trimEnd()
                if (l.isBlank()) continue
                val t = l.trim()
                val (mark, colour, rest) = when {
                    t.startsWith("OK") -> Triple("✓", good, t.removePrefix("OK").trim())
                    t.startsWith("WARN") -> Triple("!", warn, t.removePrefix("WARN").trim())
                    t.startsWith("FAIL") -> Triple("✕", bad, t.removePrefix("FAIL").trim())
                    Regex("^\\d+\\.").containsMatchIn(t) -> Triple("", text1, t)
                    else -> Triple("", text2, t)
                }
                checkRows.addView(TextView(this).apply {
                    text = if (mark.isEmpty()) rest else "$mark  $rest"
                    setTextColor(colour); textSize = if (mark.isEmpty() && colour == text1) 14f else 13f
                    if (mark.isEmpty() && colour == text1) { typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(6), 0, 0) }
                    else setPadding(px(12), px(1), 0, px(1))
                })
            }
        }
    }

    private fun setupCard(): View {
        val c = card()
        setupHead = TextView(this).apply { setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        c.addView(setupHead)
        c.addView(TextView(this).apply {
            text = "Phone Link needs a few permissions. Each one says why."
            setTextColor(text2); textSize = 14f; setPadding(0, px(2), 0, px(4))
        })
        steps += Step("Notification access",
            "Lets Phone Link see what's playing (title, artist, album art) to show it in the car. Required.",
            "Open", false, { true }, ::hasAccess) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        steps += Step("Run in the background",
            "Stops Android closing Phone Link to save battery, which would drop track info and buttons.",
            "Allow", false, { true }, ::unrestricted) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        steps += Step("Microphone",
            "Only so Android offers \"Always\" for the USB connection to the Pi. Nothing is ever recorded.",
            "Allow", false, { true }, ::micAllowed) { askOrSettings(Manifest.permission.RECORD_AUDIO) }
        steps += Step("Notifications",
            "For incoming call and text alerts from your phone.",
            "Allow", false, { Build.VERSION.SDK_INT >= 33 }, ::notificationsAllowed) {
            if (Build.VERSION.SDK_INT >= 33) askOrSettings(Manifest.permission.POST_NOTIFICATIONS)
        }
        steps += Step("Call and text pop-ups",
            "Shows calls (with Answer and Hang up) and texts on top of your music app.",
            "Allow", false, { true }, { Settings.canDrawOverlays(this) }) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }
        steps += Step("USB access",
            "Android needs your OK before Phone Link can use the USB connection to the Pi. Allow it, and tick Always.",
            "Allow", false,
            { UsbLink(this).findDevice() != null }, { UsbLink(this).permitted() }) {
            UsbLink(this).askPermission()
        }
        steps += Step("No high-volume warning",
            "Optional: lets Phone Link keep Android's hearing warning off, so full volume always works. " +
                "Needs a computer once: with USB debugging on, run  adb shell pm grant au.chris.auxlink " +
                "android.permission.WRITE_SECURE_SETTINGS", "How", true, { true },
            { checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED }) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Turn off the high-volume warning")
                .setMessage("Android only lets a computer allow this, once:\n\n" +
                    "1. Turn on USB debugging (Settings > Developer options).\n" +
                    "2. Connect this device to a computer with adb.\n" +
                    "3. Run:\n   adb shell pm grant au.chris.auxlink android.permission.WRITE_SECURE_SETTINGS\n\n" +
                    "Phone Link then keeps the warning off, including after restarts.")
                .setPositiveButton("OK", null).show()
        }
        steps += Step("Nearby devices (Bluetooth)",
            "Only for the Bluetooth music source: lets Phone Link reach the Pi without a cable.",
            "Allow", true, { Build.VERSION.SDK_INT >= 31 }, { BtLink(this).permitted() }) {
            if (Build.VERSION.SDK_INT >= 31) askOrSettings(Manifest.permission.BLUETOOTH_CONNECT)
        }
        for (s in steps) c.addView(stepRow(s))
        return c
    }

    private fun toolsCard(): View {
        val c = card()
        c.addView(TextView(this).apply {
            text = "Tools"; setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        c.addView(toolRow("Fix sound",
            "Music playing here but silent in the car? This restarts the stream to the car.", "Fix") {
            send("fix", "Asked the Pi to restart the music stream to the car.")
        })
        c.addView(toolRow("Test the call pop-up",
            "Shows a pretend call for 8 seconds, so you can see where it appears. Switch to your music app to see it on top.",
            "Test") {
            val svc = NowPlayingService.instance
            if (svc == null) say("Phone Link isn't running yet: allow Notification access first.")
            else { svc.testCallBar(); say("Showing a pretend call for 8 seconds.") }
        })
        c.addView(toolRow("Setup Wi-Fi",
            "Turns on the Pi's \"AuxLink-setup\" Wi-Fi for 15 minutes, to change its settings.", "Turn on") {
            send("setup", "Setup Wi-Fi is turning on (up to 30 s). Join \"AuxLink-setup\" " +
                "(password auxlink-setup, unless you changed it), then tap Open.")
        })
        c.addView(toolRow("Volume to full",
            "Sets this device's media volume to 100% (use the car's volume). If Android warns about " +
                "high volume, tap OK.", "Set") {
            val svc = NowPlayingService.instance
            if (svc == null) say("Phone Link isn't running yet: allow Notification access first.")
            else { svc.volumeMax(true); say("Volume set to full. If Android asks, tap OK.") }
        })
        c.addView(toolRow("Restart the Pi",
            "Reboots the Pi: music and calls stop for about a minute while it starts again.", "Restart") {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Restart the Pi?")
                .setMessage("Music and calls stop for about a minute while the Pi starts again. " +
                    "It reconnects to the car and this device by itself.")
                .setPositiveButton("Restart") { _, _ ->
                    send("reboot", "The Pi is restarting. It'll be back in about a minute.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        c.addView(toolRow("Setup page",
            "Opens the Pi's settings in the browser (on its setup Wi-Fi or your home Wi-Fi).", "Open") {
            openSetup()
        })
        note = TextView(this).apply {
            setTextColor(text1); textSize = 14f; visibility = View.GONE
            background = round(0xFF1F2A3D.toInt(), 10f)
            setPadding(px(12), px(10), px(12), px(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) }
        }
        c.addView(note)
        return c
    }

    private fun detailsCard(): View {
        val c = card()
        details = TextView(this).apply {
            setTextColor(text2); textSize = 12f; typeface = Typeface.MONOSPACE
            visibility = View.GONE; setPadding(0, px(10), 0, 0)
            setTextIsSelectable(true)
        }
        val head = TextView(this).apply {
            text = "Details for troubleshooting  ▾"
            setTextColor(text2); textSize = 14f
            setOnClickListener {
                val open = details.visibility != View.VISIBLE
                details.visibility = if (open) View.VISIBLE else View.GONE
                text = "Details for troubleshooting  " + if (open) "▴" else "▾"
            }
        }
        c.addView(head); c.addView(details)
        return c
    }

    // ------------------------------------------------------------ updating

    private fun update() {
        val linked = NowPlayingService.connected()
        val running = NowPlayingService.instance != null
        val usb = UsbLink(this).kind()      // the Pi's USB-C, the XIAO, or nothing

        // Connection
        val (title, sub, colour) = when {
            !hasAccess() -> Triple("Not set up yet", "Allow Notification access below to get started.", warn)
            linked -> Triple("Connected to the Pi", "Over " + NowPlayingService.linkKind.ifEmpty { "USB" } +
                ". Track info and car buttons are working.", good)
            !running -> Triple("Starting…", "Phone Link is starting in the background.", warn)
            usb != null && !UsbLink(this).permitted() -> Triple("Waiting for USB access",
                "Tap Allow next to USB access below.", warn)
            else -> Triple("Not connected", "Plug this device into the Pi's USB-C port (or the XIAO). " +
                "With the Bluetooth music source, connect to AuxLink-music.", bad)
        }
        linkTitle.text = title
        linkSub.text = sub
        linkDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colour) }
        pill.text = if (linked) "Connected" else if (hasAccess()) "Not connected" else "Setup needed"
        pill.setTextColor(colour)
        pill.background = GradientDrawable().apply {
            cornerRadius = 999f; setColor(colour and 0x00FFFFFF or 0x26000000)
        }

        // Now playing (what was last sent to the Pi)
        val np = try { JSONObject(NowPlayingService.nowPlaying) } catch (e: Exception) { null }
        val t = np?.optString("title").orEmpty()
        if (t.isEmpty()) {
            npTitle.text = "Nothing playing"
            npArtist.text = "Start music in any app: it shows here and in the car."
            npState.visibility = View.GONE
        } else {
            npTitle.text = t
            npArtist.text = listOf(np?.optString("artist").orEmpty(), np?.optString("album").orEmpty())
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
            val playing = np?.optString("state") == "Playing"
            npState.text = if (playing) "▶  PLAYING" else "❚❚  PAUSED"
            npState.setTextColor(if (playing) good else text2)
            npState.visibility = View.VISIBLE
        }

        updatePi(linked)
        updateBt(linked)
        updateFlow(linked)

        // Setup checklist
        var left = 0
        for (s in steps) {
            val show = s.shown()
            s.row.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) continue
            val ok = s.done()
            if (!ok && !s.optional) left++
            s.icon.text = if (ok) "✓" else if (s.optional) "–" else "!"
            s.icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ok) good else if (s.optional) line else warn)
            }
            s.button.visibility = if (ok) View.GONE else View.VISIBLE
            s.state.visibility = if (ok) View.VISIBLE else View.GONE
        }
        setupHead.text = if (left == 0) "Setup  ·  All done" else "Setup  ·  $left to do"
        setupHead.setTextColor(if (left == 0) text1 else warn)

        // Details
        details.text = buildString {
            appendLine("Link: " + if (linked) NowPlayingService.linkKind else "none")
            appendLine("USB: " + (usb ?: "nothing plugged in"))
            appendLine("Service running: " + if (running) "yes" else "no")
            appendLine("Car (CAN): " + CanState.status)
            if (CallBar.lastProblem.isNotEmpty()) appendLine("Pop-up problem: " + CallBar.lastProblem)
            if (!linked && usb == null) { appendLine("Bluetooth, last try:"); appendLine(BtLink.lastTry) }
            appendLine("Last sent to the Pi:")
            append(NowPlayingService.lastSent)
        }
    }

    // ------------------------------------------------------------ actions

    /** The setup page wherever the Pi is: its setup Wi-Fi (10.42.0.1), else
     *  the same network as this device (auxlink.local, looked up here and
     *  opened by address - browsers often can't find .local names). */
    private fun openSetup() {
        say("Looking for the Pi…")
        Thread {
            fun answers(host: String) = try {
                Socket().use { it.connect(InetSocketAddress(host, 80), 1500) }; true
            } catch (e: Exception) { false }
            val url = if (answers("10.42.0.1")) "http://10.42.0.1/" else {
                val ip = try { InetAddress.getByName("auxlink.local").hostAddress } catch (e: Exception) { null }
                if (ip != null && answers(ip)) "http://$ip/" else null
            }
            main.post {
                say(if (url != null) "Opening $url" else
                    "Pi not found. Tap Turn on under Setup Wi-Fi, join AuxLink-setup, then try again. " +
                    "(On home Wi-Fi: http://auxlink.local, or the Pi's address from your router.)")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url ?: "http://auxlink.local/")))
                } catch (e: Exception) {
                    Toast.makeText(this, "No web browser on this device", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun send(cmd: String, ok: String) {
        val svc = NowPlayingService.instance
        if (svc == null) { say("Phone Link isn't running yet: allow Notification access first."); return }
        say("Sending…")
        svc.command(cmd) { sent ->
            say(if (sent) ok else "Couldn't reach the Pi. Check this device is plugged into the Pi " +
                "(or connected to AuxLink-music with the Bluetooth source).")
        }
    }

    private fun say(msg: String) { note.text = msg; note.visibility = View.VISIBLE }

    /** Ask for a permission; if Android won't ask again, open the app's settings. */
    private fun askOrSettings(perm: String) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        if (shouldShowRequestPermissionRationale(perm) || !asked.contains(perm)) {
            asked += perm
            requestPermissions(arrayOf(perm), 2)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private val asked = mutableSetOf<String>()

    // ------------------------------------------------------------ checks

    private fun unrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun micAllowed() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val me = ComponentName(this, NowPlayingService::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    private fun appVersion() = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    // ------------------------------------------------------------ building blocks

    private fun px(v: Int) = (v * dp).toInt()

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * dp
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(cardBg, 16f)
        setPadding(px(18), px(16), px(18), px(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(14) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
            topMargin = px(14); bottomMargin = px(12)
        }
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; setTextColor(text2); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f; setPadding(0, 0, 0, px(4))
    }

    private fun pillButton(label: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.WHITE else text1)
        background = round(if (primary) accent else line, 20f)
        minWidth = px(84); minimumWidth = px(84)
        setPadding(px(16), 0, px(16), 0)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)).apply {
            marginStart = px(12)
        }
        setOnClickListener { click() }
    }

    private fun textBlock(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = title; setTextColor(text1); textSize = 16f })
        addView(TextView(this@MainActivity).apply {
            text = body; setTextColor(text2); textSize = 13f; setPadding(0, px(2), 0, 0)
        })
    }

    private fun stepRow(s: Step): View {
        s.icon = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px(26), px(26)).apply { marginEnd = px(14) }
        }
        s.button = pillButton(s.action, !s.optional) { s.onTap() }
        s.state = TextView(this).apply {
            text = "Done"; setTextColor(good); textSize = 13f; setPadding(px(12), 0, 0, 0)
        }
        val title = if (s.optional) s.title + "  (optional)" else s.title
        s.row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(4))
            addView(s.icon)
            addView(textBlock(title, s.why), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(s.state); addView(s.button)
        }
        return s.row
    }

    private fun toolRow(title: String, body: String, action: String, click: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, 0)
            addView(textBlock(title, body), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillButton(action, false, click))
        }
}
