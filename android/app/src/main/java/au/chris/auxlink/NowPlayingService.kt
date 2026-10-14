package au.chris.auxlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.usb.UsbManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.Log
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
        /** Last successful send to / line from the Pi (ms). */
        @Volatile var lastTraffic = 0L
        /** Connected = the link is open, or it carried something in the last
         *  15 s (a link that blips between sends still counts). */
        fun connected() = linkOpen || System.currentTimeMillis() - lastTraffic < 15000
        const val ACTION_ANSWER = "au.chris.auxlink.ANSWER"
        const val ACTION_DECLINE = "au.chris.auxlink.DECLINE"
        const val NOTIF_CALL = 2
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
     *  base64 chunks. [done] gets (ok, message); false with "nolink" means
     *  there's no link to the Pi (fall back to Wi-Fi). Callbacks run on the
     *  main thread. */
    fun pushPiUpdate(zip: ByteArray, ver: String,
                     progress: (String) -> Unit, done: (Boolean, String) -> Unit) {
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
                if (i % 4 == 0 || i == total) { val n = i; main.post { progress("Sending to the Pi… $n/$total") } }
            }
            if (ok) sendLine(JSONObject().put("fw", JSONObject().put("op", "end")).toString())
            if (!ok) { fwCb = null; main.post { done(false, "The link to the Pi dropped while sending.") }; return@execute }
            main.post { progress("Installing on the Pi…") }
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
                else -> { lastLine = ""; artSentId = ""; push() } // attached or permission granted: resend
            }
        }
    }

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
                main.postDelayed(this, 5000)
                return
            }
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
            // This device's time zone and clock for the Pi (which has neither
            // in the car): at the start, then every 5 minutes.
            if (beats++ % 60 == 0) request(JSONObject().put("clock", JSONObject()
                .put("tz", java.util.TimeZone.getDefault().id).put("now", System.currentTimeMillis())))
            main.postDelayed(this, 5000)
        }
    }

    // ---------- calls and texts from the Pi (shown on this device) ----------
    private val callButtons = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            callCommand(if (i.action == ACTION_ANSWER) "answer" else "decline")
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
    }

    /** A line from the Pi: {"call": state, "number", "name"} or {"text": {...}}. */
    private fun onPiLine(line: String) {
        val j = try { JSONObject(line) } catch (e: Exception) { return }
        lastTraffic = System.currentTimeMillis()
        if (j.has("pi")) { piStatus = j.optJSONObject("pi"); piStatusAt = System.currentTimeMillis(); return }
        if (j.has("bt")) { btStatus = j.optJSONObject("bt"); onBtStatus(); return }
        if (j.has("fw_result")) { fwCb?.invoke(j.optJSONObject("fw_result") ?: JSONObject()); return }
        if (j.has("flow")) { flow = j.optJSONArray("flow"); main.post { if (instance != null) goForeground() }; return }
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

    private val textPopup by lazy { TextPopup(this) }

    /** A mirrored phone notification (from the forwarder on the phone, relayed
     *  by the Pi): heads-up banner with the source app's icon + name, and a
     *  row in the Alerts tab scrollback. */
    private fun showNotif(o: JSONObject) {
        NotifLog.add(o)
        val app = o.optString("app").ifEmpty { "Notification" }
        val title = o.optString("title")
        val body = o.optString("text").ifEmpty { o.optString("big") }
        val icon = decodeNotifIcon(o.optString("icon"))
        if (textPopup.show(app, title, body, icon, 9000) { showNotifDetail(o) }) return
        val head = if (title.isNotEmpty()) "$app  ·  $title" else app
        val n = Notification.Builder(this, "texts_popup")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(head).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(o.optString("big").ifEmpty { body }))
            .setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(textId++, n)
        if (textId > 150) textId = 100
    }

    private fun showNotifDetail(o: JSONObject) {
        val app = o.optString("app").ifEmpty { "Notification" }
        val title = o.optString("title")
        val body = o.optString("big").ifEmpty { o.optString("text") }
        textPopup.show(app, title, body, decodeNotifIcon(o.optString("icon")), 30000, 16) {}
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
    /** Recent forwards for de-dupe: key -> elapsedRealtime of last send. */
    private val recentNotifs = LinkedHashMap<String, Long>()
    private val DEDUPE_MS = 45_000L

    private fun prefs() = getSharedPreferences("auxlink", MODE_PRIVATE)

    /** Turn notification forwarding on/off (set from the app's switch; meant to
     *  be enabled on the phone, not the music head unit). */
    fun setForwardNotifs(on: Boolean) {
        forwardNotifs = on
        try { prefs().edit().putBoolean("notif_forward", on).apply() } catch (e: Exception) {}
        if (on && notifLink == null) notifLink = BtLink(this, BtLink.NOTIF_UUID)
        else if (!on) { notifLink?.close(); notifLink = null }
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

    /** Muted packages (not forwarded). Empty = forward everything seen. */
    fun mutedNotifPackages(): Set<String> =
        try { prefs().getStringSet("notif_muted_pkgs", emptySet())?.toSet() ?: emptySet() }
        catch (_: Exception) { emptySet() }

    fun setMutedNotifPackages(pkgs: Set<String>) {
        try { prefs().edit().putStringSet("notif_muted_pkgs", pkgs.toSet()).apply() } catch (_: Exception) {}
    }

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
        "muted=$notifSkippedMute, dedupe=$notifSkippedDedupe, " +
        "link=${notifLink?.lastResult ?: "(forwarder not started)"}"

    /** Send a test notification straight to the Pi (forces a connection), and
     *  report whether the Bluetooth write succeeded. */
    fun testNotifForward(cb: (Boolean) -> Unit) {
        if (notifLink == null) notifLink = BtLink(this, BtLink.NOTIF_UUID)
        notifLink?.retryNow()
        val o = JSONObject().put("app", "AuxLink").put("title", "Test notification")
            .put("text", "If this shows on the car, forwarding works.").put("big", "")
            .put("pkg", packageName).put("when", System.currentTimeMillis())
            .put("icon", appIconPng(packageName))
        val line = JSONObject().put("notif", o).toString()
        notifIo.execute { val ok = notifLink?.send(line) ?: false; main.post { cb(ok) } }
    }

    private fun forwardNotification(sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName == packageName) return                 // our own
        val n = sbn.notification ?: return
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return  // persistent (media, foreground services)
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return  // the group header, not a real message
        val cat = n.category
        if (cat == Notification.CATEGORY_CALL || cat == Notification.CATEGORY_TRANSPORT) return  // calls/media handled elsewhere
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
            ?: text
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        rememberSeenApp(sbn.packageName, app)
        if (sbn.packageName in mutedNotifPackages()) {
            notifSkippedMute++; return
        }
        val dedupeKey = sbn.packageName + "|" + title + "|" + text.take(120)
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(recentNotifs) {
            recentNotifs.entries.removeAll { now - it.value > DEDUPE_MS }
            val prev = recentNotifs[dedupeKey]
            if (prev != null && now - prev < DEDUPE_MS) {
                notifSkippedDedupe++; return
            }
            recentNotifs[dedupeKey] = now
        }
        val o = JSONObject().put("app", app).put("title", title).put("text", text)
            .put("big", big).put("pkg", sbn.packageName).put("when", sbn.postTime)
            .put("icon", appIconPng(sbn.packageName))
        val line = JSONObject().put("notif", o).toString()
        notifIo.execute { notifLink?.send(line) }
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
        if (d is BitmapDrawable && d.bitmap != null) {
            return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** Opens this conversation in the app's Messages tab. */
    private fun chatIntent(number: String, name: String) =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CHAT_NUMBER, number)
            .putExtra(MainActivity.EXTRA_CHAT_NAME, name)

    private fun showText(from: String, body: String, number: String) {
        // Pi Messages path (also what the Tesla sees via MAP): keep the classic
        // sender + body pop-up. Tap opens the conversation. Do not use the
        // mirrored-notification banner here — mute the phone's Messages app in
        // Tools → Muted apps if the forwarder would otherwise double up.
        if (textPopup.showMessage(from.ifEmpty { "Text message" }, body) {
                try { startActivity(chatIntent(number, from)) } catch (e: Exception) {}
            }) return
        val tap = PendingIntent.getActivity(this, textId, chatIntent(number, from),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "texts_popup")
            .setContentIntent(tap)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(from.ifEmpty { "Text message" })
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(textId++, n)
        if (textId > 150) textId = 100
    }

    /** Keep running: a permanent low-key notification makes this a
     *  foreground service, which Android (and head units' app killers) leave
     *  alone. If Android refuses (e.g. started in the background on a
     *  device that forbids it), the app still works as before. */
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
                setForwardNotifs(true)
        } catch (e: Exception) {}
        link = UsbLink(this) { onPiLine(it) }
        bt = BtLink(this) { onPiLine(it) }
        val actions = IntentFilter().apply { addAction(ACTION_ANSWER); addAction(ACTION_DECLINE) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(callButtons, actions, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(callButtons, actions)
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbLink.ACTION_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbEvents, f)

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
        try { unregisterReceiver(callButtons) } catch (_: Exception) {}
        io.execute { link.close(); bt.close() }
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
        val state = when (ps?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "Playing"
            PlaybackState.STATE_PAUSED -> "Paused"
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
