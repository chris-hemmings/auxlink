package au.chris.auxlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Tells whether the vehicle is moving, from the head unit's location.
 *
 * On the SMO the car's own GPS is published as the system "mock" location, so
 * with the location permission this reads it through the normal LocationManager.
 * It prefers the real speed the fix carries (the car's, over CAN) and falls back
 * to distance between fixes. It reports "moving" / "stationary" to a callback,
 * re-emitting every [REPORT_MS] so a location that goes stale (car off, fixes
 * stop) settles to stationary. This is only a guard on the Pi's "car Bluetooth
 * dropped" trigger: the Pi never drops the phone while we report moving.
 */
class LocationWatch {
    companion object {
        private const val TAG = "AuxLinkLoc"
        private const val MIN_TIME_MS = 5_000L      // ask for a fix at most this often
        private const val REPORT_MS = 10_000L       // tell the Pi this often (and on change)
        private const val STALE_MS = 20_000L        // no fix this long -> treat as stationary
        private const val SPEED_MOVING = 1.2f       // m/s (~4 km/h) counts as moving
        private const val MOVE_M = 25f              // or this far between fixes (no-speed fallback)

        /** For the app's status screen. null = unknown yet. */
        @Volatile var moving: Boolean? = null; private set
        @Volatile var permitted = false; private set

        fun hasPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private var ht: HandlerThread? = null
    private var handler: Handler? = null
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var onMoving: ((Boolean) -> Unit)? = null
    // Handler thread only:
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var curMoving = false
    private var lastReported: Boolean? = null
    private var ticker: Runnable? = null

    /** Start watching. [onMoving] is called (on a background thread) whenever
     *  moving/stationary flips. No-op without the location permission. */
    @SuppressLint("MissingPermission")   // guarded by hasPermission()
    fun start(ctx: Context, onMoving: (Boolean) -> Unit) {
        if (listener != null) return
        permitted = hasPermission(ctx)
        if (!permitted) { Log.i(TAG, "no location permission; not watching"); return }
        this.onMoving = onMoving
        val t = HandlerThread("auxlink-loc").apply { start() }
        val h = Handler(t.looper)
        ht = t; handler = h
        val mgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = mgr
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = onFix(loc)
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("deprecated in API 29")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        try {
            val provider = pickProvider(mgr)
            mgr.requestLocationUpdates(provider, MIN_TIME_MS, 0f, l, h.looper)
            Log.i(TAG, "watching location via $provider")
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates failed: ${e.message}")
        }
        // Periodic report so a stalled location settles to "stationary".
        val tick = object : Runnable {
            override fun run() { emit(); h.postDelayed(this, REPORT_MS) }
        }
        ticker = tick
        h.postDelayed(tick, REPORT_MS)
    }

    fun stop() {
        listener?.let { try { lm?.removeUpdates(it) } catch (_: Exception) {} }
        ticker?.let { handler?.removeCallbacks(it) }
        listener = null; ticker = null
        ht?.quitSafely(); ht = null; handler = null; onMoving = null
    }

    private fun onFix(loc: Location) {
        val prev = lastFix
        curMoving = if (loc.hasSpeed()) loc.speed > SPEED_MOVING
            else if (prev != null) loc.distanceTo(prev) > MOVE_M
            else false
        lastFix = loc
        lastFixAt = SystemClock.elapsedRealtime()
        emit()
    }

    private fun emit() {
        // If fixes have stopped, the car is almost certainly off/parked.
        val mv = if (SystemClock.elapsedRealtime() - lastFixAt > STALE_MS) false else curMoving
        moving = mv
        if (mv != lastReported) {
            lastReported = mv
            Log.i(TAG, if (mv) "moving" else "stationary")
            try { onMoving?.invoke(mv) } catch (e: Exception) { Log.w(TAG, "onMoving: ${e.message}") }
        }
    }

    private fun pickProvider(mgr: LocationManager): String {
        if (Build.VERSION.SDK_INT >= 31 && mgr.allProviders.contains(LocationManager.FUSED_PROVIDER))
            return LocationManager.FUSED_PROVIDER
        if (mgr.allProviders.contains(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER
        return LocationManager.NETWORK_PROVIDER
    }
}
