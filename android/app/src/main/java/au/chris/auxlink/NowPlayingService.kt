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
        @Volatile var linkOpen: Boolean = false
        @Volatile var linkKind: String = ""
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
    private val heartbeat = object : Runnable {
        override fun run() {
            pickController()          // also resends (clears lastLine, pushes)
            // Nothing playing anywhere: say "still here" anyway, so the XIAO
            // sees the app has its USB link (else it replugs to get it one).
            if (controller == null) io.execute { sendLine("{\"cmd\":\"ping\"}") }
            main.postDelayed(this, 5000)
        }
    }

    // ---------- calls and texts from the Pi (shown on this device) ----------
    private val callButtons = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cmd = if (i.action == ACTION_ANSWER) "answer" else "decline"
            command(cmd) { }
            getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL)
        }
    }

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
    }

    /** A line from the Pi: {"call": state, "number", "name"} or {"text": {...}}. */
    private fun onPiLine(line: String) {
        val j = try { JSONObject(line) } catch (e: Exception) { return }
        main.post {
            try {
                channels()
                when {
                    j.has("call") -> showCall(j.optString("call"), j.optString("name"), j.optString("number"))
                    j.has("text") -> j.optJSONObject("text")?.let {
                        showText(it.optString("from"), it.optString("body"))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "notification failed: ${e.message}")
            }
        }
    }

    private fun showCall(state: String, name: String, number: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (state != "incoming") {
            nm.cancel(NOTIF_CALL)
            return
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val answer = PendingIntent.getBroadcast(this, 1, Intent(ACTION_ANSWER).setPackage(packageName), flags)
        val decline = PendingIntent.getBroadcast(this, 2, Intent(ACTION_DECLINE).setPackage(packageName), flags)
        val who = name.ifEmpty { number.ifEmpty { "Unknown caller" } }
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

    private fun showText(from: String, body: String) {
        val n = Notification.Builder(this, "texts_popup")
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
                "running", "AuxLink running", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AuxLink connected")
                .setContentText("Sending what's playing to the car")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, n)
        } catch (e: Exception) {
            Log.w(TAG, "could not keep a running notification: ${e.message}")
        }
    }

    override fun onListenerConnected() {
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
        pickController()
        main.post(heartbeat)
    }

    override fun onListenerDisconnected() {
        instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
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
        linkOpen = link.isOpen() || bt.isOpen()
        if (!linkOpen) linkKind = ""
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
        }
        val line = json.toString()
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
