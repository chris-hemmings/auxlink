package au.chris.smonowplaying

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 */
class NowPlayingService : NotificationListenerService() {
    companion object {
        private const val TAG = "SmoNowPlaying"
        @Volatile var lastSent: String = "(nothing yet)"
        @Volatile var linkOpen: Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var link: UsbLink
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
                UsbManager.ACTION_USB_DEVICE_DETACHED -> io.execute { link.close(); link.resetPermissionPrompt() }
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
            main.postDelayed(this, 5000)
        }
    }

    override fun onListenerConnected() {
        link = UsbLink(this)
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
        main.postDelayed(heartbeat, 5000)
    }

    override fun onListenerDisconnected() {
        main.removeCallbacks(heartbeat)
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller?.unregisterCallback(callback)
        try { unregisterReceiver(usbEvents) } catch (_: Exception) {}
        io.execute { link.close() }
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
                if (link.send(line)) lastSent = line else lastLine = ""
                linkOpen = link.isOpen()
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (art.first != artSentId || now - artSentAt > 60_000) {
            val (id, artJson) = art
            artSentId = id
            artSentAt = now
            io.execute {
                if (!link.send(artJson)) artSentId = ""   // try again next push
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
