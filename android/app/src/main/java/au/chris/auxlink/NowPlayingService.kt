package au.chris.auxlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.hardware.usb.UsbManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * Notification access is what lets an app read other apps' media sessions.
 * This service watches the active one and sends its now-playing info to the
 * XIAO, and from there to the Pi and the car.
 *
 * Album art goes as its own line, {"art": base64 JPEG, "art_id": crc}, only
 * when it changes (and once a minute, in case the Pi restarted): a 200x200
 * JPEG, which is what cars display. At the serial link's speed one image
 * takes about 1.5 s, so all sending happens on a background thread.
 *
 * Where it goes: over USB (the XIAO, or the Pi's USB-C port) whenever one is
 * plugged in - exactly as before - and otherwise over Bluetooth to the Pi,
 * for the Bluetooth music source.
 */
class NowPlayingService : NotificationListenerService() {
    companion object {
        private const val TAG = "SmoNowPlaying"
        @Volatile var lastSent: String = "(nothing yet)"
        /** The latest track info, sent or not (for the app screen). */
        @Volatile var nowPlaying: String = ""
        /** The Pi's latest status (Wi-Fi, IP, version...), and when it came. */
        @Volatile var piStatus: JSONObject? = null
        @Volatile var piStatusAt = 0L
        /** Car/phone Bluetooth state, the live audio path, the last full check
         *  and the last Bluetooth action's result, from the Pi. */
        @Volatile var btStatus: JSONObject? = null
        @Volatile var flow: org.json.JSONArray? = null
        @Volatile var checkResult: JSONObject? = null
        @Volatile var btResult: JSONObject? = null
        @Volatile var linkOpen: Boolean = false
        @Volatile var linkKind: String = ""
        /** Last USB-C silent-audio recovery note (for Status). */
        @Volatile var lastRecoveryNote: String = ""
        @Volatile var lastRecoveryAt = 0L
        /** Last successful send to / line from the Pi (ms). */
        @Volatile var lastTraffic = 0L
        /** Connected = the link is open, or it carried something in the last
         *  15 s (a link that blips between sends still counts). */
        fun connected() = linkOpen || System.currentTimeMillis() - lastTraffic < 15000
        const val ACTION_ANSWER = "au.chris.auxlink.ANSWER"
        const val ACTION_DECLINE = "au.chris.auxlink.DECLINE"
        const val ACTION_SHADE_ACT = "au.chris.auxlink.SHADE_ACT"
        const val ACTION_SHADE_MUTE = "au.chris.auxlink.SHADE_MUTE"
        const val ACTION_SHADE_REPLY = "au.chris.auxlink.SHADE_REPLY"
        /** Shade body tap → open that alert with actions (not the Alerts list). */
        const val ACTION_SHADE_OPEN = "au.chris.auxlink.SHADE_OPEN"
        const val EXTRA_SHADE_ID = "shade_id"
        const val EXTRA_NID = "nid"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_APP = "app"
        const val EXTRA_ACTION_LABEL = "action_label"
        const val NOTIF_CALL = 2
        /** Shade notification ids for mirrored alerts (1000–1999). */
        private const val SHADE_ID_BASE = 1000
        private const val SHADE_ID_MAX = 1999
        /** Allowlist of packages to mirror (Apps to forward). */
        private const val KEY_ALLOWED_PKGS = "notif_allowed_pkgs"
        private const val KEY_ALLOWLIST_READY = "notif_allowlist_ready"
        /** The running service, for the app screen's buttons. */
        @Volatile var instance: NowPlayingService? = null
        /** True while the app is in the foreground (MainActivity resumed). */
        @Volatile var appForeground = false
        /** The Phone screen's inline call panel, when it's open: given the call
         *  state ("incoming"/"outgoing"/"active"/"idle"), who, and the start
         *  time. While it's set and the app is in front, the floating call bar
         *  and notification stay hidden - the call shows in the app instead. */
        @Volatile var callUi: ((String, String, Long) -> Unit)? = null

        data class ForwardApp(val pkg: String, val label: String, val icon: Drawable?)

        /**
         * Launchable apps on this phone (Wear-style list for “which apps to
         * forward”), before any notification has been seen. Safe to call off
         * the main thread.
         */
        fun forwardableApps(ctx: Context): List<ForwardApp> {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = try {
                if (Build.VERSION.SDK_INT >= 33)
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                else
                    @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
            } catch (_: Exception) { emptyList() }
            return found.mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == ctx.packageName) return@mapNotNull null
                val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                ForwardApp(pkg, label, icon)
            }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        }

        /**
         * Picker list: launchable apps, plus any packages we've already seen
         * posting a notification (so a rare non-launcher app can be enabled).
         * System services never appear → they stay off the allowlist.
         */
        fun appsForForwardPicker(ctx: Context): List<ForwardApp> {
            val launchable = forwardableApps(ctx)
            val have = launchable.map { it.pkg }.toHashSet()
            val extras = mutableListOf<ForwardApp>()
            val raw = try {
                ctx.getSharedPreferences("auxlink", MODE_PRIVATE)
                    .getStringSet("notif_seen_apps", emptySet()) ?: emptySet()
            } catch (_: Exception) { emptySet() }
            val pm = ctx.packageManager
            for (row in raw) {
                val i = row.indexOf('\t')
                if (i <= 0) continue
                val pkg = row.substring(0, i)
                if (pkg in have || pkg == ctx.packageName) continue
                // Non-launcher system packages stay out of the picker (and
                // off the allowlist) — those were the unmutable noise.
                val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
                if (ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                val label = row.substring(i + 1).ifBlank { pkg }
                val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                extras += ForwardApp(pkg, "$label  ·  seen", icon)
                have += pkg
            }
            return launchable + extras.sortedBy { it.label.lowercase() }
        }

        /**
         * One-time migrate mute-list → allowlist (launchable − muted). Safe
         * off the main thread. Call before reading [KEY_ALLOWED_PKGS] from prefs
         * when the listener may not be running.
         */
        fun ensureNotifAllowlist(ctx: Context) {
            try {
                val p = ctx.getSharedPreferences("auxlink", MODE_PRIVATE)
                if (p.getBoolean(KEY_ALLOWLIST_READY, false)) return
                val muted = try {
                    p.getStringSet("notif_muted_pkgs", emptySet())?.toSet() ?: emptySet()
                } catch (_: Exception) { emptySet() }
                val launchable = forwardableApps(ctx).map { it.pkg }.toSet()
                val seeded = launchable - muted
                p.edit()
                    .putStringSet(KEY_ALLOWED_PKGS, seeded)
                    .putBoolean(KEY_ALLOWLIST_READY, true)
                    .apply()
                Log.i(TAG, "notif allowlist seeded: ${seeded.size} apps (was mute-list)")
            } catch (e: Exception) {
                Log.w(TAG, "notif allowlist seed: ${e.message}")
            }
        }

        /** Packages currently on the allowlist (seeds once if needed). */
        fun readAllowedNotifPackages(ctx: Context): Set<String> {
            ensureNotifAllowlist(ctx)
            return try {
                ctx.getSharedPreferences("auxlink", MODE_PRIVATE)
                    .getStringSet(KEY_ALLOWED_PKGS, emptySet())?.toSet() ?: emptySet()
            } catch (_: Exception) { emptySet() }
        }
    }

    /** Send a command to the Pi ({"cmd": name}) over the open link, right
     *  away (not waiting out a Bluetooth retry). [done] runs on the main thread. */
    fun command(name: String, done: (Boolean) -> Unit) {
        io.execute {
            bt.retryNow()
            val ok = sendLine(JSONObject().put("cmd", name).toString())
            main.post { done(ok) }
        }
    }

    /** A request for the Pi from the Phone / Messages screens (any JSON). */
    fun request(obj: JSONObject, done: (Boolean) -> Unit = {}) {
        io.execute {
            val ok = sendLine(obj.toString())
            main.post { done(ok) }
        }
    }

    @Volatile private var fwCb: ((JSONObject) -> Unit)? = null

    /** Push a Pi update zip to the Pi over the open USB/Bluetooth link, in
     *  base64 chunks. [progress] gets (label, percent 0–100 or null = indeterminate).
     *  [done] gets (ok, message); false with "nolink" means there's no link to
     *  the Pi (fall back to Wi-Fi). Callbacks run on the main thread. */
    fun pushPiUpdate(zip: ByteArray, ver: String,
                     progress: (String, Int?) -> Unit, done: (Boolean, String) -> Unit) {
        io.execute {
            val b64 = android.util.Base64.encodeToString(zip, android.util.Base64.NO_WRAP)
            val chunk = 24000            // base64 chars per line (well under the 64 KB cap)
            val total = (b64.length + chunk - 1) / chunk
            val begin = JSONObject().put("fw", JSONObject()
                .put("op", "begin").put("n", total).put("size", zip.size).put("ver", ver))
            if (!sendLine(begin.toString())) { main.post { done(false, "nolink") }; return@execute }
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = arrayOfNulls<JSONObject>(1)
            fwCb = { r -> result[0] = r; latch.countDown() }
            var ok = true
            var i = 0
            while (i < total) {
                val part = b64.substring(i * chunk, minOf((i + 1) * chunk, b64.length))
                if (!sendLine(JSONObject().put("fw",
                        JSONObject().put("op", "data").put("i", i).put("b", part)).toString())) {
                    ok = false; break
                }
                i++
                if (i % 2 == 0 || i == total) {
                    val n = i
                    val pct = if (total > 0) (n * 100) / total else 100
                    main.post { progress("Sending to the Pi… $pct%", pct) }
                }
            }
            if (ok) sendLine(JSONObject().put("fw", JSONObject().put("op", "end")).toString())
            if (!ok) { fwCb = null; main.post { done(false, "The link to the Pi dropped while sending.") }; return@execute }
            main.post { progress("Installing on the Pi…", null) }
            val got = try {
                latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) { false }
            fwCb = null
            val r = result[0]
            main.post {
                when {
                    !got -> done(false, "No reply from the Pi (it may still be installing — check its " +
                        "version in a minute).")
                    r?.optBoolean("ok") == true -> done(true, r.optString("msg", "installing"))
                    else -> done(false, r?.optString("msg").orEmpty().ifEmpty { "the Pi rejected the update" })
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var link: UsbLink
    private lateinit var bt: BtLink
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastLine = ""
    // Sends run here, in order, off the main thread (an image takes ~1.5 s).
    private val io = Executors.newSingleThreadExecutor()
    // The notification forwarder gets its own thread: on the phone the music
    // link (io) sits blocked on failed connects, which would starve it.
    private val notifIo = Executors.newSingleThreadExecutor()
    private var artKey = ""          // what the cached image was made from
    private var artLine = ""         // {"art":...} line for the current artwork
    private var artSentId = ""
    private var artSentAt = 0L

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() = pickController()
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { pickController() }

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> io.execute { link.close() }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // Composite gadget (UAC + serial): let the USB audio HAL
                    // claim the sound card before we open CDC serial.
                    io.execute {
                        link.deferOpen(2500)
                        lastLine = ""; artSentId = ""
                    }
                    main.postDelayed({
                        lastLine = ""; artSentId = ""; push()
                    }, 2600)
                }
                else -> { lastLine = ""; artSentId = ""; push() } // permission granted: resend
            }
        }
    }

    /** Phone↔Pi ACL drop: tear down notif RFCOMM so we don't keep a zombie. */
    private val btAclEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED &&
                i.action != android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED) return
            val dev = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                    android.bluetooth.BluetoothDevice::class.java)
            else @Suppress("DEPRECATION")
                i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            val addr = dev?.address ?: return
            if (i.action == android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                notifLink?.dropIfPeer(addr)
                Log.i(TAG, "ACL disconnected $addr — dropped notif RFCOMM if it matched")
            } else if (forwardNotifs) {
                // Link is back (e.g. back in the car): reconnect promptly.
                notifIo.execute {
                    notifLink?.retryNow()
                    ensureNotifLink()
                }
            }
        }
    }

    /** Last time we recycled the USB serial claim for silent-audio recovery. */
    private var lastUsbcRecycleMs = 0L

    // Every few seconds: follow whichever player is actually playing (a
    // player already in the session list that starts playing changes no
    // list, so nothing else would switch to it - the app kept following the
    // old one until reopened), and resend so the car's position stays right
    // and a Pi that rebooted catches up.
    private var beats = 0
    private val heartbeat = object : Runnable {
        override fun run() {
            if (forwarderOnly) {
                // Phone is only a notification forwarder: no music / Pi status traffic.
                ensureNotifLink()
                main.postDelayed(this, 5000)
                return
            }
            ensureNotifLink()
            // First beat after start: remember overlay state for loss detection.
            if (beats == 0) overlayWasOn = try { Settings.canDrawOverlays(this@NowPlayingService) }
            catch (_: Exception) { null }
            pickController()          // also resends (clears lastLine, pushes)
            // Nothing playing anywhere: say "still here" anyway, so the XIAO
            // sees the app has its USB link (else it replugs to get it one).
            if (controller == null) io.execute { sendLine("{\"cmd\":\"ping\",\"ctl\":2}") }
            // Keep the Pi's idea of vehicle movement fresh (e.g. after it rebooted).
            LocationWatch.moving?.let { mv -> io.execute { sendLine("{\"moving\":$mv}") } }
            // Refresh the audio-path check shown in the notification (~every 30 s).
            if (beats % 6 == 0) request(JSONObject().put("req", "pi"))
            // Keep Android's safe-volume warning suppressed: a car (A2DP)
            // reconnect can re-arm it mid-drive, so re-assert it every ~60 s.
            if (beats % 12 == 0) keepVolumeWarningOff()
            // SMO / head units sometimes clear "Display over other apps" —
            // notice quickly and offer a one-tap fix.
            if (beats % 2 == 0) checkOverlayPermission()
            // This device's time zone and clock for the Pi (which has neither
            // in the car): at the start, then every 5 minutes.
            if (beats++ % 60 == 0) request(JSONObject().put("clock", JSONObject()
                .put("tz", java.util.TimeZone.getDefault().id).put("now", System.currentTimeMillis())))
            main.postDelayed(this, 5000)
        }
    }

    private var overlayWasOn: Boolean? = null
    private var lastOverlayNudgeMs = 0L
    private val NOTIF_OVERLAY = 7

    /** If "Display over other apps" flips off, nudge with a high-priority
     *  notification (call / text banners need it). */
    private fun checkOverlayPermission() {
        if (forwarderOnly) return
        val on = try { Settings.canDrawOverlays(this) } catch (_: Exception) { return }
        val was = overlayWasOn
        overlayWasOn = on
        val nm = getSystemService(NotificationManager::class.java)
        if (on) {
            nm.cancel(NOTIF_OVERLAY)
            return
        }
        // Only nag when it was on before (true regression), or we have never
        // seen it on this process and setup still needs it.
        val lost = was == true
        if (!lost && was != null) return
        val now = System.currentTimeMillis()
        if (now - lastOverlayNudgeMs < 30 * 60 * 1000L && !lost) return
        lastOverlayNudgeMs = now
        try {
            nm.createNotificationChannel(NotificationChannel(
                "permissions", "Permissions", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "When a required permission is turned off" })
            val open = PendingIntent.getActivity(
                this, 7,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, "permissions")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(
                    if (lost) "Display over other apps was turned off"
                    else "Allow Display over other apps"
                )
                .setContentText("Needed for call and text pop-ups over music. Tap to turn it back on.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIF_OVERLAY, n)
            if (lost) Log.w(TAG, "Display over other apps revoked — nudged user")
        } catch (e: Exception) {
            Log.w(TAG, "overlay nudge failed: ${e.message}")
        }
    }

    // ---------- calls and texts from the Pi (shown on this device) ----------
    private val callButtons = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_ANSWER -> callCommand("answer")
                ACTION_DECLINE -> callCommand("decline")
                ACTION_SHADE_MUTE -> {
                    val pkg = i.getStringExtra(EXTRA_PKG).orEmpty()
                    val app = i.getStringExtra(EXTRA_APP).orEmpty()
                    val shadeId = i.getIntExtra(EXTRA_SHADE_ID, -1)
                    if (pkg.isNotEmpty()) OverlayMuteActivity.start(this@NowPlayingService, pkg, app, shadeId)
                }
                ACTION_SHADE_ACT -> {
                    val nid = i.getStringExtra(EXTRA_NID).orEmpty()
                    val id = i.getStringExtra(EXTRA_ACTION_ID).orEmpty()
                    val shadeId = i.getIntExtra(EXTRA_SHADE_ID, -1)
                    sendNotifAction(nid, id)
                    if (shadeId > 0) getSystemService(NotificationManager::class.java).cancel(shadeId)
                }
                ACTION_SHADE_REPLY -> {
                    val nid = i.getStringExtra(EXTRA_NID).orEmpty()
                    val id = i.getStringExtra(EXTRA_ACTION_ID).orEmpty()
                    val label = i.getStringExtra(EXTRA_ACTION_LABEL).orEmpty().ifBlank { "Reply" }
                    val shadeId = i.getIntExtra(EXTRA_SHADE_ID, -1)
                    OverlayReplyActivity.start(this@NowPlayingService, label) { text ->
                        sendNotifAction(nid, id, text)
                        if (shadeId > 0) getSystemService(NotificationManager::class.java).cancel(shadeId)
                    }
                }
                ACTION_SHADE_OPEN -> {
                    val nid = i.getStringExtra(EXTRA_NID).orEmpty()
                    val shadeId = i.getIntExtra(EXTRA_SHADE_ID, -1)
                    SystemUi.collapseShade(this@NowPlayingService)
                    openShadeNotif(nid, shadeId)
                }
            }
        }
    }

    /** Answer / Decline / Hang up, from the notification or the call bar.
     *  Answering keeps both up (they turn into the "On a call" controls when
     *  the Pi says the call is active); ending removes them straight away. */
    private fun callCommand(cmd: String) {
        command(cmd) { }
        if (cmd == "decline") {
            callState = "idle"; callWho = ""
            renderCall()
        }
    }

    private val callBar by lazy { CallBar(this) { callCommand(it) } }

    /** Answer / Hang up from the app's inline call panel. */
    fun callAction(cmd: String) = callCommand(cmd)

    /** The app's foreground state changed, or its Phone screen opened/closed:
     *  move the call between the inline panel and the floating bar. */
    fun setAppForeground(fg: Boolean) { appForeground = fg; main.post { renderCall() } }
    fun refreshCallUi() { main.post { renderCall() } }

    /** The app screen's "Test call bar": a pretend call for 8 seconds. */
    fun testCallBar() {
        main.post {
            channels()
            showCall("incoming", "Test caller", "")
            main.postDelayed({ if (callState == "incoming") showCall("active", "Test caller", "") }, 3000)
            main.postDelayed({ if (callState == "active") showCall("idle", "", "") }, 8000)
        }
    }
    private var callState = "idle"
    /** A call is connected (so the keypad should send DTMF tones, not dial). */
    fun callActive(): Boolean = callState == "active"
    private var callSince = 0L      // elapsedRealtime when the call was answered
    private var callSinceWall = 0L  // the same, as a clock time (notification timer)

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            "calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
        // High importance = pops up on screen. (The first "texts" channel
        // was normal importance, which Android never lets an app raise, so
        // it is replaced by a new one.)
        nm.deleteNotificationChannel("texts")
        nm.createNotificationChannel(NotificationChannel(
            "texts_popup", "Text messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(
            "call_ongoing", "Call in progress", NotificationManager.IMPORTANCE_LOW))
        // Quiet shade entry after the floating banner — no second heads-up.
        // Kept as a fallback; live posts use per-source-app channels below.
        nm.createNotificationChannel(NotificationChannel(
            "mirrored", "Mirrored alerts", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Phone notifications kept in the shade after the banner"
            setSound(null, null)
            enableVibration(false)
        })
    }

    /**
     * One quiet channel per phone app so shade rows stay separate and labeled
     * by source (WhatsApp, Messages, …). Android still attributes them to
     * Phone Link as the posting app — that cannot be spoofed.
     */
    private fun mirrorChannel(pkg: String, app: String): String {
        val id = "mirror_" + (pkg.ifEmpty { "other" }.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40))
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(
                id, app.ifEmpty { "Mirrored alert" }, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Mirrored from $app on your phone"
                setSound(null, null)
                enableVibration(false)
            })
        }
        return id
    }

    /** A line from the Pi: {"call": state, "number", "name"} or {"text": {...}}. */
    private fun onPiLine(line: String) {
        val j = try { JSONObject(line) } catch (e: Exception) { return }
        lastTraffic = System.currentTimeMillis()
        if (j.has("pi")) { piStatus = j.optJSONObject("pi"); piStatusAt = System.currentTimeMillis(); return }
        if (j.has("bt")) { btStatus = j.optJSONObject("bt"); onBtStatus(); return }
        if (j.has("fw_result")) { fwCb?.invoke(j.optJSONObject("fw_result") ?: JSONObject()); return }
        if (j.has("flow")) {
            flow = j.optJSONArray("flow")
            maybeRecoverUsbcAudio(flow)
            main.post { if (instance != null) goForeground() }
            return
        }
        if (j.has("check")) { checkResult = j.optJSONObject("check"); return }
        if (j.has("bt_result")) { btResult = j.optJSONObject("bt_result"); return }
        if (PhoneData.onLine(j)) return     // contacts, recents, texts for the Phone/Messages screens
        // A new text: the Messages screen's list is out of date.
        if (j.has("text")) request(JSONObject().put("req", "messages"))
        main.post {
            try {
                channels()
                when {
                    j.has("media") -> media(j.optString("media"))
                    j.has("volume") -> if (j.optString("volume") == "max") volumeMax()
                    j.has("call") -> showCall(j.optString("call"), j.optString("name"), j.optString("number"))
                    j.has("text") -> j.optJSONObject("text")?.let {
                        showText(it.optString("from"), it.optString("body"), it.optString("number"))
                    }
                    j.has("notif") -> j.optJSONObject("notif")?.let { showNotif(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "notification failed: ${e.message}")
            }
        }
    }

    /** Media volume to 100% when the Pi asks (each time this device connects;
     *  the car's volume is what people use). Set directly: if Android shows its
     *  "safe listening" warning, confirming it applies the full 100%. */
    /** With WRITE_SECURE_SETTINGS (granted once over adb), mark Android's
     *  high-volume ("safe listening") limiter as DISABLED, so it never holds
     *  the volume back. We use 1 = SAFE_MEDIA_VOLUME_DISABLED, not 2 = INACTIVE:
     *  INACTIVE only means "dismissed for now" and the system re-arms it to
     *  ACTIVE (value 3) on a reboot or a Bluetooth reconnect, which is what kept
     *  the warning coming back. Android reads this flag at start-up, so a set
     *  value takes full effect after the next restart of this device. */
    fun keepVolumeWarningOff(): Boolean {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val cr = contentResolver
        val key = "audio_safe_volume_state"   // 1 = DISABLED (feature off for good)
        val off = 1
        var touched = false
        // ROMs keep this flag in different namespaces. Set it wherever it
        // already lives (and fall back to Global). WRITE_SECURE_SETTINGS covers
        // Global and Secure; System needs WRITE_SETTINGS, so that one may throw
        // - harmless, we just skip it.
        fun getNs(ns: Int) = try { when (ns) {
            0 -> android.provider.Settings.Global.getInt(cr, key, -1)
            1 -> android.provider.Settings.Secure.getInt(cr, key, -1)
            else -> android.provider.Settings.System.getInt(cr, key, -1)
        } } catch (e: Exception) { -1 }
        fun putNs(ns: Int) = try { when (ns) {
            0 -> android.provider.Settings.Global.putInt(cr, key, off)
            1 -> android.provider.Settings.Secure.putInt(cr, key, off)
            else -> android.provider.Settings.System.putInt(cr, key, off)
        }; true } catch (e: Exception) { Log.w(TAG, "safe-vol ns$ns: ${e.message}"); false }
        for (ns in 0..2) {
            val cur = getNs(ns)
            if (cur == -1) continue                        // key not in this namespace
            if (cur == off) { touched = true; continue }   // already disabled here
            if (putNs(ns)) touched = true
        }
        if (!touched) touched = putNs(0)                   // not found anywhere: try Global
        return touched
    }

    fun volumeMax(personAsked: Boolean = false) {
        keepVolumeWarningOff()
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            if (am.getStreamVolume(stream) >= max) return
            // Silent: no volume-bar popup (flag 0, not FLAG_SHOW_UI). Media
            // volume needs no permission; with the safe-volume limiter off it
            // goes straight to max in the background. If the limiter is still on
            // (other devices), the set is held back and the personAsked=false
            // branch below pops up our own prompt only then.
            am.setStreamVolume(stream, max, 0)
            if (personAsked) {
                // Android's hearing warning is up: once OK is tapped, set the full
                // volume again (some Android versions drop the held-back request).
                volumeWatchUntil = System.currentTimeMillis() + 60000
                registerVolumeWatch()
            } else {
                // Blocked by the warning (which can vanish before anyone taps it):
                // ask in a pop-up of our own that stays until tapped.
                main.postDelayed({
                    if (am.getStreamVolume(stream) < max) {
                        textPopup.show(
                            "Phone Link",
                            "Volume limited by Android",
                            "Android's hearing-safety warning held the volume back. " +
                                "Tap here, then OK, for full volume.",
                            stayMs = 60000,
                        ) { volumeMax(true) }
                    }
                }, 2500)
            }
        } catch (e: Exception) {
            Log.w(TAG, "volume: ${e.message}")
        }
    }

    private var volumeWatchUntil = 0L
    private var volumeWatching = false
    private val volumeWatch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val am = getSystemService(android.media.AudioManager::class.java)
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            if (System.currentTimeMillis() > volumeWatchUntil) {
                try { unregisterReceiver(this) } catch (_: Exception) {}
                volumeWatching = false
                return
            }
            // The volume moved (OK was tapped): finish the job, no more warnings.
            if (am.getStreamVolume(stream) < max) am.setStreamVolume(stream, max, 0)
        }
    }

    private fun registerVolumeWatch() {
        if (volumeWatching) return
        volumeWatching = true
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeWatch, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(volumeWatch, f)
    }

    /** The car's play/pause/next/previous, relayed by the Pi: straight to
     *  the player's own controls, the way a phone handles them over
     *  Bluetooth (no media-key guessing). */
    private fun media(cmd: String) {
        if (forwarderOnly) return
        if (controller == null) pickController()
        val c = controller
        if (c == null) {
            // No music app has a session yet (e.g. just after the device
            // started): a media key goes to the last music app, and starts it.
            val code = when (cmd) {
                "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "playpause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return
            }
            val am = getSystemService(android.media.AudioManager::class.java)
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            return
        }
        val t = c.transportControls
        val playing = c.playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
        }
        when (cmd) {
            "play" -> t.play()
            "pause" -> t.pause()
            "playpause" -> if (playing) t.pause() else t.play()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "stop" -> t.stop()
        }
    }

    private var callWho = ""

    private fun showCall(state: String, name: String, number: String) {
        if (state !in listOf("incoming", "outgoing", "active")) {
            callState = "idle"; callWho = ""
            renderCall()
            return
        }
        if (state == "active" && callState != "active") {
            callSince = CallBar.now()
            callSinceWall = System.currentTimeMillis()
        }
        callState = state
        callWho = name.ifEmpty { number.ifEmpty { if (state == "incoming") "Unknown caller" else "" } }
        renderCall()
    }

    /** Put the current call where it belongs: inline on the app's Phone screen
     *  when the app is open, otherwise the floating bar / notification. */
    private fun renderCall() {
        val nm = getSystemService(NotificationManager::class.java)
        val state = callState
        if (state != "idle" && appForeground && callUi != null) {
            callBar.hide()
            nm.cancel(NOTIF_CALL)
            callUi?.invoke(state, callWho, callSince)
            return
        }
        callUi?.invoke("idle", "", 0L)       // nothing to show in the app's panel
        if (state == "idle") {
            nm.cancel(NOTIF_CALL)
            callBar.hide()
            return
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val answer = PendingIntent.getBroadcast(this, 1, Intent(ACTION_ANSWER).setPackage(packageName), flags)
        val decline = PendingIntent.getBroadcast(this, 2, Intent(ACTION_DECLINE).setPackage(packageName), flags)
        val who = callWho
        callBar.show(state, who, callSince)
        if (CallBar.lastProblem.isEmpty()) {
            // The floating bar is up: no notification as well (it would
            // drop down over the bar).
            nm.cancel(NOTIF_CALL)
            return
        }
        if (state != "incoming") {
            // During the call: a quiet notification (no pop-up) with Hang up.
            val n = Notification.Builder(this, "call_ongoing")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(if (state == "active") "On a call" else "Calling...")
                .setContentText(who)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setShowWhen(state == "active")
                .setWhen(if (state == "active") callSinceWall else System.currentTimeMillis())
                .setUsesChronometer(state == "active")
                .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Hang up", decline).build())
                .build()
            nm.notify(NOTIF_CALL, n)
            return
        }
        val n = Notification.Builder(this, "calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(who)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            // Keeps the pop-up on screen until answered or declined.
            .setFullScreenIntent(PendingIntent.getActivity(this, 3,
                Intent(this, MainActivity::class.java), flags), true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Answer", answer).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Decline", decline).build())
            .build()
        nm.notify(NOTIF_CALL, n)
    }

    private var textId = 100
    private var shadeIdSeq = SHADE_ID_BASE

    private val textPopup by lazy { TextPopup(this) }

    /** A mirrored phone notification (from the forwarder on the phone, relayed
     *  by the Pi): floating banner + a lasting shade notification (actions /
     *  Mute) so you can act later without opening Phone Link. */
    private fun showNotif(o: JSONObject) {
        val pkg = o.optString("pkg")
        if (pkg.isNotEmpty() && CarNotifMute.isMuted(this, pkg)) {
            Log.i(TAG, "car-muted notif from $pkg — skipped")
            return
        }
        NotifLog.add(o)
        val app = o.optString("app").ifEmpty { "Notification" }
        val title = o.optString("title")
        val body = o.optString("text").ifEmpty { o.optString("big") }
        val icon = decodeNotifIcon(o.optString("icon"))
        val actions = NotifLog.parseActions(o)
        val nid = o.optString("nid")
        val onAction = { act: NotifLog.Action, text: String? ->
            sendNotifAction(nid, act.id, text.orEmpty())
        }
        val onMute = {
            OverlayMuteActivity.start(this, pkg, app)
        }
        // Always keep a shade entry (quiet) so it remains after the banner goes.
        postShadeNotif(o)
        textPopup.show(app, title, body, icon, 9000, 4, actions, onAction, onMute,
            onTap = { showNotifDetail(o) })
    }

    /** Quiet notification in the system shade with phone actions + Mute. */
    private fun postShadeNotif(o: JSONObject) {
        channels()
        val app = o.optString("app").ifEmpty { "Notification" }
        val title = o.optString("title")
        val body = o.optString("text").ifEmpty { o.optString("big") }
        val big = o.optString("big").ifEmpty { body }
        val pkg = o.optString("pkg")
        val nid = o.optString("nid")
        val actions = NotifLog.parseActions(o)
        val shadeId = shadeIdSeq++
        if (shadeIdSeq > SHADE_ID_MAX) shadeIdSeq = SHADE_ID_BASE
        if (nid.isNotEmpty()) {
            synchronized(shadePayloads) {
                shadePayloads[nid] = o
                while (shadePayloads.size > 40) {
                    val first = shadePayloads.keys.firstOrNull() ?: break
                    shadePayloads.remove(first)
                }
            }
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Body tap → that notification’s actions (banner detail), not Alerts list.
        val open = PendingIntent.getBroadcast(
            this, shadeId,
            Intent(ACTION_SHADE_OPEN).setPackage(packageName)
                .putExtra(EXTRA_SHADE_ID, shadeId)
                .putExtra(EXTRA_NID, nid),
            flags
        )
        // Unique group key → system won't auto-stack everything under one
        // "Phone Link" bundle. SubText + channel name carry the source app;
        // the shade still shows Phone Link as the posting app (platform rule).
        val groupKey = "mirror_" + (nid.ifEmpty { shadeId.toString() })
        val channel = mirrorChannel(pkg, app)
        val whenMs = o.optLong("when", System.currentTimeMillis())
        val b = Notification.Builder(this, channel)
            .setContentTitle(title.ifEmpty { app })
            .setContentText(body)
            .setSubText(app)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(groupKey)
            .setSortKey(String.format("%013d", Long.MAX_VALUE - whenMs))
            .setShowWhen(true)
            .setWhen(whenMs)
        val icon = decodeNotifIcon(o.optString("icon"))
        val appBmp = if (icon is BitmapDrawable && icon.bitmap != null) {
            drawableToBitmap(icon, 128)
        } else null
        // Status bar: white/black silhouette of THIS app's icon (not a generic
        // chat glyph). Android only uses alpha in setSmallIcon — a full-color
        // opaque bitmap becomes a square, so we derive a proper silhouette.
        if (appBmp != null) {
            b.setSmallIcon(Icon.createWithBitmap(statusBarSilhouette(appBmp)))
        } else {
            b.setSmallIcon(R.drawable.ic_text)
        }
        if (appBmp != null && Build.VERSION.SDK_INT >= 28) {
            // Full-color app icon as the conversation avatar (left of the row).
            val person = Person.Builder()
                .setName(title.ifEmpty { app })
                .setKey(pkg.ifEmpty { nid }.ifEmpty { shadeId.toString() })
                .setIcon(Icon.createWithBitmap(circleCropBitmap(appBmp)))
                .build()
            val style = Notification.MessagingStyle(person)
                .setGroupConversation(false)
            if (title.isNotEmpty()) style.setConversationTitle(app)
            val msg = body.ifEmpty { big }.ifEmpty { app }
            style.addMessage(msg, whenMs, person)
            b.setStyle(style)
        } else {
            if (appBmp != null) b.setLargeIcon(appBmp)
            b.setStyle(Notification.BigTextStyle().bigText(big).setSummaryText(app))
        }
        // Up to 2 phone actions + Mute (Android shows ~3 actions).
        for (act in actions.take(2)) {
            val intent = Intent(
                if (act.reply) ACTION_SHADE_REPLY else ACTION_SHADE_ACT
            ).setPackage(packageName)
                .putExtra(EXTRA_SHADE_ID, shadeId)
                .putExtra(EXTRA_NID, nid)
                .putExtra(EXTRA_ACTION_ID, act.id)
                .putExtra(EXTRA_ACTION_LABEL, act.label)
            val pi = PendingIntent.getBroadcast(this, shadeId * 10 + act.id.hashCode(), intent, flags)
            b.addAction(Notification.Action.Builder(
                null as android.graphics.drawable.Icon?, act.label, pi
            ).build())
        }
        if (pkg.isNotEmpty()) {
            val muteIntent = Intent(ACTION_SHADE_MUTE).setPackage(packageName)
                .putExtra(EXTRA_SHADE_ID, shadeId)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_APP, app)
            val mutePi = PendingIntent.getBroadcast(this, shadeId * 10 + 9, muteIntent, flags)
            b.addAction(Notification.Action.Builder(
                null as android.graphics.drawable.Icon?, "Mute", mutePi
            ).build())
        }
        getSystemService(NotificationManager::class.java).notify(shadeId, b.build())
    }

    private fun showNotifDetail(o: JSONObject): Boolean {
        val app = o.optString("app").ifEmpty { "Notification" }
        val title = o.optString("title")
        val body = o.optString("big").ifEmpty { o.optString("text") }
        val actions = NotifLog.parseActions(o)
        val nid = o.optString("nid")
        val pkg = o.optString("pkg")
        return showNotifDetail(app, title, body, o.optString("icon"), nid, pkg, actions)
    }

    private fun showNotifDetail(item: NotifLog.Item): Boolean {
        return showNotifDetail(
            item.app, item.title, item.body(), item.iconB64, item.nid, item.pkg, item.actions
        )
    }

    private fun showNotifDetail(
        app: String,
        title: String,
        body: String,
        iconB64: String,
        nid: String,
        pkg: String,
        actions: List<NotifLog.Action>,
    ): Boolean {
        return textPopup.show(app, title, body, decodeNotifIcon(iconB64), 30000, 16, actions,
            onAction = { act, text -> sendNotifAction(nid, act.id, text.orEmpty()) },
            onMute = if (pkg.isNotEmpty()) ({ OverlayMuteActivity.start(this, pkg, app) }) else null)
    }

    /** Shade notification body tap: open that alert with Reply / actions. */
    private fun openShadeNotif(nid: String, shadeId: Int) {
        val fromLog = NotifLog.findByNid(nid)
        val fromCache = if (nid.isNotEmpty()) synchronized(shadePayloads) { shadePayloads[nid] } else null
        val shown = when {
            fromLog != null -> showNotifDetail(fromLog)
            fromCache != null -> showNotifDetail(fromCache)
            else -> false
        }
        if (!shown) {
            // No overlay / unknown nid: open Alerts focused on this notification.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_NOTIF_NID, nid)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
            )
        }
        if (shadeId > 0) getSystemService(NotificationManager::class.java).cancel(shadeId)
    }

    /** Recent shade payloads by nid (so a tap still works if the log was cleared). */
    private val shadePayloads = linkedMapOf<String, JSONObject>()

    /** Head unit → Pi → phone: fire a Notification.Action (reply / mark read). */
    fun sendNotifAction(nid: String, actionId: String, text: String = "") {
        if (nid.isEmpty() || actionId.isEmpty()) return
        val o = JSONObject().put("nid", nid).put("id", actionId)
        if (text.isNotEmpty()) o.put("text", text)
        val line = JSONObject().put("notif_action", o).toString()
        io.execute { sendLine(line) }
    }

    /** Head unit → Pi → phone: permanently mute [pkg] in the forwarder. */
    fun sendNotifMute(pkg: String, appLabel: String = "") {
        if (pkg.isEmpty()) return
        val o = JSONObject().put("pkg", pkg)
        if (appLabel.isNotEmpty()) o.put("app", appLabel)
        val line = JSONObject().put("notif_mute", o).toString()
        io.execute { sendLine(line) }
    }

    private fun decodeNotifIcon(b64: String): Drawable? {
        if (b64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            BitmapDrawable(resources, bmp)
        } catch (_: Exception) { null }
    }

    // ---------- forwarder (on the phone): mirror notifications to the Pi ----------
    @Volatile private var forwardNotifs = false
    @Volatile private var forwarderOnly = false
    private var notifLink: BtLink? = null
    /**
     * When the listener connects (or forwarding is turned on), Android re-posts
     * every active shade notification via [onNotificationPosted]. Gate those out
     * so only notifications that appear after the phone is linked are mirrored.
     */
    @Volatile private var forwardAfterWallMs = 0L
    @Volatile private var suppressReplayUntilElapsed = 0L
    private val shadeKeysAtArm = mutableSetOf<String>()
    /** Recent forwards for de-dupe: key -> elapsedRealtime of last send. */
    private val recentNotifs = LinkedHashMap<String, Long>()
    private val DEDUPE_MS = 45_000L
    private val REPLAY_SUPPRESS_MS = 5_000L
    /** Pending Notification.Action intents keyed by "nid|actionId". */
    private data class HeldNotifAction(
        val pending: PendingIntent,
        val remoteInput: RemoteInput?,
        val expiresAt: Long,
    )
    private val heldActions = LinkedHashMap<String, HeldNotifAction>()
    private val ACTION_TTL_MS = 15 * 60_000L
    private val MAX_HELD_ACTIONS = 80

    private fun prefs() = getSharedPreferences("auxlink", MODE_PRIVATE)

    /** Turn notification forwarding on/off (set from the app's switch; meant to
     *  be enabled on the phone, not the music head unit). */
    fun setForwardNotifs(on: Boolean) {
        forwardNotifs = on
        try { prefs().edit().putBoolean("notif_forward", on).apply() } catch (e: Exception) {}
        if (on) {
            armNotifForwardGate("forwarding on")
            if (notifLink == null) {
                notifLink = BtLink(this, BtLink.NOTIF_UUID) { line -> onNotifLinkLine(line) }
            }
        } else {
            notifLink?.close(); notifLink = null
            synchronized(heldActions) { heldActions.clear() }
            synchronized(shadeKeysAtArm) { shadeKeysAtArm.clear() }
        }
    }

    /** Ignore the shade dump Android delivers when the listener (re)connects. */
    private fun armNotifForwardGate(why: String) {
        forwardAfterWallMs = System.currentTimeMillis()
        suppressReplayUntilElapsed = SystemClock.elapsedRealtime() + REPLAY_SUPPRESS_MS
        val keys = mutableSetOf<String>()
        try {
            activeNotifications?.forEach { keys += it.key }
        } catch (e: Exception) {
            Log.w(TAG, "activeNotifications: ${e.message}")
        }
        synchronized(shadeKeysAtArm) {
            shadeKeysAtArm.clear()
            shadeKeysAtArm += keys
        }
        Log.i(TAG, "notif forward gate: $why — ignore ${keys.size} existing shade item(s) for ${REPLAY_SUPPRESS_MS}ms")
    }

    /** True for the burst of already-posted shade items after connect. */
    private fun isConnectReplay(sbn: android.service.notification.StatusBarNotification): Boolean {
        if (SystemClock.elapsedRealtime() >= suppressReplayUntilElapsed) return false
        synchronized(shadeKeysAtArm) {
            if (sbn.key in shadeKeysAtArm) return true
        }
        val posted = sbn.postTime
        return posted > 0L && posted < forwardAfterWallMs
    }

    /** Lines from the Pi on the notification RFCOMM (Stage 3 actions / mute). */
    private fun onNotifLinkLine(line: String) {
        val j = try { JSONObject(line) } catch (_: Exception) { return }
        val mute = j.optJSONObject("notif_mute")
        if (mute != null) {
            val pkg = mute.optString("pkg")
            if (pkg.isNotEmpty()) {
                // Allowlist: drop this app so it stops forwarding.
                val allowed = allowedNotifPackages().toMutableSet()
                if (allowed.remove(pkg)) setAllowedNotifPackages(allowed)
                Log.i(TAG, "Always-mute from head unit: removed $pkg from allowlist")
            }
            return
        }
        val a = j.optJSONObject("notif_action") ?: return
        val nid = a.optString("nid")
        val id = a.optString("id")
        val text = a.optString("text")
        notifIo.execute { fireNotifAction(nid, id, text) }
    }

    private fun fireNotifAction(nid: String, id: String, text: String) {
        if (nid.isEmpty() || id.isEmpty()) return
        val key = "$nid|$id"
        val held = synchronized(heldActions) {
            pruneHeldActionsLocked()
            heldActions.remove(key)
        } ?: run {
            Log.w(TAG, "notif_action unknown/expired: $key")
            return
        }
        try {
            val ri = held.remoteInput
            if (ri != null) {
                if (text.isEmpty()) {
                    Log.w(TAG, "notif_action reply missing text: $key")
                    return
                }
                val intent = Intent()
                val results = Bundle().apply { putCharSequence(ri.resultKey, text) }
                RemoteInput.addResultsToIntent(arrayOf(ri), intent, results)
                held.pending.send(this, 0, intent)
            } else {
                held.pending.send()
            }
            Log.i(TAG, "Fired notif action $key")
        } catch (e: Exception) {
            Log.w(TAG, "notif_action failed: ${e.message}")
        }
    }

    private fun pruneHeldActionsLocked() {
        val now = SystemClock.elapsedRealtime()
        heldActions.entries.removeAll { now > it.value.expiresAt }
        while (heldActions.size > MAX_HELD_ACTIONS) {
            val first = heldActions.keys.firstOrNull() ?: break
            heldActions.remove(first)
        }
    }

    /** Pack reply / mark-read actions and keep their PendingIntents for later. */
    private fun packNotifActions(sbn: android.service.notification.StatusBarNotification, n: Notification): JSONArray {
        val raw = mutableListOf<Notification.Action>()
        n.actions?.let { raw.addAll(it) }
        try {
            for (a in Notification.WearableExtender(n).actions) {
                if (raw.none { it.title == a.title && it.actionIntent == a.actionIntent }) raw += a
            }
        } catch (_: Exception) {}
        fun isReply(a: Notification.Action): Boolean {
            if (Build.VERSION.SDK_INT >= 28 &&
                a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) return true
            return a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        fun isMarkRead(a: Notification.Action): Boolean =
            Build.VERSION.SDK_INT >= 28 &&
                a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
        fun rank(a: Notification.Action) = when {
            isReply(a) -> 0
            isMarkRead(a) -> 1
            else -> 2
        }
        val chosen = raw.filter { it.actionIntent != null && !it.title.isNullOrBlank() }
            .sortedBy { rank(it) }
            .take(3)
        val arr = JSONArray()
        val nid = sbn.key
        val expires = SystemClock.elapsedRealtime() + ACTION_TTL_MS
        synchronized(heldActions) {
            pruneHeldActionsLocked()
            chosen.forEachIndexed { i, action ->
                val id = i.toString()
                val reply = isReply(action)
                val ri = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                heldActions["$nid|$id"] = HeldNotifAction(action.actionIntent!!, if (reply) ri else null, expires)
                arr.put(JSONObject().put("id", id).put("label", action.title.toString())
                    .put("reply", reply))
            }
        }
        return arr
    }

    /**
     * Phone-only: stop acting as the music device (no track info / art / media
     * commands over USB or the app Bluetooth link). Notification forwarding
     * keeps running on its own link.
     */
    fun setForwarderOnly(on: Boolean) {
        forwarderOnly = on
        try { prefs().edit().putBoolean("notif_forwarder_only", on).apply() } catch (e: Exception) {}
        if (on) stopMusicSide() else startMusicSide()
    }

    fun isForwarderOnly() = forwarderOnly

    /**
     * Allowlist of packages to mirror. Only these are forwarded — system
     * services and anything not selected never come through. Migrates once
     * from the old mute-list (launchable apps minus muted).
     */
    fun allowedNotifPackages(): Set<String> = readAllowedNotifPackages(this)

    fun setAllowedNotifPackages(pkgs: Set<String>) {
        try {
            prefs().edit()
                .putStringSet(KEY_ALLOWED_PKGS, pkgs.toSet())
                .putBoolean(KEY_ALLOWLIST_READY, true)
                .apply()
        } catch (_: Exception) {}
    }

    /** True only if [pkg] was explicitly selected in Apps to forward. */
    fun isNotifForwardAllowed(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg in allowedNotifPackages()

    /** Apps the forwarder has seen posting a notification (kept for diagnostics). */
    fun seenNotifApps(): List<Pair<String, String>> {
        val raw = try { prefs().getStringSet("notif_seen_apps", emptySet()) ?: emptySet() }
        catch (_: Exception) { emptySet() }
        return raw.mapNotNull {
            val i = it.indexOf('\t')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.sortedBy { it.second.lowercase() }
    }

    private fun rememberSeenApp(pkg: String, label: String) {
        try {
            val cur = prefs().getStringSet("notif_seen_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
            cur.removeAll { it.startsWith("$pkg\t") }
            cur.add("$pkg\t$label")
            while (cur.size > 80) cur.remove(cur.first())
            prefs().edit().putStringSet("notif_seen_apps", cur).apply()
        } catch (_: Exception) {}
    }

    @Volatile private var notifCount = 0
    @Volatile private var notifSkippedMute = 0
    @Volatile private var notifSkippedDedupe = 0
    @Volatile private var notifSkippedEmpty = 0
    @Volatile private var notifSendFail = 0
    @Volatile private var notifSent = 0
    @Volatile private var notifLastSkip = ""

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        if (!forwardNotifs) return
        notifCount++
        try { forwardNotification(sbn) } catch (e: Exception) { Log.w(TAG, "forward: ${e.message}") }
    }

    /** A one-line status for the app's screen (so we can tell what's happening
     *  without a logcat): is forwarding on, how many notifications were seen,
     *  and what the last Bluetooth attempt did. */
    fun notifDiag() = "forwarding=${if (forwardNotifs) "ON" else "off"}, " +
        "only=${if (forwarderOnly) "ON" else "off"}, seen=$notifCount, " +
        "sent=$notifSent, fail=$notifSendFail, " +
        "blocked=$notifSkippedMute, dedupe=$notifSkippedDedupe, empty=$notifSkippedEmpty, " +
        "lastSkip=$notifLastSkip, " +
        "link=${notifLink?.lastResult ?: "(forwarder not started)"}"

    /** Keep the phone→Pi notification RFCOMM up after a Bluetooth drop.
     *  Always probe with a ping — after leaving the car the socket object can
     *  still look open while RFCOMM is dead (Test send force-retries; live
     *  Discord posts were silently dropped). */
    private fun ensureNotifLink() {
        if (!forwardNotifs) return
        if (notifLink == null)
            notifLink = BtLink(this, BtLink.NOTIF_UUID) { line -> onNotifLinkLine(line) }
        notifIo.execute {
            val link = notifLink ?: return@execute
            if (!link.send("""{"cmd":"ping"}""")) {
                link.retryNow()
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                link.send("""{"cmd":"ping"}""")
            }
        }
    }

    /** Send one forwarded line; retry once like Test send after a drop. */
    private fun sendNotifLine(line: String): Boolean {
        val link = notifLink ?: run {
            notifLink = BtLink(this, BtLink.NOTIF_UUID) { onNotifLinkLine(it) }
            notifLink
        } ?: return false
        if (link.send(line)) return true
        link.close()
        link.retryNow()
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        return link.send(line)
    }

    /** Send a test notification straight to the Pi (forces a connection), and
     *  report whether the Bluetooth write succeeded. */
    fun testNotifForward(cb: (Boolean) -> Unit) {
        if (notifLink == null)
            notifLink = BtLink(this, BtLink.NOTIF_UUID) { line -> onNotifLinkLine(line) }
        notifLink?.retryNow()
        val o = JSONObject().put("app", "AuxLink").put("title", "Test notification")
            .put("text", "If this shows on the car, forwarding works.").put("big", "")
            .put("pkg", packageName).put("when", System.currentTimeMillis())
            .put("nid", "test").put("actions", JSONArray())
            .put("icon", appIconPng(packageName))
        val line = JSONObject().put("notif", o).toString()
        notifIo.execute {
            var ok = notifLink?.send(line) ?: false
            if (!ok) {
                // Common right after phone↔Pi reconnect: one quick retry.
                notifLink?.close()
                notifLink?.retryNow()
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                ok = notifLink?.send(line) ?: false
            }
            main.post { cb(ok) }
        }
    }

    private fun forwardNotification(sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName == packageName) return                 // our own
        // SMS already arrives via the Pi Messages path (Tesla + classic pop-up).
        // Skip the phone's SMS app so you don't have to mute it by hand.
        if (isSmsApp(sbn.packageName)) return
        if (isConnectReplay(sbn)) {
            notifLastSkip = "preconnect:${sbn.packageName}"
            return
        }
        val n = sbn.notification ?: return
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) {
            notifLastSkip = "ongoing:${sbn.packageName}"; return
        }
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            notifLastSkip = "summary:${sbn.packageName}"; return
        }
        val cat = n.category
        if (cat == Notification.CATEGORY_CALL || cat == Notification.CATEGORY_TRANSPORT) {
            notifLastSkip = "cat:$cat:${sbn.packageName}"; return
        }
        val ex = n.extras ?: return
        val (title, text, big) = notifTitleText(ex)
        if (title.isEmpty() && text.isEmpty()) {
            notifSkippedEmpty++
            notifLastSkip = "empty:${sbn.packageName}"
            return
        }
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        rememberSeenApp(sbn.packageName, app)
        // Allowlist only — system / unselected packages never forward.
        if (!isNotifForwardAllowed(sbn.packageName)) {
            notifSkippedMute++
            notifLastSkip = "not-allowed:${sbn.packageName}"
            return
        }
        val dedupeKey = sbn.packageName + "|" + title + "|" + text.take(120)
        val now = SystemClock.elapsedRealtime()
        synchronized(recentNotifs) {
            recentNotifs.entries.removeAll { now - it.value > DEDUPE_MS }
            val prev = recentNotifs[dedupeKey]
            if (prev != null && now - prev < DEDUPE_MS) {
                notifSkippedDedupe++
                notifLastSkip = "dedupe:${sbn.packageName}"
                return
            }
            recentNotifs[dedupeKey] = now
        }
        val actions = packNotifActions(sbn, n)
        val o = JSONObject().put("app", app).put("title", title).put("text", text)
            .put("big", big).put("pkg", sbn.packageName).put("when", sbn.postTime)
            .put("nid", sbn.key).put("actions", actions)
            .put("icon", appIconPng(sbn.packageName))
        val line = JSONObject().put("notif", o).toString()
        notifIo.execute {
            if (sendNotifLine(line)) {
                notifSent++
                notifLastSkip = ""
            } else {
                notifSendFail++
                notifLastSkip = "sendfail:${sbn.packageName}"
                Log.w(TAG, "notif send failed for ${sbn.packageName}")
            }
        }
    }

    /**
     * Title/body for forwarding. Discord and other chat apps often use
     * MessagingStyle with empty EXTRA_TEXT — pull the latest message instead
     * of dropping the notification as "empty".
     */
    private fun notifTitleText(ex: android.os.Bundle): Triple<String, String, String> {
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isEmpty())
            text = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (text.isEmpty() && Build.VERSION.SDK_INT >= 30)
            text = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        var big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (big.isEmpty()) {
            big = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString() }.orEmpty()
        }
        if ((title.isEmpty() && text.isEmpty()) || (text.isEmpty() && big.isEmpty())) {
            // No androidx: parse MessagingStyle extras directly (Discord etc.).
            // Platform extractMessagingStyleFromNotification is missing from the
            // compile classpath this project uses.
            val chat = messagingFromExtras(ex)
            if (chat != null) {
                if (title.isEmpty()) title = chat.first
                if (text.isEmpty()) text = chat.second
                if (big.isEmpty()) big = chat.third
            }
        }
        if (big.isEmpty()) big = text
        return Triple(title, text, big)
    }

    /** Last few MessagingStyle lines from notification extras, if any. */
    private fun messagingFromExtras(ex: android.os.Bundle): Triple<String, String, String>? {
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val lines = mutableListOf<String>()
        var lastText = ""
        var lastSender = ""
        for (p in arr) {
            val b: android.os.Bundle? = when (p) {
                is android.os.Bundle -> p
                else -> try {
                    p.javaClass.getMethod("toBundle").invoke(p) as? android.os.Bundle
                } catch (_: Exception) { null }
            }
            if (b == null) continue
            val t = b.getCharSequence("text")?.toString().orEmpty()
            if (t.isEmpty()) continue
            var s = b.getCharSequence("sender")?.toString().orEmpty()
            if (s.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                try {
                    @Suppress("DEPRECATION")
                    val person = b.getParcelable("sender_person") as? android.app.Person
                    s = person?.name?.toString().orEmpty()
                } catch (_: Exception) {}
            }
            lastText = t
            lastSender = s
            lines += if (s.isNotEmpty()) "$s: $t" else t
        }
        if (lastText.isEmpty()) return null
        val conv = if (Build.VERSION.SDK_INT >= 30)
            ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        else ""
        val title = conv.ifEmpty { lastSender }
        val big = lines.takeLast(5).joinToString("\n")
        return Triple(title, lastText, big)
    }

    /** True for the phone's SMS/MMS app (handled by MAP → Messages / Tesla,
     *  not the notification forwarder). Chat apps like WhatsApp are left alone. */
    private fun isSmsApp(pkg: String): Boolean {
        val def = try { Telephony.Sms.getDefaultSmsPackage(this) } catch (_: Exception) { null }
        if (!def.isNullOrEmpty() && pkg == def) return true
        return pkg == "com.android.mms" ||
            pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging" ||
            pkg == "com.android.messaging" ||
            pkg == "com.coloros.mms" ||
            pkg == "com.oplus.mms" ||
            pkg.endsWith(".mms")
    }

    /** Default SMS app icon on this device (for the classic text pop-up). */
    private fun defaultSmsIcon(): Drawable? {
        val candidates = mutableListOf<String>()
        try { Telephony.Sms.getDefaultSmsPackage(this)?.let { candidates += it } } catch (_: Exception) {}
        candidates += listOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
            "com.coloros.mms",
            "com.oplus.mms",
        )
        for (pkg in candidates.distinct()) {
            try { return packageManager.getApplicationIcon(pkg) } catch (_: Exception) {}
        }
        return try { getDrawable(R.drawable.ic_text)?.mutate()?.also { it.setTint(0xFFFFFFFF.toInt()) } }
        catch (_: Exception) { null }
    }

    /** Small PNG of the app icon, base64 — for the head-unit banner (that
     *  device does not have WhatsApp/etc installed). Empty if it fails or
     *  would be too large for the Bluetooth line. */
    private fun appIconPng(pkg: String): String {
        return try {
            val d = packageManager.getApplicationIcon(pkg)
            val bmp = drawableToBitmap(d, 72)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            if (bytes.size > 12_000) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        // Adaptive icons: draw() applies the system mask (circle/squircle) with
        // transparent corners — needed so status-bar silhouettes aren't squares.
        if (Build.VERSION.SDK_INT >= 26 &&
            d is android.graphics.drawable.AdaptiveIconDrawable) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            return bmp
        }
        if (d is BitmapDrawable && d.bitmap != null) {
            val src = d.bitmap
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(
                src, null,
                android.graphics.Rect(0, 0, size, size),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            return out
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** Round bitmap for MessagingStyle Person avatars (transparent corners). */
    private fun circleCropBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        }
        c.clipPath(path)
        val left = (src.width - size) / 2f
        val top = (src.height - size) / 2f
        c.drawBitmap(src, -left, -top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Status-bar glyph from an app icon: white shape, transparent background.
     * SystemUI only uses alpha and tints white/black — a full-bleed opaque icon
     * becomes a white square in the circular status-bar chip. We circle-crop
     * first, punch flat letterbox/background fill, then keep alpha as the shape.
     */
    private fun statusBarSilhouette(src: Bitmap, size: Int = 72): Bitmap {
        // Circle first so opaque square launcher icons can't become a square glyph.
        val round = circleCropBitmap(src)
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(
            round, null,
            android.graphics.Rect(0, 0, size, size),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        val n = size * size
        val px = IntArray(n)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        // Letterbox punch only when edge samples agree with each other AND
        // differ from the centre — otherwise a solid WhatsApp-style disc has
        // the same colour at the edge and we'd erase the whole glyph.
        val samples = intArrayOf(
            px[size / 2], px[size * (size / 2)],
            px[size * (size / 2) + size - 1], px[n - size / 2]
        )
        val center = px[size * (size / 2) + size / 2]
        var br = 0; var bgc = 0; var bb = 0; var bn = 0
        for (s in samples) {
            if (((s ushr 24) and 0xFF) < 128) continue
            br += (s shr 16) and 0xFF
            bgc += (s shr 8) and 0xFF
            bb += s and 0xFF
            bn++
        }
        var punchBg = false
        if (bn >= 3 && ((center ushr 24) and 0xFF) >= 128) {
            br /= bn; bgc /= bn; bb /= bn
            val cr = (center shr 16) and 0xFF
            val cg = (center shr 8) and 0xFF
            val cb = center and 0xFF
            val dCenter = (cr - br) * (cr - br) + (cg - bgc) * (cg - bgc) + (cb - bb) * (cb - bb)
            // Edges look like a shared pad colour, distinct from the logo centre.
            punchBg = dCenter >= 45 * 45
        }
        for (i in 0 until n) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            if (a < 16) { px[i] = 0; continue }
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (punchBg) {
                val dr = r - br; val dg = g - bgc; val db = b - bb
                if (dr * dr + dg * dg + db * db < 40 * 40) {
                    px[i] = 0
                    continue
                }
            }
            val sat = maxOf(r, g, b) - minOf(r, g, b)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            // Near-white fill with no chroma → transparent; keep logo/content.
            if (sat < 18 && lum > 235) { px[i] = 0; continue }
            px[i] = (a shl 24) or 0x00FFFFFF
        }
        // Re-apply a clean circular mask (anti-aliased edge for the status chip).
        val mid = (size - 1) / 2f
        val r2 = (size / 2f - 0.75f).let { it * it }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - mid
                val dy = y - mid
                if (dx * dx + dy * dy > r2) px[y * size + x] = 0
            }
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, size, 0, 0, size, size)
        return out
    }

    /** Opens this conversation in the app's Messages tab. */
    private fun chatIntent(number: String, name: String) =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CHAT_NUMBER, number)
            .putExtra(MainActivity.EXTRA_CHAT_NAME, name)

    private fun showText(from: String, body: String, number: String) {
        // Pi Messages path (also what the Tesla sees via MAP): classic pop-up
        // with the default messaging app's icon. Tap opens the conversation.
        // The forwarder auto-skips the SMS app so texts aren't doubled.
        val smsIcon = defaultSmsIcon()
        if (textPopup.showMessage(from.ifEmpty { "Text message" }, body, smsIcon) {
                try { startActivity(chatIntent(number, from)) } catch (e: Exception) {}
            }) return
        val tap = PendingIntent.getActivity(this, textId, chatIntent(number, from),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(this, "texts_popup")
            .setContentIntent(tap)
            .setContentTitle(from.ifEmpty { "Text message" })
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        if (smsIcon != null && Build.VERSION.SDK_INT >= 23) {
            nb.setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(drawableToBitmap(smsIcon, 96)))
        } else {
            nb.setSmallIcon(android.R.drawable.sym_action_chat)
        }
        getSystemService(NotificationManager::class.java).notify(textId++, nb.build())
        if (textId > 150) textId = 100
    }

    /** Keep running: a permanent low-key notification makes this a
     *  foreground service, which Android (and head units' app killers) leave
     *  alone. If Android refuses (e.g. started in the background on a
     *  device that forbids it), the app still works as before. */
    /**
     * Pi says sound should be playing but USB-C capture is silent: drop our
     * CDC serial claim briefly so the USB audio HAL can recover. Rate-limited.
     * The Pi may also force a gadget re-plug if silence continues.
     */
    private fun maybeRecoverUsbcAudio(f: org.json.JSONArray?) {
        if (f == null || forwarderOnly) return
        var silentWhilePlaying = false
        for (i in 0 until f.length()) {
            val o = f.optJSONObject(i) ?: continue
            if (o.optString("title").startsWith("Sound from the music") &&
                o.optString("st") == "bad") {
                silentWhilePlaying = true
                break
            }
        }
        if (!silentWhilePlaying) return
        val now = System.currentTimeMillis()
        if (now - lastUsbcRecycleMs < 30_000L) return
        lastUsbcRecycleMs = now
        Log.w(TAG, "USB-C audio silent while playing — recycling serial claim")
        lastRecoveryNote = "Recycled USB serial (silent while playing)"
        lastRecoveryAt = now
        io.execute { link.recycle(1500) }
    }

    /** The audio-path check as (one-line summary, multi-line detail) for the
     *  ongoing notification, or null when the Pi hasn't sent it yet. */
    private fun flowText(): Pair<String, String>? {
        val f = flow ?: return null
        if (f.length() == 0) return null
        val sb = StringBuilder()
        var bad = false
        for (i in 0 until f.length()) {
            val s = f.optJSONObject(i) ?: continue
            val title = s.optString("title")
            if (title.isEmpty()) continue
            val st = s.optString("st")
            if (st == "bad") bad = true
            val sym = when (st) { "ok" -> "✓"; "bad" -> "✗"; "idle" -> "–"; else -> "•" }
            sb.append(sym).append(' ').append(title)
            val detail = s.optString("detail")
            if (detail.isNotEmpty()) sb.append(" — ").append(detail)
            sb.append('\n')
        }
        val big = sb.toString().trimEnd()
        if (big.isEmpty()) return null
        return (if (bad) "Audio path: needs attention" else "Audio path OK") to big
    }

    private var carWasConnected = false
    private val wifiNudge = Runnable { wifiRescanIfNeeded() }

    /** "Nudge Wi-Fi when the car connects" switch. A fallback only: 15 s after
     *  the car's Bluetooth connects (giving Wi-Fi a chance to join on its own),
     *  if this device still isn't on any Wi-Fi, turn Wi-Fi on and kick off a
     *  fresh scan so the system re-evaluates its known networks and joins one
     *  (some head units sit with Wi-Fi off, and Spotify needs it). No specific
     *  network is suggested, and it does not keep retrying. Best effort. */
    private fun onBtStatus() {
        val p = try { getSharedPreferences("auxlink", MODE_PRIVATE) } catch (e: Exception) { return }
        // New key, falling back to the old "when the car's off" key.
        if (!p.getBoolean("wifi_keep_on", p.getBoolean("wifi_on_car_off", false))) return

        val carObj = btStatus?.optJSONObject("car") ?: return
        val carConnected = carObj.optBoolean("connected")
        val justConnected = carConnected && !carWasConnected
        carWasConnected = carConnected
        if (!justConnected) return

        // Check 15 s later, not instantly - Wi-Fi often reconnects on its own.
        main.removeCallbacks(wifiNudge)
        main.postDelayed(wifiNudge, 15_000L)
    }

    private fun wifiRescanIfNeeded() {
        if (!carWasConnected) return      // car dropped again within the 15 s
        val p = try { getSharedPreferences("auxlink", MODE_PRIVATE) } catch (e: Exception) { return }
        if (!p.getBoolean("wifi_keep_on", p.getBoolean("wifi_on_car_off", false))) return

        // Already on Wi-Fi? Then nothing to do - this is only a fallback.
        val onWifi = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) { false }
        if (onWifi) return

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return
        try { if (!wm.isWifiEnabled) { @Suppress("DEPRECATION") wm.setWifiEnabled(true) } }
        catch (e: Exception) { Log.w(TAG, "turn Wi-Fi on: ${e.message}") }
        // Re-scan so the system reconnects to a known network it can see.
        try { @Suppress("DEPRECATION") wm.startScan(); Log.i(TAG, "car connected, still no Wi-Fi: rescanning") }
        catch (e: Exception) { Log.w(TAG, "Wi-Fi rescan: ${e.message}") }
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                "running", "Phone Link running", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            // Swipe down to see the audio path check (source -> Pi -> car).
            val path = flowText()
            val b = Notification.Builder(this, "running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Phone Link connected")
                .setContentText(path?.first ?: "Sending what's playing to the car")
                .setContentIntent(open)
                .setOngoing(true)
            path?.let { b.setStyle(Notification.BigTextStyle().bigText(it.second)) }
            val n = b.build()
            if (Build.VERSION.SDK_INT >= 29) {
                // Add the location type only once the permission is granted, so
                // reading the car's (mock) location from here is allowed; without
                // it, start as a plain connected-device service.
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= 30 && LocationWatch.hasPermission(this))
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(1, n, type)
            } else startForeground(1, n)
        } catch (e: Exception) {
            Log.w(TAG, "could not keep a running notification: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        keepVolumeWarningOff()
        instance = this
        goForeground()
        try {
            forwarderOnly = prefs().getBoolean("notif_forwarder_only", false)
            if (prefs().getBoolean("notif_forward", false))
                // setForwardNotifs arms the gate so the system's re-delivery of
                // every active shade item is ignored as pre-connect.
                setForwardNotifs(true)
        } catch (e: Exception) {}
        link = UsbLink(this) { onPiLine(it) }
        bt = BtLink(this) { onPiLine(it) }
        CarNotifMute.onBoot(this)
        val actions = IntentFilter().apply {
            addAction(ACTION_ANSWER); addAction(ACTION_DECLINE)
            addAction(ACTION_SHADE_ACT); addAction(ACTION_SHADE_MUTE)
            addAction(ACTION_SHADE_REPLY); addAction(ACTION_SHADE_OPEN)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(callButtons, actions, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(callButtons, actions)
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbLink.ACTION_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbEvents, f)
        val btF = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btAclEvents, btF, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(btAclEvents, btF)

        if (forwarderOnly) {
            // Notification forwarder only: no media sessions / location / music link traffic.
            main.post(heartbeat)
        } else {
            startMusicSide()
        }
    }

    /** Begin following media sessions and talking to the Pi as the music device. */
    private fun startMusicSide() {
        if (!::link.isInitialized) return
        if (sessions == null) {
            sessions = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val me = ComponentName(this, NowPlayingService::class.java)
            sessions?.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
        }
        startLocationWatch()
        pickController()
        main.removeCallbacks(heartbeat)
        main.post(heartbeat)
    }

    /** Stop music-device behaviour (forwarder-only on the phone). */
    private fun stopMusicSide() {
        loc.stop()
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        sessions = null
        controller?.unregisterCallback(callback)
        controller = null
        lastLine = ""
        artSentId = ""
        if (::link.isInitialized || ::bt.isInitialized) {
            io.execute {
                try { if (::link.isInitialized) link.close() } catch (_: Exception) {}
                try { if (::bt.isInitialized) bt.close() } catch (_: Exception) {}
            }
        }
        linkOpen = false
        linkKind = ""
    }

    private val loc = LocationWatch()

    /** Report vehicle movement to the Pi (if the location permission is on), so
     *  it won't drop the phone while still moving. Safe to call again after the
     *  permission is granted. */
    fun startLocationWatch() {
        // Re-assert the foreground notification so its service type now includes
        // "location" (allowed once the permission is granted), then watch.
        goForeground()
        loc.stop()
        loc.start(this) { mv -> io.execute { sendLine("{\"moving\":$mv}") } }
    }

    override fun onListenerDisconnected() {
        instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        loc.stop()
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(wifiNudge)
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller?.unregisterCallback(callback)
        try { unregisterReceiver(usbEvents) } catch (_: Exception) {}
        try { unregisterReceiver(btAclEvents) } catch (_: Exception) {}
        try { unregisterReceiver(callButtons) } catch (_: Exception) {}
        io.execute {
            try { link.close() } catch (_: Exception) {}
            try { bt.close() } catch (_: Exception) {}
            try { notifLink?.close() } catch (_: Exception) {}
        }
        notifLink = null
        synchronized(heldActions) { heldActions.clear() }
    }

    /** USB if one is plugged in (unchanged behaviour), else Bluetooth. Runs on [io]. */
    private fun sendLine(line: String): Boolean {
        if (forwarderOnly) return false
        val ok = if (link.findDevice() != null) {
            bt.close()
            link.send(line).also { if (it) linkKind = "USB" }
        } else {
            bt.send(line).also { if (it) linkKind = "Bluetooth (${bt.deviceName})" }
        }
        if (ok) lastTraffic = System.currentTimeMillis()
        linkOpen = link.isOpen() || bt.isOpen()
        if (!connected()) linkKind = ""
        return ok
    }

    private fun pickController() {
        if (forwarderOnly) return
        val list = try {
            sessions?.getActiveSessions(ComponentName(this, NowPlayingService::class.java))
        } catch (e: SecurityException) { null } ?: emptyList()
        val playing = { c: MediaController? -> c?.playbackState?.state == PlaybackState.STATE_PLAYING }
        val best = controller?.takeIf { cur -> playing(cur) && list.any { it.sessionToken == cur.sessionToken } }
            ?: list.firstOrNull { playing(it) }
            ?: controller?.takeIf { cur -> list.any { it.sessionToken == cur.sessionToken } }
            ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
            Log.i(TAG, "Following ${best?.packageName}")
        }
        lastLine = ""
        push()
    }

    private fun push() {
        if (forwarderOnly) return
        val c = controller ?: return
        val md = c.metadata
        val ps = c.playbackState
        // BUFFERING is not Playing for the car — advertising Playing before
        // samples hit USB/I2S causes Tesla silent-stream glitches.
        val state = when (ps?.state) {
            PlaybackState.STATE_PLAYING -> "Playing"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PAUSED -> "Paused"
            else -> "Stopped"
        }
        val json = JSONObject().apply {
            put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
            put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            put("dur", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            put("pos", currentPosition(ps))
            put("state", state)
            // So the Pi can restart the car stream when YouTube → Spotify etc.
            put("pkg", c.packageName ?: "")
            put("ctl", 2)   // the Pi may send {"media": play/pause/next...} and {"volume": "max"}
        }
        val line = json.toString()
        nowPlaying = line
        val art = artFor(md)
        // Skip exact repeats, but always let the heartbeat through (it clears lastLine).
        if (line != lastLine) {
            lastLine = line
            io.execute {
                if (sendLine(line)) lastSent = line else lastLine = ""
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (art.first != artSentId || now - artSentAt > 60_000) {
            val (id, artJson) = art
            artSentId = id
            artSentAt = now
            io.execute {
                if (!sendLine(artJson)) artSentId = ""   // try again next push
            }
        }
    }

    /** (id, line) for the current track's artwork; cached per track. */
    private fun artFor(md: MediaMetadata?): Pair<String, String> {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key = if (bmp == null) "none" else
            "${md?.getString(MediaMetadata.METADATA_KEY_TITLE)}|${md?.getString(MediaMetadata.METADATA_KEY_ALBUM)}|" +
                "${bmp.width}x${bmp.height}|${bmp.generationId}"
        if (key == artKey && artLine.isNotEmpty()) return artIdOf(artLine) to artLine
        artKey = key
        artLine = if (bmp == null) {
            JSONObject().put("art", "").put("art_id", "none").toString()
        } else {
            val jpeg = thumbnail(bmp)
            val crc = CRC32().apply { update(jpeg) }.value.toString(16)
            JSONObject().put("art", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                .put("art_id", crc).toString()
        }
        return artIdOf(artLine) to artLine
    }

    private fun artIdOf(line: String) = try { JSONObject(line).getString("art_id") } catch (_: Exception) { "" }

    /** Centre-cropped square, 200x200, JPEG - the size cars show. */
    private fun thumbnail(src: Bitmap): ByteArray {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, 200, 200, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (small !== sq) small.recycle()
        if (sq !== src) sq.recycle()
        return out.toByteArray()
    }

    private fun currentPosition(ps: PlaybackState?): Long {
        if (ps == null) return 0
        var pos = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
            pos += (elapsed * ps.playbackSpeed).toLong()
        }
        return maxOf(0, pos)
    }
}
