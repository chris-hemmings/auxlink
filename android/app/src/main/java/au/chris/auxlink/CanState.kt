package au.chris.auxlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Read-only reader of the car's on/off state, for the head unit (SMO) only.
 *
 * The device's own ScreenMate app, with the owner's CAN agent injected, reads
 * the car over its privileged link and BROADCASTS a small batch every cycle
 * (~2 Hz to a passive listener like us). We only listen: we never ask for
 * anything and never send to the car. From each batch we read one bit - is any
 * signal readable right now - and turn that into "car on" / "car off":
 *
 *   - signals readable (count > 0)            -> the car is on (even parked)
 *   - no signal readable, service healthy     -> the car is off (asleep)
 *     (the agent sets NO_DATA, not CAR_DOWN)
 *   - service momentarily unhealthy (CAR_DOWN) -> ignored (a reconnect blip)
 *   - no broadcast at all                      -> unknown (agent not running)
 *
 * "Off" is only declared after NO_DATA has held for [OFF_DEBOUNCE_MS], so a
 * one-cycle gap on the bus can't drop the phone mid-drive. "On" is declared the
 * instant a signal comes back. Each batch is checked against the agent's HMAC
 * (a normal app can't forge one) and its timestamp (an old batch can't be
 * replayed), so a random app can't fake a car-off.
 *
 * This matches b1658/agent AgentBroadcaster wire v3 exactly:
 *   int version, long seq, long tsElapsed, int flags, int reconnectCount,
 *   int maxReadMicros, int count, count*(int id, byte kind, int bits),
 *   then an 8-byte HMAC-SHA256 trailer over all preceding bytes (big-endian).
 */
class CanState {
    companion object {
        private const val TAG = "AuxLinkCan"
        private const val ACTION = "co.screenmate.can.agent.SIGNALS"
        private const val PERM = "co.screenmate.can.permission.SIGNALS"
        private const val EXTRA_BATCH = "batch"
        private val AUTH_KEY = "SMCAN-broadcast-hmac-v1".toByteArray(Charsets.UTF_8)
        private const val HMAC_LEN = 8
        private const val MAX_VERSION = 3
        private const val FLAG_NO_DATA = 0x1
        private const val FLAG_CAR_DOWN = 0x2

        // Standard Android Automotive VehicleProperty ids the agent now also
        // reads (added alongside the vendor signals). IGNITION_STATE is the
        // clean car-on/off signal; GEAR_SELECTION is shown for context.
        private const val ID_IGNITION = 0x11400409
        private const val ID_GEAR = 0x11400400
        // VehicleIgnitionState: 0 UNDEFINED, 1 LOCK, 2 OFF, 3 ACC, 4 ON, 5 START.
        // Car is "on" from ACC up (someone is in/using it); "off" at LOCK/OFF.
        private const val IGN_ACC = 3

        /** How long "off" must hold before we drop the phone. Ignition is
         *  authoritative, so a short debounce; the vendor-signal fallback is
         *  noisier and uses the longer window. */
        private const val OFF_DEBOUNCE_IGN_MS = 8_000L
        private const val OFF_DEBOUNCE_MS = 20_000L
        /** A batch older than this (vs the shared boot clock) is stale/replayed. */
        private const val FRESH_MS = 5_000L

        /** For the app's status screen. null = not yet known. */
        @Volatile var carOn: Boolean? = null; private set
        @Volatile var status: String = "No car data yet"; private set
        @Volatile var lastBatchAt: Long = 0L; private set
    }

    private var ht: HandlerThread? = null
    private var handler: Handler? = null
    private var receiver: BroadcastReceiver? = null
    private var onChange: ((Boolean) -> Unit)? = null
    // Confined to the handler thread: when the car first looked off (0 = it doesn't).
    private var offSince = 0L

    /** Start listening. [onChange] is called (on a background thread) only when
     *  the car on/off state actually flips. Safe to call once; a second call is
     *  ignored until [stop]. */
    fun start(ctx: Context, onChange: (Boolean) -> Unit) {
        if (receiver != null) return
        this.onChange = onChange
        val t = HandlerThread("auxlink-can").apply { start() }
        val h = Handler(t.looper)
        ht = t; handler = h
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == ACTION) onBatch(i.getByteArrayExtra(EXTRA_BATCH))
            }
        }
        receiver = rx
        val filter = IntentFilter(ACTION)
        try {
            // broadcastPermission = null: the host app doesn't hold our permission,
            // so we can't require it of the sender - the HMAC is the real check.
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(rx, filter, null, h, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(rx, filter, null, h)
            }
            Log.i(TAG, "listening for car CAN state")
        } catch (e: Exception) {
            Log.w(TAG, "could not listen: ${e.message}")
            status = "Can't listen: ${e.message}"
        }
    }

    fun stop(ctx: Context) {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        ht?.quitSafely()
        ht = null; handler = null; onChange = null
    }

    private fun onBatch(raw: ByteArray?) {
        if (raw == null || raw.size <= HMAC_LEN) return
        val payloadLen = raw.size - HMAC_LEN
        if (!macOk(raw, payloadLen)) { Log.w(TAG, "batch failed HMAC; ignored"); return }
        val flags: Int
        val count: Int
        var ignition: Int? = null
        var gear: Int? = null
        try {
            val din = DataInputStream(ByteArrayInputStream(raw, 0, payloadLen))
            val version = din.readInt()
            if (version < 1 || version > MAX_VERSION) return
            din.readLong()                       // seq
            val tsElapsed = din.readLong()
            // Reject a stale or replayed batch (tsElapsed is the device boot clock
            // we both share). A small negative skew is clamped.
            val age = SystemClock.elapsedRealtime() - tsElapsed
            if (age > FRESH_MS || age < -FRESH_MS) { Log.w(TAG, "batch stale (${age}ms); ignored"); return }
            flags = din.readInt()
            din.readInt()                        // reconnectCount
            din.readInt()                        // maxReadMicros
            count = din.readInt()
            // Read the signal entries, picking out ignition + gear by id.
            for (i in 0 until count) {
                val id = din.readInt()
                val kind = din.readByte().toInt()
                val bits = din.readInt()
                when (id) {
                    ID_IGNITION -> if (kind == 0) ignition = bits
                    ID_GEAR -> if (kind == 0) gear = bits
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad batch: ${e.message}"); return
        }

        lastBatchAt = System.currentTimeMillis()
        val serviceDown = (flags and FLAG_CAR_DOWN) != 0

        // Preferred path: IGNITION_STATE, when the agent could read a sane code.
        // 0 UNDEFINED is treated as "unknown" and falls through to the signal
        // heuristic rather than being read as off.
        val ign = ignition
        if (ign != null && ign in 1..7) {
            val on = ign >= IGN_ACC
            val g = gearName(gear)
            if (on) {
                offSince = 0L
                status = "Car on (ign ${ignName(ign)}$g)"
                flip(true)
            } else {
                val now = SystemClock.elapsedRealtime()
                if (offSince == 0L) offSince = now
                val held = now - offSince
                if (held >= OFF_DEBOUNCE_IGN_MS) {
                    status = "Car off (ign ${ignName(ign)})"
                    flip(false)
                } else {
                    status = "Car off? ign ${ignName(ign)} (${held / 1000}s)"
                }
            }
            return
        }

        // Fallback: no usable ignition reading - use "any signal readable".
        val hasData = count > 0
        if (hasData) {
            offSince = 0L
            status = "Car on ($count signals)"
            flip(true)
            return
        }
        // No signal this cycle. A momentary service blip (CAR_DOWN) doesn't count
        // toward "off" - only a healthy service with nothing to read does.
        if (serviceDown) {
            status = "Car link reconnecting"
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (offSince == 0L) offSince = now
        val held = now - offSince
        if (held >= OFF_DEBOUNCE_MS) {
            status = "Car off"
            flip(false)
        } else {
            status = "No data (${held / 1000}s)"
        }
    }

    private fun ignName(v: Int) = when (v) {
        1 -> "LOCK"; 2 -> "OFF"; 3 -> "ACC"; 4 -> "ON"; 5 -> "START"; else -> v.toString()
    }

    /** GEAR_SELECTION bits: PARK 4, REVERSE 2, NEUTRAL 1, DRIVE 8. "" if absent. */
    private fun gearName(v: Int?) = when (v) {
        null -> ""
        4 -> ", P"; 2 -> ", R"; 1 -> ", N"; 8 -> ", D"; else -> ", gear $v"
    }

    private fun flip(on: Boolean) {
        if (carOn != null && carOn == on) return
        carOn = on
        Log.i(TAG, "car ${if (on) "ON" else "OFF"}")
        try { onChange?.invoke(on) } catch (e: Exception) { Log.w(TAG, "onChange: ${e.message}") }
    }

    private fun macOk(raw: ByteArray, payloadLen: Int): Boolean {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(AUTH_KEY, "HmacSHA256"))
            val full = mac.doFinal(raw.copyOfRange(0, payloadLen))
            var ok = true
            for (i in 0 until HMAC_LEN) ok = ok and (full[i] == raw[payloadLen + i])
            ok
        } catch (e: Exception) {
            Log.w(TAG, "HMAC unavailable: ${e.message}"); false
        }
    }
}
