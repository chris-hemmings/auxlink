package au.chris.smonowplaying

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import org.json.JSONObject

/**
 * Notification access is what lets an app read other apps' media sessions.
 * This service watches the active one and sends its now-playing info to the
 * XIAO, and from there to the Pi and the car.
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
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { link.close(); link.resetPermissionPrompt() }
                else -> { lastLine = ""; push() } // attached or permission granted: resend
            }
        }
    }

    // Resend every few seconds so the car's position stays right and a Pi
    // that rebooted catches up without waiting for a track change.
    private val heartbeat = object : Runnable {
        override fun run() {
            lastLine = ""
            push()
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
        link.close()
    }

    private fun pickController() {
        val list = try {
            sessions?.getActiveSessions(ComponentName(this, NowPlayingService::class.java))
        } catch (e: SecurityException) { null } ?: emptyList()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
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
        // Skip exact repeats, but always let the heartbeat through (it clears lastLine).
        if (line == lastLine) return
        if (link.send(line)) {
            lastLine = line
            lastSent = line
        }
        linkOpen = link.isOpen()
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
