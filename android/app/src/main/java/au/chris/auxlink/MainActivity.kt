package au.chris.auxlink

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

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
        /** Open Alerts focused on one mirrored notification (nid). */
        const val EXTRA_OPEN_NOTIF_NID = "open_notif_nid"
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
    private lateinit var details: TextView
    private val steps = mutableListOf<Step>()
    private var setupCollapsed = true
    private var setupBody: LinearLayout? = null
    private var setupToggle: TextView? = null
    private var recoveryShownAt = 0L
    private var updateDlg: android.app.AlertDialog? = null
    private var updateTitleTv: TextView? = null
    private var updateMsgTv: TextView? = null
    private var updateBar: ProgressBar? = null
    private var toolsMusicRows: MutableList<View> = mutableListOf()
    private var roleBlock: LinearLayout? = null

    // Tabs: Phone, Messages, Status, Alerts (mirrored notifications)
    private lateinit var phone: PhoneScreen
    private lateinit var msgs: MessagesScreen
    private lateinit var alerts: NotificationsScreen
    private lateinit var pages: List<View>
    private lateinit var tabViews: List<TextView>
    private lateinit var tabBar: LinearLayout
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
        // Forwarder controls live on Alerts (not buried in Tools).
        alerts = NotificationsScreen(this, ui, settings = notifForwardBlock())
        // Keep Status at index 2 so saved tabs / poll logic stay stable; Alerts is last.
        pages = listOf(phone.view, msgs.view, status, alerts.view)
        val content = android.widget.FrameLayout(this).apply { pages.forEach { addView(it) } }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardBg)
            setPadding(0, px(6), 0, px(6))
        }
        tabViews = listOf("✆" to "Phone", "✉" to "Messages", "♪" to "Status", "🔔" to "Alerts").mapIndexed { i, (icon, name) ->
            TextView(this).apply {
                text = icon + "\n" + name; textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, px(6), 0, px(6))
                setOnClickListener { showTab(i) }
                // Long-press Alerts: escape hatch out of forwarder-only.
                if (i == 3) setOnLongClickListener {
                    if (forwarderOnlyPreferred()) {
                        android.app.AlertDialog.Builder(this@MainActivity,
                            android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Restore full Phone Link UI?")
                            .setMessage("Turns off forwarder-only so Phone, Messages, and Status show again.")
                            .setPositiveButton("Restore") { _, _ ->
                                val p = getSharedPreferences("auxlink", MODE_PRIVATE)
                                val svc = NowPlayingService.instance
                                if (svc != null) svc.setForwarderOnly(false)
                                else p.edit().putBoolean("notif_forwarder_only", false).apply()
                                applyRoleChrome()
                                say("Full Phone Link UI restored.")
                            }
                            .setNegativeButton("Cancel", null).show()
                        true
                    } else false
                }
                tabBar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Android 15+ draws apps targeting SDK 35 edge to edge: without this the
        // status bar sits over the header and the navigation buttons over the
        // tabs. The tab bar's background runs under the navigation bar (its
        // buttons get padding instead); with the keyboard up, the whole screen
        // lifts above it (adjustResize no longer does that edge to edge). On
        // older Android the window already fits the bars and these are 0.
        val tabPadBottom = tabBar.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val l: Int; val t: Int; val r: Int; val b: Int; val ime: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = bars.left; t = bars.top; r = bars.right; b = bars.bottom
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                val w = intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                l = w[0]; t = w[1]; r = w[2]; b = w[3]
                ime = 0
            }
            v.setPadding(l, t, r, maxOf(0, ime - b))
            tabBar.setPadding(tabBar.paddingLeft, tabBar.paddingTop, tabBar.paddingRight, tabPadBottom + b)
            insets
        }
        setContentView(root)
        applyRoleChrome()
        maybeAskDeviceRole()
        // Back where you left off; the status screen until setup is done.
        // Forwarder-only phones land on Alerts.
        val saved = try { getPreferences(MODE_PRIVATE).getInt("tab", 2) } catch (e: Exception) { 2 }
        val start = when {
            !hasAccess() -> 2
            forwarderOnlyPreferred() -> 3
            else -> saved
        }
        showTab(start)
        openChatFrom(intent)
        openAlertsFrom(intent)
        openNotifFrom(intent)

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
        openAlertsFrom(intent)
        openNotifFrom(intent)
    }

    /** Fallback: open Alerts tab (shade body taps open the alert itself now). */
    private fun openAlertsFrom(i: Intent?) {
        if (i?.getBooleanExtra("open_alerts", false) != true) return
        i.removeExtra("open_alerts")
        showTab(3)
    }

    /** Open Alerts and the detail dialog for one notification (overlay fallback). */
    private fun openNotifFrom(i: Intent?) {
        val nid = i?.getStringExtra(EXTRA_OPEN_NOTIF_NID).orEmpty()
        if (nid.isEmpty()) return
        i?.removeExtra(EXTRA_OPEN_NOTIF_NID)
        showTab(3)
        alerts.openDetail(nid)
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
        // Forwarder-only: Phone / Messages / Status are hidden — stay on Alerts.
        val target = if (forwarderOnlyPreferred() && i != 3) 3 else i
        tab = target
        pages.forEachIndexed { k, v -> v.visibility = if (k == target) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { k, t -> t.setTextColor(if (k == target) accent else text2) }
        try { getPreferences(MODE_PRIVATE).edit().putInt("tab", target).apply() } catch (e: Exception) {}
        when (target) {
            0 -> phone.onShow()
            1 -> msgs.onShow()
            3 -> alerts.onShow()
        }
        if (target != 0) phone.onHide()
    }

    private fun forwarderOnlyPreferred() =
        try { getSharedPreferences("auxlink", MODE_PRIVATE).getBoolean("notif_forwarder_only", false) }
        catch (_: Exception) { false }

    /** Saved role: phone / headunit / auto. Auto infers from forwarder + USB. */
    private fun deviceRole(): String {
        val p = try { getSharedPreferences("auxlink", MODE_PRIVATE) } catch (_: Exception) { return "auto" }
        val saved = p.getString("device_role", "auto") ?: "auto"
        if (saved == "phone" || saved == "headunit") return saved
        if (p.getBoolean("notif_forwarder_only", false) || p.getBoolean("notif_forward", false))
            return "phone"
        if (UsbLink(this).findDevice() != null) return "headunit"
        return "auto"
    }

    private fun isPhoneRole() = deviceRole() == "phone"
    private fun isHeadUnitRole() = deviceRole() == "headunit"

    /** First run: ask whether this device is the phone or the head unit. */
    private fun maybeAskDeviceRole() {
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        if (p.contains("device_role")) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("What is this device?")
            .setMessage("Phone Link runs on your phone (forward notifications) and on the " +
                "car head unit / music tablet (receive them and drive the Pi). " +
                "You can change this later under Tools.")
            .setPositiveButton("This is my phone") { _, _ ->
                p.edit().putString("device_role", "phone").apply()
                applyRoleChrome()
            }
            .setNeutralButton("Head unit / tablet") { _, _ ->
                p.edit().putString("device_role", "headunit").apply()
                applyRoleChrome()
            }
            .setNegativeButton("Decide later") { _, _ ->
                p.edit().putString("device_role", "auto").apply()
            }
            .setCancelable(false)
            .show()
    }

    /** Hide Phone / Messages / Status when forwarder-only; role-aware Tools / Alerts. */
    private fun applyRoleChrome() {
        if (!::tabViews.isInitialized) return
        val fo = forwarderOnlyPreferred()
        // Indices: 0 Phone, 1 Messages, 2 Status, 3 Alerts
        for (i in 0..2) {
            tabViews[i].visibility = if (fo) View.GONE else View.VISIBLE
        }
        tabViews[3].visibility = View.VISIBLE
        if (fo && tab != 3) showTab(3)
        val phone = isPhoneRole()
        val head = isHeadUnitRole()
        toolsMusicRows.forEach { it.visibility = if (phone) View.GONE else View.VISIBLE }
        // Rebuild Alerts settings visibility via tag on the forwarder block.
        alerts.setForwarderVisible(!head)
        roleBlock?.let { updateRoleBlock(it) }
        refreshAlertsBadge()
    }

    private fun refreshAlertsBadge() {
        if (!::tabViews.isInitialized) return
        val n = NotifLog.unreadCount()
        tabViews[3].text = if (n > 0) "🔔\nAlerts ($n)" else "🔔\nAlerts"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 1 && msgs.back()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        phone.close(); msgs.destroy(); alerts.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume(); main.post(refresh)
        NowPlayingService.appForeground = true
        if (::pages.isInitialized) showTab(tab)
        NowPlayingService.instance?.setAppForeground(true)
        // Daily auto-check (if the switch is on). Waits ~8 s for the Pi's
        // version to arrive first; stays silent unless there's something new.
        if (getSharedPreferences("auxlink", MODE_PRIVATE).getBoolean("pi_auto_update", false))
            main.postDelayed({ checkPiUpdate(auto = true) }, 8000)
        maybeWarnOverlayLost()
    }

    /** If overlay was on last time and is off now, offer to turn it back on. */
    private fun maybeWarnOverlayLost() {
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        val on = Settings.canDrawOverlays(this)
        val was = p.getBoolean("overlay_was_on", false)
        if (on) {
            p.edit().putBoolean("overlay_was_on", true).apply()
            return
        }
        if (!was) return
        val now = System.currentTimeMillis()
        if (now - p.getLong("overlay_warn_at", 0L) < 6 * 60 * 60 * 1000L) return
        p.edit().putLong("overlay_warn_at", now).apply()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Display over other apps is off")
            .setMessage("Phone Link had this on before, but Android turned it off. " +
                "Call and text pop-ups need it. On the SMO, also set battery use to Unrestricted.")
            .setPositiveButton("Turn on") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
    override fun onPause() {
        super.onPause(); main.removeCallbacks(refresh)
        NowPlayingService.appForeground = false
        NowPlayingService.instance?.setAppForeground(false)
    }

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
                       else "Asking the Pi…"
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

        // Music source (SMO / phone), when a phone is set up.
        if (st.optBoolean("has_phone")) {
            piRows.addView(sourceSelector(st.optString("source_override", "both")))
            if (st.optString("source_override", "both") == "auto") {
                val act = st.optString("source_active", "smo")
                piRows.addView(piRow("Playing from", if (act == "phone") "the phone" else "the media player",
                    if (act == "phone") accent else good))
            }
        }

        // Wi-Fi + Pi controls. One button forces the setup hotspot on no matter
        // what (disconnecting any current network); it switches off by itself
        // after 15 min. Power saving (Wi-Fi off when the car disconnects) is a
        // Pi setting on the setup page.
        val setupOn = st.optString("wifi") == "setup"
        piRows.addView(piButtonRow("Setup Wi-Fi", "Turn on", !setupOn) {
            send("wifi-setup", "Disconnecting from any current network and turning the setup Wi-Fi on — " +
                "join \"AuxLink-setup\" to open the settings page.")
        })
        piRows.addView(piButtonRow("Settings page", "Open", false) { openSetup() })
        piRows.addView(piButtonRow("Restart the Pi", "Restart", false) {
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
    }

    /** The Auto / SMO / Phone source picker for the Pi card. */
    private fun sourceSelector(current: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, px(10), 0, 0)
        }
        box.addView(TextView(this).apply { text = "Music source"; setTextColor(text2); textSize = 14f })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0)
        }
        listOf("Media" to "media", "Phone" to "phone", "Both" to "both", "Auto" to "auto").forEachIndexed { i, (label, key) ->
            val b = pillButton(label, current == key) { send("source-$key", "Music source set to $label.") }
            b.minWidth = 0; b.minimumWidth = 0
            b.layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply { if (i > 0) marginStart = px(8) }
            row.addView(b)
        }
        box.addView(row)
        return box
    }

    /** A Pi-card row: a label on the left and an action pill on the right. */
    private fun piButtonRow(label: String, buttonText: String, primary: Boolean, click: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = label; setTextColor(text2); textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillButton(buttonText, primary, click))
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
                       else "Asking the Pi…"
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
        val note = NowPlayingService.lastRecoveryNote
        val noteAt = NowPlayingService.lastRecoveryAt
        val key = (if (linked) "1" else "0") + (f?.toString() ?: "") + note + noteAt
        if (key != flowShown) {
            flowShown = key
            flowRows.removeAllViews()
            if (note.isNotEmpty() && noteAt > recoveryShownAt &&
                System.currentTimeMillis() - noteAt < 120_000L) {
                recoveryShownAt = noteAt
                flowRows.addView(TextView(this).apply {
                    text = "Auto-recovery: $note"
                    setTextColor(warn); textSize = 13f
                    setPadding(0, 0, 0, px(8))
                })
            }
            if (!linked || f == null) {
                flowRows.addView(TextView(this).apply {
                    text = if (!linked) "Connect to the Pi to see the audio path."
                           else "Asking the Pi…"
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
        // Check summary card + collapsible raw log (same naming as the web page).
        val r = NowPlayingService.checkResult
        if (r != null && r !== checkShown) {
            checkShown = r
            checking = false
            checkRows.removeAllViews()
            val out = r.optString("output")
            val lines = out.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            var okN = 0; var warnN = 0; var failN = 0
            var firstFail = ""
            for (raw in lines) {
                val t = raw.trim()
                when {
                    t.startsWith("OK") -> okN++
                    t.startsWith("WARN") -> { warnN++; if (firstFail.isEmpty()) firstFail = t.removePrefix("WARN").trim() }
                    t.startsWith("FAIL") -> { failN++; if (firstFail.isEmpty()) firstFail = t.removePrefix("FAIL").trim() }
                }
            }
            val reboot = out.contains("Reboot the Pi", ignoreCase = true)
            val title = if (r.optBoolean("fix")) "Check and fix" else "Full check"
            val (overall, colour, next) = when {
                failN > 0 || reboot -> Triple(
                    "Needs attention", bad,
                    if (reboot) "Next: reboot the Pi (Status → Restart the Pi)."
                    else "Next: tap Check and fix, or Re-plug USB-C under Tools.")
                warnN > 0 -> Triple("Check recommended", warn,
                    "Next: review the warnings below, or tap Check and fix.")
                else -> Triple("All clear", good, "Pi side looks healthy.")
            }
            checkRows.addView(TextView(this).apply {
                text = "$title  ·  $overall"
                setTextColor(colour); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, px(4), 0, px(2))
            })
            checkRows.addView(TextView(this).apply {
                text = buildString {
                    append("$okN OK")
                    if (warnN > 0) append("  ·  $warnN warn")
                    if (failN > 0) append("  ·  $failN fail")
                    if (firstFail.isNotEmpty()) append("\nFirst issue: $firstFail")
                    append("\n$next")
                }
                setTextColor(text2); textSize = 13f; setPadding(0, 0, 0, px(6))
            })
            val detailBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            for (raw in lines) {
                val t = raw.trim()
                val (mark, c, rest) = when {
                    t.startsWith("OK") -> Triple("✓", good, t.removePrefix("OK").trim())
                    t.startsWith("WARN") -> Triple("!", warn, t.removePrefix("WARN").trim())
                    t.startsWith("FAIL") -> Triple("✕", bad, t.removePrefix("FAIL").trim())
                    Regex("^\\d+\\.").containsMatchIn(t) -> Triple("", text1, t)
                    else -> Triple("", text2, t)
                }
                detailBody.addView(TextView(this).apply {
                    text = if (mark.isEmpty()) rest else "$mark  $rest"
                    setTextColor(c); textSize = if (mark.isEmpty() && c == text1) 14f else 13f
                    if (mark.isEmpty() && c == text1) { typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(6), 0, 0) }
                    else setPadding(px(12), px(1), 0, px(1))
                })
            }
            val detailHead = TextView(this).apply {
                text = "Details  ▾"; setTextColor(text2); textSize = 13f
                setPadding(0, px(4), 0, px(4))
                setOnClickListener {
                    val open = detailBody.visibility != View.VISIBLE
                    detailBody.visibility = if (open) View.VISIBLE else View.GONE
                    text = "Details  " + if (open) "▴" else "▾"
                }
            }
            checkRows.addView(detailHead)
            checkRows.addView(detailBody)
        }
        refreshAlertsBadge()
    }

    private fun setupCard(): View {
        val c = card()
        setupHead = TextView(this).apply { setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        c.addView(setupHead)
        setupToggle = TextView(this).apply {
            text = "Phone Link needs a few permissions. Each one says why."
            setTextColor(text2); textSize = 14f; setPadding(0, px(2), 0, px(4))
            setOnClickListener {
                setupCollapsed = !setupCollapsed
                applySetupCollapse()
            }
        }
        c.addView(setupToggle)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupBody = body
        c.addView(body)
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
            "Shows calls (with Answer and Hang up) and texts on top of your music app. " +
                "On some SMOs this resets after a reboot if Phone Link was force-stopped — " +
                "also set battery use to Unrestricted. Phone Link will notify you if it turns off.",
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
        // ADB tip lives in a dialog only (not the main checklist body).
        steps += Step("No high-volume warning",
            "Optional: keep Android's hearing warning off so full volume always works (one-time adb grant).",
            "How", true, { true },
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
        steps += Step("Vehicle location",
            "Optional: stops the Pi dropping the phone while the car is still moving. The head unit " +
                "shares the car's GPS as the device location; Phone Link reads it only to tell the Pi " +
                "moving or stationary (needs the Pi's \"disconnect when the car turns off\" setting on).",
            "Allow", true, { true }, { LocationWatch.hasPermission(this) }) {
            askOrSettings(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        for (s in steps) body.addView(stepRow(s))
        return c
    }

    private fun applySetupCollapse() {
        val body = setupBody ?: return
        val left = steps.count { it.shown() && !it.done() && !it.optional }
        val allDone = left == 0 && hasAccess()
        if (allDone && setupCollapsed) {
            body.visibility = View.GONE
            setupToggle?.text = "All permissions granted  ·  tap to expand"
        } else {
            body.visibility = View.VISIBLE
            setupToggle?.text = if (allDone) "Tap to collapse completed checklist"
            else "Phone Link needs a few permissions. Each one says why."
        }
    }

    /** "Nudge Wi-Fi when the car connects": a single switch. When the car's
     *  Bluetooth connects and this device isn't on any Wi-Fi, the service turns
     *  Wi-Fi on and re-scans so the system rejoins a known network. */
    private fun wifiParkedBlock(): View {
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(10), 0, 0) }
        box.addView(TextView(this).apply {
            text = "Wi-Fi when the car connects"; setTextColor(text1); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "A fallback only: 15 s after the car's Bluetooth connects, if Wi-Fi still isn't connected, " +
                "turn Wi-Fi on and scan again so it rejoins a known network (some head units sit with Wi-Fi " +
                "off, and Spotify needs it). It doesn't pick a specific network and doesn't keep retrying."
            setTextColor(text2); textSize = 13f; setPadding(0, px(2), 0, px(6))
        })
        val sw = android.widget.Switch(this).apply {
            text = "  Nudge Wi-Fi when the car connects"; setTextColor(text1); textSize = 14f
            isChecked = p.getBoolean("wifi_keep_on", p.getBoolean("wifi_on_car_off", false))
        }
        sw.setOnCheckedChangeListener { _, _ ->
            p.edit().putBoolean("wifi_keep_on", sw.isChecked).remove("wifi_on_car_off").apply()
        }
        box.addView(sw)
        return box
    }

    // ----------------------------------------------------- Pi software update
    private val piRepo = "chris-hemmings/auxlink"
    private val piBranch = "main"
    @Volatile private var piUpdateBusy = false

    /** "Keep the Pi up to date": a switch + a "Check now" button. The switch
     *  checks on its own (once a day); either way nothing downloads or installs
     *  until you confirm. */
    private fun piUpdateBlock(): View {
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(10), 0, 0) }
        box.addView(TextView(this).apply {
            text = "Pi software updates"; setTextColor(text1); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "Fetches the latest Pi software from GitHub and installs it on the Pi over the USB-C (or " +
                "Bluetooth) link — no Wi-Fi needed, so it works in the car. It always asks before downloading."
            setTextColor(text2); textSize = 13f; setPadding(0, px(2), 0, px(6))
        })
        val sw = android.widget.Switch(this).apply {
            text = "  Check for Pi updates automatically"; setTextColor(text1); textSize = 14f
            isChecked = p.getBoolean("pi_auto_update", false)
        }
        sw.setOnCheckedChangeListener { _, on ->
            p.edit().putBoolean("pi_auto_update", on).apply()
            if (on) checkPiUpdate(auto = false)
        }
        box.addView(sw)
        box.addView(pillButton("Check now", false) { checkPiUpdate(auto = false) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = 0
            (layoutParams as LinearLayout.LayoutParams).topMargin = px(8)
        })
        return box
    }

    /** Progress sheet for Pi updates (download → send → install). */
    private fun showUpdateProgress(title: String, msg: String, pct: Int?) {
        if (updateDlg == null) {
            val pad = px(20)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, px(12), pad, px(8))
            }
            updateTitleTv = TextView(this).apply {
                setTextColor(text1); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            }
            updateMsgTv = TextView(this).apply {
                setTextColor(text2); textSize = 14f; setPadding(0, px(8), 0, px(12))
            }
            updateBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            col.addView(updateTitleTv)
            col.addView(updateMsgTv)
            col.addView(updateBar)
            updateDlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(col)
                .setCancelable(false)
                .create()
        }
        updateTitleTv?.text = title
        updateMsgTv?.text = msg
        val bar = updateBar
        if (bar != null) {
            if (pct == null) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                bar.progress = pct.coerceIn(0, 100)
            }
        }
        if (updateDlg?.isShowing != true) updateDlg?.show()
    }

    private fun finishUpdateProgress(msg: String, ok: Boolean) {
        piUpdateBusy = false
        updateDlg?.dismiss()
        updateDlg = null
        updateTitleTv = null; updateMsgTv = null; updateBar = null
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (ok) "Update sent" else "Update failed")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    /** "1.0.89" > "1.0.9"? Numeric, component by component. */
    private fun verNewer(a: String, b: String): Boolean {
        val pa = a.split("."); val pb = b.split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    private data class PiRelease(val version: String, val zipUrl: String)

    /**
     * Newest published Pi image release (tag pi-v*). The image workflow only
     * writes VERSION inside the release zip — main's pi/VERSION can lag, so
     * we must not trust raw.githubusercontent for "what's latest".
     */
    private fun fetchLatestPiRelease(): PiRelease? {
        val c = URL("https://api.github.com/repos/$piRepo/releases?per_page=40")
            .openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AuxLink-PhoneLink")
        c.connectTimeout = 10000; c.readTimeout = 15000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        var best: PiRelease? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
            val tag = o.optString("tag_name")
            if (!tag.startsWith("pi-v")) continue
            val ver = tag.removePrefix("pi-v")
            if (!ver.matches(Regex("[0-9.]{1,12}"))) continue
            val assets = o.optJSONArray("assets") ?: continue
            var zipUrl = ""
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val name = a.optString("name")
                if (name == "auxlink-pi-update-$ver.zip") {
                    zipUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (zipUrl.isEmpty()) continue
            if (best == null || verNewer(ver, best.version)) best = PiRelease(ver, zipUrl)
        }
        return best
    }

    /** Fallback when the Releases API is unreachable: main's pi/VERSION. */
    private fun fetchPiVersionFromBranch(): String? {
        val c = URL("https://raw.githubusercontent.com/$piRepo/$piBranch/pi/VERSION")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        val v = c.inputStream.bufferedReader().use { it.readText() }.trim()
        return v.takeIf { it.matches(Regex("[0-9.]{1,12}")) }
    }

    private fun downloadUrl(url: String, latest: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "AuxLink-PhoneLink")
        c.connectTimeout = 15000; c.readTimeout = 120000
        val len = c.contentLength
        val inp = c.inputStream
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var read = 0
        while (true) {
            val n = inp.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            read += n
            if (len > 0) {
                val pct = ((read.toLong() * 100) / len).toInt().coerceIn(0, 99)
                main.post {
                    showUpdateProgress("Downloading v$latest",
                        "${read / 1024} KB of ${len / 1024} KB", pct)
                }
            } else if (read % (64 * 1024) < chunk.size) {
                main.post {
                    showUpdateProgress("Downloading v$latest",
                        "${read / 1024} KB…", null)
                }
            }
        }
        inp.close()
        return buf.toByteArray()
    }

    /** Check GitHub for a newer Pi version and, if there is one, offer to
     *  install it. [auto] = triggered by the daily auto-check (stays quiet
     *  when there's nothing new or a check already ran today). */
    private fun checkPiUpdate(auto: Boolean) {
        if (piUpdateBusy) return
        val have = NowPlayingService.piStatus?.optString("version").orEmpty()
        if (have.isEmpty()) {
            if (!auto) say("The Pi isn't connected yet, so its version is unknown. " +
                "Plug this device into the Pi (or connect the Bluetooth source) and try again.")
            return
        }
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (auto && now - p.getLong("pi_update_checked", 0L) < 24 * 60 * 60 * 1000L) return
        piUpdateBusy = true
        if (!auto) showUpdateProgress("Checking for updates", "Looking on GitHub…", null)
        Thread {
            val rel = try { fetchLatestPiRelease() } catch (_: Exception) { null }
            val latest = rel?.version ?: try { fetchPiVersionFromBranch() } catch (_: Exception) { null }
            val zipUrl = rel?.zipUrl
            main.post {
                p.edit().putLong("pi_update_checked", now).apply()
                if (latest == null) {
                    piUpdateBusy = false
                    if (!auto) finishUpdateProgress(
                        "Couldn't reach GitHub to check for updates. Is this device online?", false)
                    return@post
                }
                if (!verNewer(latest, have)) {
                    piUpdateBusy = false
                    if (!auto) {
                        updateDlg?.dismiss(); updateDlg = null
                        updateTitleTv = null; updateMsgTv = null; updateBar = null
                        say("The Pi is up to date (v$have). Latest on GitHub is v$latest.")
                    }
                    return@post
                }
                piUpdateBusy = false
                updateDlg?.dismiss(); updateDlg = null
                updateTitleTv = null; updateMsgTv = null; updateBar = null
                // Don't nag on auto-checks about a version already declined.
                if (auto && p.getString("pi_update_skip", "") == latest) return@post
                confirmPiUpdate(have, latest, auto, zipUrl)
            }
        }.start()
    }

    private fun confirmPiUpdate(have: String, latest: String, auto: Boolean, zipUrl: String?) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update the Pi to v$latest?")
            .setMessage("The Pi is on v$have. This downloads the latest Pi software from GitHub " +
                "(under 1 MB) and installs it on the Pi over the USB-C or Bluetooth link. The Pi restarts " +
                "its services and reconnects by itself.")
            .setPositiveButton("Download & install") { _, _ -> downloadAndPushPiUpdate(latest, zipUrl) }
            .setNegativeButton("Not now") { _, _ ->
                if (auto) getSharedPreferences("auxlink", MODE_PRIVATE)
                    .edit().putString("pi_update_skip", latest).apply()
            }
            .show()
    }

    private fun downloadAndPushPiUpdate(latest: String, zipUrl: String?) {
        if (piUpdateBusy) return
        piUpdateBusy = true
        showUpdateProgress("Downloading v$latest", "Fetching from GitHub…", 0)
        Thread {
            val url = zipUrl?.takeIf { it.isNotEmpty() }
                ?: "https://github.com/$piRepo/releases/download/pi-v$latest/auxlink-pi-update-$latest.zip"
            var zip = try { downloadUrl(url, latest) } catch (_: Exception) { null }
            // Older path: whole-repo zip from main (still accepted by the Pi).
            if (zip == null || zip.size < 1000) {
                zip = try {
                    downloadUrl("https://codeload.github.com/$piRepo/zip/refs/heads/$piBranch", latest)
                } catch (_: Exception) { null }
            }
            if (zip == null || zip.size < 1000) {
                main.post { finishUpdateProgress("Download failed — is this device online?", false) }
                return@Thread
            }
            main.post {
                showUpdateProgress("Downloading v$latest", "Download complete (${zip.size / 1024} KB)", 100)
                val svc = NowPlayingService.instance
                val piVer = NowPlayingService.piStatus?.optString("version").orEmpty()
                val linkOk = svc != null && piVer.isNotEmpty() && !verNewer("1.0.90", piVer)
                if (linkOk && svc != null) {
                    showUpdateProgress("Sending to the Pi", "Pushing v$latest over the link…", 0)
                    svc.pushPiUpdate(zip, latest, { label, pct ->
                        showUpdateProgress("Sending to the Pi", label, pct)
                    }) { ok, msg ->
                        if (msg == "nolink") installPiUpdateOverWifi(zip, latest)
                        else if (ok) finishUpdateProgress(
                            "Update sent. The Pi is installing v$latest and will restart its " +
                                "services (about a minute), then reconnect.", true)
                        else finishUpdateProgress("Couldn't install: $msg", false)
                    }
                } else installPiUpdateOverWifi(zip, latest)
            }
        }.start()
    }

    /** Fallback: push the zip to the Pi's /api/update over Wi-Fi (needs to be
     *  on the Pi's network, like the setup page). */
    private fun installPiUpdateOverWifi(zip: ByteArray, latest: String) {
        showUpdateProgress("Looking for the Pi", "No link — trying Wi-Fi…", null)
        Thread {
            val base = resolvePiBase()
            if (base == null) {
                main.post {
                    finishUpdateProgress(
                        "Pi not found. Plug this device into the Pi's USB-C, or get on the Pi's Wi-Fi " +
                            "(its Setup Wi-Fi, or the same network), then try again.", false)
                }
                return@Thread
            }
            main.post {
                showUpdateProgress("Installing on the Pi",
                    "Uploading over Wi-Fi (${zip.size / 1024} KB)…", null)
            }
            val ok = try {
                val c = URL("${base}api/update").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.setRequestProperty("Content-Type", "application/zip")
                c.setFixedLengthStreamingMode(zip.size)
                c.outputStream.use { out ->
                    var off = 0
                    val chunk = 16 * 1024
                    while (off < zip.size) {
                        val n = minOf(chunk, zip.size - off)
                        out.write(zip, off, n)
                        off += n
                        val pct = (off * 100) / zip.size
                        main.post {
                            showUpdateProgress("Installing on the Pi",
                                "Uploading… $pct%", pct)
                        }
                    }
                }
                main.post {
                    showUpdateProgress("Installing on the Pi",
                        "Pi is installing v$latest…", null)
                }
                val code = c.responseCode
                val body = try {
                    (if (code in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (e: Exception) { "" }
                Triple(code in 200..299, code, body)
            } catch (e: Exception) { Triple(false, 0, e.message ?: "error") }
            main.post {
                when {
                    ok.first -> finishUpdateProgress(
                        "Update sent over Wi-Fi. The Pi is installing v$latest and will " +
                            "restart its services (about a minute), then reconnect.", true)
                    ok.second == 401 -> finishUpdateProgress(
                        "The Pi's setup page is password-protected, so Wi-Fi install isn't available — " +
                            "plug into the Pi's USB-C to install over the link instead.", false)
                    else -> finishUpdateProgress(
                        "The Pi rejected the update: ${ok.third.take(200)}", false)
                }
            }
        }.start()
    }

    private fun toolsCard(): View {
        val c = card()
        c.addView(TextView(this).apply {
            text = "Tools"; setTextColor(text1); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        val role = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, px(4)) }
        roleBlock = role
        updateRoleBlock(role)
        c.addView(role)
        toolsMusicRows.clear()
        fun musicTool(v: View): View { toolsMusicRows += v; return v }
        c.addView(musicTool(toolRow("Fix sound",
            "Music playing here but silent in the car? This restarts the stream to the car.", "Fix") {
            send("fix", "Asked the Pi to restart the music stream to the car.")
        }))
        c.addView(musicTool(toolRow("Re-plug USB-C",
            "Force the Pi to re-plug its USB-C gadget (fixes Wired selected but silent). " +
                "Music drops for a few seconds.", "Re-plug") {
            send("usbc-replug", "Asked the Pi to re-plug USB-C. Wait a few seconds for sound to return.")
        }))
        c.addView(musicTool(toolRow("Test the call pop-up",
            "Shows a pretend call for 8 seconds, so you can see where it appears. Switch to your music app to see it on top.",
            "Test") {
            val svc = NowPlayingService.instance
            if (svc == null) say("Phone Link isn't running yet: allow Notification access first.")
            else { svc.testCallBar(); say("Showing a pretend call for 8 seconds.") }
        }))
        c.addView(musicTool(toolRow("Volume to full",
            "Sets this device's media volume to 100% (use the car's volume). If Android warns about " +
                "high volume, tap OK.", "Set") {
            val svc = NowPlayingService.instance
            if (svc == null) say("Phone Link isn't running yet: allow Notification access first.")
            else { svc.volumeMax(true); say("Volume set to full. If Android asks, tap OK.") }
        }))
        c.addView(musicTool(bannerPosBlock()))
        c.addView(wifiParkedBlock())
        c.addView(piUpdateBlock())
        return c
    }

    /** Where floating alert banners appear (remembered across reboot). */
    private fun bannerPosBlock(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, px(10), 0, 0)
        }
        val status = TextView(this).apply {
            text = "Currently: " + BannerPos.summary(this@MainActivity)
            setTextColor(text2); textSize = 13f; setPadding(0, px(2), 0, px(6))
        }
        box.addView(TextView(this).apply {
            text = "Alert banner position"; setTextColor(text1); textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "Drag any floating alert to move it — the spot is saved across reboot. " +
                "Or pick a vertical slot here."
            setTextColor(text2); textSize = 13f; setPadding(0, px(2), 0, px(6))
        })
        box.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Top" to "top", "Upper" to "upper", "Middle" to "middle", "Lower" to "lower")
            .forEachIndexed { i, (label, key) ->
                val b = pillButton(label, false) {
                    BannerPos.setPreset(this, key)
                    status.text = "Currently: " + BannerPos.summary(this)
                    say("Banner position set to $label")
                }
                b.minWidth = 0; b.minimumWidth = 0
                b.layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply {
                    if (i > 0) marginStart = px(6)
                }
                row.addView(b)
            }
        box.addView(row)
        box.addView(pillButton("Reset to default", false) {
            BannerPos.reset(this)
            status.text = "Currently: " + BannerPos.summary(this)
            say("Banner position reset")
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = 0
            (layoutParams as LinearLayout.LayoutParams).topMargin = px(8)
        })
        return box
    }

    private fun updateRoleBlock(box: LinearLayout) {
        box.removeAllViews()
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        val cur = p.getString("device_role", "auto") ?: "auto"
        box.addView(TextView(this).apply {
            text = "This device"; setTextColor(text1); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "Phone = forward notifications. Head unit = receive them and drive the Pi. " +
                "Auto guesses from USB / forwarder settings."
            setTextColor(text2); textSize = 12f; setPadding(0, px(2), 0, px(6))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Auto" to "auto", "Phone" to "phone", "Head unit" to "headunit").forEachIndexed { i, (label, key) ->
            val b = pillButton(label, cur == key) {
                p.edit().putString("device_role", key).apply()
                applyRoleChrome()
            }
            b.minWidth = 0; b.minimumWidth = 0
            b.layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply { if (i > 0) marginStart = px(8) }
            row.addView(b)
        }
        box.addView(row)
        if (isHeadUnitRole()) {
            box.addView(TextView(this).apply {
                text = "Head-unit mode: Alerts receives mirrored notifications (forwarder controls hidden)."
                setTextColor(text2); textSize = 12f; setPadding(0, px(8), 0, 0)
            })
        }
    }

    /** Confirm before enabling forwarder-only; turning off restores tabs. */
    private fun wireForwarderOnlySwitch(sw: android.widget.Switch) {
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = p.getBoolean("notif_forwarder_only", false)
        sw.setOnCheckedChangeListener { _, on ->
            if (on) {
                // Revert until the user confirms.
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = false
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Forwarder only?")
                    .setMessage("Hides Phone, Messages, and Status on this device. " +
                        "Long-press the Alerts tab anytime to restore the full UI.")
                    .setPositiveButton("Enable") { _, _ ->
                        val svc = NowPlayingService.instance
                        if (svc != null) svc.setForwarderOnly(true)
                        else p.edit().putBoolean("notif_forwarder_only", true).apply()
                        wireForwarderOnlySwitch(sw)
                        applyRoleChrome()
                        say("Forwarder-only on. Long-press Alerts to restore full UI.")
                    }
                    .setNegativeButton("Cancel") { _, _ -> wireForwarderOnlySwitch(sw) }
                    .setOnCancelListener { wireForwarderOnlySwitch(sw) }
                    .show()
            } else {
                val svc = NowPlayingService.instance
                if (svc != null) svc.setForwarderOnly(false)
                else p.edit().putBoolean("notif_forwarder_only", false).apply()
                applyRoleChrome()
                say("Full Phone Link UI restored.")
            }
        }
    }

    /** Forwarder controls for the Alerts tab (enable on your PHONE). */
    private fun notifForwardBlock(): View {
        val p = getSharedPreferences("auxlink", MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cardBg, 16f)
            setPadding(px(14), px(12), px(14), px(12))
            tag = "forwarder"
        }
        box.addView(TextView(this).apply {
            text = "Forward from this phone"; setTextColor(text1); textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "ON on your phone only. SMS/Messages is skipped automatically " +
                "(texts already go to the Tesla via the Pi). Leave this OFF on the head unit."
            setTextColor(text2); textSize = 12f; setPadding(0, px(2), 0, px(6))
        })
        box.addView(android.widget.Switch(this).apply {
            text = "  Forward notifications"; setTextColor(text1); textSize = 14f
            isChecked = p.getBoolean("notif_forward", false)
            setOnCheckedChangeListener { _, on ->
                val svc = NowPlayingService.instance
                if (svc != null) svc.setForwardNotifs(on)
                else p.edit().putBoolean("notif_forward", on).apply()
                say(if (on) "Forwarding on. Grant Notification access if prompted."
                    else "Forwarding off.")
                applyRoleChrome()
            }
        })
        val foSw = android.widget.Switch(this).apply {
            text = "  Forwarder only (hide Phone / Messages / Status)"; setTextColor(text1); textSize = 14f
            isChecked = p.getBoolean("notif_forwarder_only", false)
        }
        wireForwarderOnlySwitch(foSw)
        box.addView(foSw)
        fun pill(label: String, click: () -> Unit) = pillButton(label, false, click).apply {
            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply {
                marginStart = px(4); marginEnd = px(4)
            }
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(8), 0, 0)
            addView(pill("Test send") {
                val svc = NowPlayingService.instance
                if (svc == null) say("Grant Notification access first.")
                else {
                    say("Testing…")
                    svc.testNotifForward { ok ->
                        say(if (ok) "Test sent — check the head unit."
                            else "Couldn't reach the Pi.\n${svc.notifDiag()}")
                    }
                }
            })
            addView(pill("Link status") {
                NowPlayingService.instance?.let { say(it.notifDiag()) }
                    ?: say("Phone Link isn't running (grant Notification access).")
            })
        })
        box.addView(Button(this).apply {
            text = "Apps to forward"
            isAllCaps = false; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(text1)
            val shape = round(line, 20f)
            background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, shape)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(44)
            ).apply { topMargin = px(8) }
            setOnClickListener { appsToForwardDialog() }
        })
        return box
    }

    /**
     * Wear-style picker: only apps with the switch ON are mirrored.
     * System / unlisted packages never forward. Select all / none + Save stay
     * on screen.
     */
    private fun appsToForwardDialog() {
        say("Loading apps…")
        val prefs = getSharedPreferences("auxlink", MODE_PRIVATE)
        Thread {
            // Seed allowlist off the UI thread (works even if listener isn't up).
            val allowedSeed = NowPlayingService.readAllowedNotifPackages(this)
            val apps = NowPlayingService.appsForForwardPicker(this)
            main.post {
                if (apps.isEmpty()) {
                    say("Couldn't list apps on this phone.")
                    return@post
                }
                val allowed = (NowPlayingService.instance?.allowedNotifPackages()
                    ?: allowedSeed).toMutableSet()
                val switches = mutableListOf<android.widget.Switch>()
                val rows = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(4), 0, px(4), 0)
                }
                fun bindSwitch(sw: android.widget.Switch, pkg: String, on: Boolean) {
                    sw.setOnCheckedChangeListener(null)
                    sw.isChecked = on
                    sw.setOnCheckedChangeListener { _, checked ->
                        if (checked) allowed.add(pkg) else allowed.remove(pkg)
                    }
                }
                for (app in apps) {
                    val sw = android.widget.Switch(this)
                    bindSwitch(sw, app.pkg, app.pkg in allowed)
                    switches += sw
                    val icon = android.widget.ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(12) }
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        if (app.icon != null) setImageDrawable(app.icon)
                        else setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                    val label = TextView(this).apply {
                        text = app.label; setTextColor(text1); textSize = 15f
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }
                    rows.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(px(4), px(6), px(4), px(6))
                        addView(icon)
                        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(sw)
                    })
                }
                val scroll = ScrollView(this).apply { addView(rows) }
                val dlgH = (resources.displayMetrics.heightPixels * 0.88f).toInt()
                val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(16), px(12), px(16), px(12))
                    setBackgroundColor(cardBg)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dlgH)
                    addView(TextView(this@MainActivity).apply {
                        text = "Apps to forward"; setTextColor(text1); textSize = 18f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "Only apps turned on are mirrored. Android System and other " +
                            "framework alerts never come through. SMS/Messages is skipped automatically."
                        setTextColor(text2); textSize = 12f
                        setPadding(0, px(4), 0, px(8))
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(pillButton("Select all", false) {
                            allowed.clear(); allowed.addAll(apps.map { it.pkg })
                            apps.forEachIndexed { i, a -> bindSwitch(switches[i], a.pkg, true) }
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply { marginEnd = px(6) }
                        })
                        addView(pillButton("Select none", false) {
                            allowed.clear()
                            apps.forEachIndexed { i, a -> bindSwitch(switches[i], a.pkg, false) }
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f).apply { marginStart = px(6) }
                        })
                    })
                    addView(scroll, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                    ).apply { topMargin = px(8); bottomMargin = px(8) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        addView(pillButton("Cancel", false) { dlg.dismiss() }.apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, px(44)
                            ).apply { marginEnd = px(8) }
                        })
                        addView(pillButton("Save", true) {
                            val svc = NowPlayingService.instance
                            if (svc != null) svc.setAllowedNotifPackages(allowed)
                            else prefs.edit()
                                .putStringSet("notif_allowed_pkgs", allowed.toSet())
                                .putBoolean("notif_allowlist_ready", true)
                                .apply()
                            say(when (allowed.size) {
                                0 -> "No apps selected — nothing will be mirrored."
                                1 -> "Forwarding 1 app."
                                else -> "Forwarding ${allowed.size} apps."
                            })
                            dlg.dismiss()
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, px(44)
                            )
                        })
                    })
                }
                dlg.setContentView(panel)
                dlg.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.94f).toInt(),
                    dlgH
                )
                dlg.show()
            }
        }.start()
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
        // Collapse completed rows when everything required is done.
        for (s in steps) {
            if (left == 0 && setupCollapsed && s.done() && s.optional) {
                // optional leftovers stay hidden when collapsed
            }
        }
        applySetupCollapse()
        // Hide completed optional ADB tip once required setup is done and collapsed.
        if (left == 0 && setupCollapsed) {
            for (s in steps) {
                if (s.optional && s.done()) s.row.visibility = View.GONE
            }
        }

        // Details
        details.text = buildString {
            appendLine("Link: " + if (linked) NowPlayingService.linkKind else "none")
            appendLine("USB: " + (usb ?: "nothing plugged in"))
            appendLine("Service running: " + if (running) "yes" else "no")
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
    /** The Pi's web base URL ("http://host/"), or null if it can't be reached.
     *  Blocks on the network - call off the main thread. Setup Wi-Fi first
     *  (10.42.0.1), else the same network as this device (auxlink.local). */
    private fun resolvePiBase(): String? {
        fun answers(host: String) = try {
            Socket().use { it.connect(InetSocketAddress(host, 80), 1500) }; true
        } catch (e: Exception) { false }
        if (answers("10.42.0.1")) return "http://10.42.0.1/"
        val ip = try { InetAddress.getByName("auxlink.local").hostAddress } catch (e: Exception) { null }
        return if (ip != null && answers(ip)) "http://$ip/" else null
    }

    private fun openSetup() {
        say("Looking for the Pi…")
        Thread {
            val url = resolvePiBase()
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

    private fun say(msg: String) {
        Toast.makeText(this, msg, if (msg.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        VoiceToText.onPermissionResult(this, requestCode, permissions, grantResults)
        // Location just granted: (re)start the watcher so the service begins
        // reporting movement to the Pi (and re-arms its foreground service type).
        if (permissions.any { it == Manifest.permission.ACCESS_FINE_LOCATION ||
                it == Manifest.permission.ACCESS_COARSE_LOCATION } &&
            LocationWatch.hasPermission(this)) {
            NowPlayingService.instance?.startLocationWatch()
        }
    }

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
        // A rounded fill with a press ripple, so taps give feedback.
        val shape = round(if (primary) accent else line, 20f)
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape, shape)
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
