package au.chris.auxlink

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB permission without Android's prompt, on head units with root adb.
 *
 * Some head units (the Screenmate) forget the "Always" USB permission at
 * every boot. When USB debugging is on and the head unit runs its adb as
 * root ("Allow rooted debugging" on Screenmate OS 1.1.0b+), the app connects
 * to that adb on the device itself (127.0.0.1:5555), like a computer would,
 * and runs [RootHelper] as root to grant itself the permission.
 *
 * Only an extra: everything else works as before. It does nothing unless the
 * Pi (or XIAO) is plugged in, Phone Link has no USB permission, and USB
 * debugging is on. The first time, Android asks "Allow USB debugging?" for
 * Phone Link's own adb key: tick "Always allow" once. Any failure (no adb on
 * 5555, key not allowed, adb not root) just leaves the normal prompt/flow,
 * retrying now and then.
 *
 * Android's plug-in prompt ("Allow Phone Link to access AuxLink?") comes
 * from UsbAttachActivity's USB_DEVICE_ATTACHED filter, not from the missing
 * permission, so granting alone doesn't stop it appearing at boot. Once a root
 * grant has worked, the app switches that activity off (an app may do that to
 * its own components); if root adb stops working it switches it back on, so
 * the normal prompt returns.
 *
 * Once it works, it also makes sure (only if not already so) notification
 * access is on, battery optimisation is off and WRITE_SECURE_SETTINGS (the
 * no-volume-warning switch) is granted.
 *
 * Volume: when the Pi asks for full volume and Android's hearing-safety
 * limiter holds it back (or the no-warning permission is missing), the same
 * root adb switches the limiter off at once and the volume goes to 100% -
 * see [volumeMax]. Without root adb the normal pop-up flow is unchanged.
 */
object RootAdb {
    private const val TAG = "SmoRootAdb"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private val busy = AtomicBoolean(false)
    @Volatile private var nextTry = 0L
    @Volatile private var fails = 0
    @Volatile private var extrasDone = false

    /** For the app screen: what the last attempt found ("" = nothing tried). */
    @Volatile var status = ""
        private set

    fun adbOn(ctx: Context) = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (_: Exception) { false }

    /**
     * If the auxlink USB device is plugged in without permission and adb is
     * on, try (in the background) to grant it through root adb. Cheap to call
     * often: returns at once unless there is something to do, and backs off
     * after failures (30 s, doubling to 10 min). [now] skips the back-off
     * (plug-in, app opened). [onGranted] runs on the helper's thread.
     */
    fun maybeGrant(ctx: Context, why: String, now: Boolean = false, onGranted: () -> Unit = {}) {
        val app = ctx.applicationContext
        val link = UsbLink(app)
        if (link.findDevice() == null || link.permitted()) return
        if (!adbOn(app)) return
        val t = SystemClock.elapsedRealtime()
        if (!now && t < nextTry) return
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                if (grant(app, why)) {
                    fails = 0; nextTry = 0
                    attachPrompt(app, false)
                    onGranted()
                } else {
                    attachPrompt(app, true)
                    fails++
                    nextTry = SystemClock.elapsedRealtime() +
                        minOf(600_000L, 30_000L shl minOf(fails - 1, 5))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "root adb: ${e.message}")
            } finally {
                busy.set(false)
            }
        }.apply { isDaemon = true; name = "root-adb" }.start()
    }

    /** Android's plug-in prompt for Phone Link on (normal) or off (root grants instead). */
    private fun attachPrompt(ctx: Context, on: Boolean) {
        try {
            val c = ComponentName(ctx, UsbAttachActivity::class.java)
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (ctx.packageManager.getComponentEnabledSetting(c) == want) return
            ctx.packageManager.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
            Log.i(TAG, if (on) "USB plug-in prompt back on" else "USB plug-in prompt off (root adb grants USB access)")
        } catch (e: Exception) {
            Log.w(TAG, "plug-in prompt: ${e.message}")
        }
    }

    private fun keys(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            Log.i(TAG, "Making Phone Link's adb key")
            AdbKeyPair.generate(priv, pub)
        }
        return AdbKeyPair.read(priv, pub)
    }

    // 60 s socket timeout: the first connection waits on the "Allow USB
    // debugging?" dialog until someone taps it.
    private fun connect(kp: AdbKeyPair): Dadb = Dadb.create(HOST, PORT, kp, 4000, 60_000)

    private fun Dadb.sh(cmd: String): String = shell(cmd).allOutput.trim()

    /** One adb session at a time (USB grant and volume can both want one). */
    private val lock = Any()

    /**
     * Connects to the head unit's adb and makes sure it is root. Null (with
     * [status] saying why) if adb isn't reachable, Phone Link's key isn't
     * allowed yet, or adb won't run as root. The caller closes it.
     */
    private fun rootShell(ctx: Context): Dadb? {
        val kp = try { keys(ctx) } catch (e: Exception) {
            status = "couldn't make an adb key"; Log.w(TAG, "adb key: ${e.message}"); return null
        }
        var d = try { connect(kp).also { it.sh("true") } } catch (e: Exception) {
            status = "adb on this device isn't reachable or Phone Link isn't allowed yet " +
                "(tick Always allow on the USB debugging prompt)"
            Log.i(TAG, "adb connect: ${e.message}")
            return null
        }
        try {
            if (d.sh("id -u") == "0") return d
            Log.i(TAG, "adb isn't root: asking it to restart as root")
            try { d.root() } catch (e: Exception) { Log.i(TAG, "adb root: ${e.message}") }
            try { d.close() } catch (_: Exception) {}
            // adbd restarts: give it up to ~10 s to come back.
            for (i in 1..10) {
                SystemClock.sleep(1000)
                try {
                    d = connect(kp)
                    if (d.sh("id -u") == "0") return d
                } catch (_: Exception) {}
                try { d.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            try { d.close() } catch (_: Exception) {}
            status = "root adb failed: ${e.message}"
            Log.w(TAG, status)
            return null
        }
        status = "adb isn't root (turn on Allow rooted debugging)"
        Log.i(TAG, status)
        return null
    }

    /** Runs [RootHelper] as root with [args]; its output. */
    private fun helper(ctx: Context, d: Dadb, args: String): String {
        // Run from a copy in /data/local/tmp (what adb's shell can always
        // read); the installed APK itself if the copy fails.
        val apk = ctx.applicationInfo.sourceDir
        val tmp = "/data/local/tmp/auxlink-helper.apk"
        return d.sh("cp '$apk' $tmp 2>/dev/null && chmod 644 $tmp && C=$tmp || C='$apk'; " +
            "CLASSPATH=\$C app_process /system/bin au.chris.auxlink.RootHelper $args")
    }

    private fun grant(ctx: Context, why: String): Boolean = synchronized(lock) {
        Log.i(TAG, "No USB permission ($why): trying root adb on $HOST:$PORT")
        val d = rootShell(ctx) ?: return false
        try {
            val uid = ctx.applicationInfo.uid
            val out = helper(ctx, d, "grant %04x %04x,%04x $uid".format(UsbLink.VID, UsbLink.PID, UsbLink.PID_PI))
            Log.i(TAG, "helper: $out")
            val ok = out.lines().any { it.startsWith("OK granted") && !it.endsWith(" 0") } &&
                UsbLink(ctx).permitted()
            status = if (ok) "USB access granted automatically (root adb)" else "root adb: $out"
            if (ok && !extrasDone) extras(ctx, d)
            ok
        } catch (e: Exception) {
            status = "root adb failed: ${e.message}"
            Log.w(TAG, status)
            false
        } finally {
            try { d.close() } catch (_: Exception) {}
        }
    }

    private val volBusy = AtomicBoolean(false)
    @Volatile private var volNextTry = 0L

    /** Whether a root volume fix is worth trying now (adb on, not just failed). */
    fun canTryVolume(ctx: Context) =
        adbOn(ctx) && SystemClock.elapsedRealtime() >= volNextTry && !volBusy.get()

    /**
     * Full media volume with root's help, in the background: switches off
     * Android's hearing-safety limiter right now (what tapping OK on its
     * warning does - no reboot needed), marks it disabled for good
     * (audio_safe_volume_state = 1, read at start-up) and grants Phone Link
     * WRITE_SECURE_SETTINGS so it keeps it that way itself. [onDone](true)
     * then sets the volume (from the helper's thread). False: root adb isn't
     * available; the normal way carries on. After a failure, waits 10 min
     * before trying again (so a head unit without root adb isn't asked at
     * every connect).
     */
    fun volumeMax(ctx: Context, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        if (!adbOn(app) || !volBusy.compareAndSet(false, true)) { onDone(false); return }
        Thread {
            var ok = false
            try {
                synchronized(lock) {
                    val d = rootShell(app)
                    if (d != null) try {
                        val out = helper(app, d, "volmax")
                        Log.i(TAG, "volume helper: $out")
                        Log.i(TAG, "safe volume setting: " +
                            d.sh("settings put global audio_safe_volume_state 1 && echo ok"))
                        if (app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
                            PackageManager.PERMISSION_GRANTED) {
                            Log.i(TAG, "secure settings: " +
                                d.sh("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS"))
                        }
                        ok = out.lines().any { it.startsWith("OK") }
                    } finally {
                        try { d.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "root volume: ${e.message}")
            } finally {
                volNextTry = if (ok) 0L else SystemClock.elapsedRealtime() + 600_000L
                volBusy.set(false)
            }
            onDone(ok)
        }.apply { isDaemon = true; name = "root-adb-vol" }.start()
    }

    /** Settings Phone Link otherwise needs a person (or a computer) for. Only touches what's off. */
    private fun extras(ctx: Context, d: Dadb) {
        val pkg = ctx.packageName
        try {
            val listener = ComponentName(ctx, NowPlayingService::class.java)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 27 && !nm.isNotificationListenerAccessGranted(listener)) {
                Log.i(TAG, "notification access: " +
                    d.sh("cmd notification allow_listener ${listener.flattenToString()}"))
            }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                Log.i(TAG, "battery: " + d.sh("dumpsys deviceidle whitelist +$pkg"))
            }
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
                PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "secure settings: " + d.sh("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"))
            }
            extrasDone = true
        } catch (e: Exception) {
            Log.w(TAG, "extras: ${e.message}")
        }
    }
}
