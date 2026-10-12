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
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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

    private val main = Handler(Looper.getMainLooper())
    private lateinit var link: UsbLink
    private lateinit var bt: BtLink
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastLine = ""
    // Sends run here, in order, off the main thread (an image takes ~1.5 s).
    private val io = Executors.newSingleThreadExecutor()
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
            pickController()          // also resends (clears lastLine, pushes)
            // Nothing playing anywhere: say "still here" anyway, so the XIAO
            // sees the app has its USB link (else it replugs to get it one).
            if (controller == null) io.execute { sendLine("{\"cmd\":\"ping\",\"ctl\":2}") }
            // Keep the Pi's idea of vehicle movement fresh (e.g. after it rebooted).
            LocationWatch.moving?.let { mv -> io.execute { sendLine("{\"moving\":$mv}") } }
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
            getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL)
            callBar.hide()
            callState = "idle"
        }
    }

    private val callBar by lazy { CallBar(this) { callCommand(it) } }

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
        if (j.has("bt")) { btStatus = j.optJSONObject("bt"); return }
        if (j.has("flow")) { flow = j.optJSONArray("flow"); return }
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
     *  high-volume warning as already accepted, so it never holds the volume
     *  back. Android reads this at start-up, so keeping it set means it stays
     *  off after every restart of this device too. */
    fun keepVolumeWarningOff(): Boolean {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            val cr = contentResolver
            if (android.provider.Settings.Global.getInt(cr, "audio_safe_volume_state", 0) != 2)
                android.provider.Settings.Global.putInt(cr, "audio_safe_volume_state", 2)
            true
        } catch (e: Exception) {
            Log.w(TAG, "volume warning: ${e.message}"); false
        }
    }

    fun volumeMax(personAsked: Boolean = false) {
        keepVolumeWarningOff()
        try {
            val am = getSystemService(android.media.AudioManager::class.java)
            val stream = android.media.AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            if (am.getStreamVolume(stream) >= max) return
            am.setStreamVolume(stream, max, android.media.AudioManager.FLAG_SHOW_UI)
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
                        textPopup.show("Volume limited by Android",
                            "Android's hearing-safety warning held the volume back. " +
                                "Tap here, then OK, for full volume.", 60000) { volumeMax(true) }
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

    private fun showCall(state: String, name: String, number: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (state !in listOf("incoming", "outgoing", "active")) {
            nm.cancel(NOTIF_CALL)
            callBar.hide()
            callState = "idle"
            return
        }
        if (state == "active" && callState != "active") {
            callSince = CallBar.now()
            callSinceWall = System.currentTimeMillis()
        }
        callState = state
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val answer = PendingIntent.getBroadcast(this, 1, Intent(ACTION_ANSWER).setPackage(packageName), flags)
        val decline = PendingIntent.getBroadcast(this, 2, Intent(ACTION_DECLINE).setPackage(packageName), flags)
        val who = name.ifEmpty { number.ifEmpty { if (state == "incoming") "Unknown caller" else "" } }
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
            .setContentText(if (name.isNotEmpty() && number.isNotEmpty()) "$name  $number" else who)
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

    /** Opens this conversation in the app's Messages tab. */
    private fun chatIntent(number: String, name: String) =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CHAT_NUMBER, number)
            .putExtra(MainActivity.EXTRA_CHAT_NAME, name)

    private fun showText(from: String, body: String, number: String) {
        // Floating window when allowed; a notification only if not. Tapping
        // either opens the conversation.
        if (textPopup.show(from.ifEmpty { "Text message" }, body) {
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
    private fun goForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                "running", "Phone Link running", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Phone Link connected")
                .setContentText("Sending what's playing to the car")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
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

        sessions = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val me = ComponentName(this, NowPlayingService::class.java)
        sessions?.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
        startLocationWatch()
        pickController()
        main.post(heartbeat)
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
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller?.unregisterCallback(callback)
        try { unregisterReceiver(usbEvents) } catch (_: Exception) {}
        try { unregisterReceiver(callButtons) } catch (_: Exception) {}
        io.execute { link.close(); bt.close() }
    }

    /** USB if one is plugged in (unchanged behaviour), else Bluetooth. Runs on [io]. */
    private fun sendLine(line: String): Boolean {
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
