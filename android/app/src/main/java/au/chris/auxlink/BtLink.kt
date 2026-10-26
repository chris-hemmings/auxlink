package au.chris.auxlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.io.OutputStream
import java.util.UUID

/**
 * Writes lines to the auxlink over Bluetooth, for the Bluetooth music
 * source (no USB link at all). The Pi offers a serial service with
 * [APP_UUID] on the adapter this device is paired with; this connects to it
 * and sends the same lines as [UsbLink]. Only used when there is no USB link.
 * Opens lazily, and after a failure waits [RETRY_MS] before trying again so
 * a device without the Pi nearby isn't searched constantly.
 *
 * Also used by the notification forwarder ([NOTIF_UUID]) on the phone.
 * Both services listen on fixed RFCOMM channels on the Pi; we prefer those
 * so a missing/broken SDP record cannot block the connect.
 */
@SuppressLint("MissingPermission")   // every call checks permitted() first
class BtLink(private val context: Context, private val serviceUuid: UUID = APP_UUID,
             private val onLine: ((String) -> Unit)? = null) {
    companion object {
        val APP_UUID: UUID = UUID.fromString("7e5b1a20-3c4d-4f8e-9a6b-74657362726b")
        /** The Pi's notification-forwarder serial service (phone -> Pi). */
        val NOTIF_UUID: UUID = UUID.fromString("7e5b1a20-3c4d-4f8e-9a6b-6e6f74696679")
        /** Must match NOTIF_CHANNEL / APP_CHANNEL in auxlink-media.py. */
        const val NOTIF_CHANNEL = 22
        const val APP_CHANNEL = 23
        /** Music / general link: back off after a failed sweep. */
        private const val RETRY_MS = 20_000L
        /** Notification forwarder: reconnect quickly after phone↔Pi drop. */
        private const val NOTIF_RETRY_MS = 3_000L
        /** After a device first appears bonded while the app is running, wait
         *  this long before opening a service channel to it. A just-finished
         *  pairing is BONDED on Android a moment before the Pi has adopted and
         *  trusted it; connecting in that gap makes the Pi reject the
         *  still-untrusted service request, which aborts the bond (Android's
         *  "just connect" pairing race). Devices already bonded when the app
         *  starts are treated as settled, so normal reconnects aren't delayed. */
        private const val BOND_SETTLE_MS = 20_000L
        /** Cap on the exponential back-off between failed connect attempts. */
        private const val MAX_RETRY_MS = 60_000L
        private const val TAG = "SmoBtLink"
        /** What the last attempt did, shown on the app's screen. */
        @Volatile var lastTry: String = "(not tried yet)"

        private fun channelFor(uuid: UUID): Int? = when (uuid) {
            NOTIF_UUID -> NOTIF_CHANNEL
            APP_UUID -> APP_CHANNEL
            else -> null
        }
    }

    private var sock: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var nextTry = 0L
    private var lastGood: String? = null
    /** Consecutive failed connect sweeps, for the back-off below. */
    private var consecFails = 0
    /** address -> elapsedRealtime a device was first seen bonded (0 = it was
     *  already bonded when we started, so it needs no settle wait). */
    private val bondedSince = HashMap<String, Long>()
    private var bondSeeded = false
    private val retryMs =
        if (serviceUuid == NOTIF_UUID) NOTIF_RETRY_MS else RETRY_MS
    /** This instance's last attempt (not shared like the companion lastTry). */
    @Volatile var lastResult: String = "(not tried yet)"
    var deviceName: String = ""
        private set

    fun permitted() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isOpen() = sock != null

    /** Address of the peer we last connected to (for ACL-drop handling). */
    fun peerAddress(): String? = lastGood

    /** Try at once (button / reconnect): clear the back-off even if a zombie
     *  socket is still held — [send] will close it and reopen. */
    fun retryNow() { nextTry = 0L }

    /** Drop a half-dead socket so the next [send]/[open] reconnects. */
    @Synchronized
    fun dropIfPeer(address: String?) {
        if (address.isNullOrEmpty()) return
        if (lastGood.equals(address, ignoreCase = true)) close()
    }

    @Synchronized
    fun send(line: String): Boolean {
        if (out == null && !open()) return false
        return try {
            out!!.write((line + "\n").toByteArray(Charsets.UTF_8))
            out!!.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            close()
            nextTry = 0L   // phone/Pi just dropped — reconnect on the next send
            false
        }
    }

    /** Bonded devices most likely to be the auxlink first: the one that
     *  worked last, those already ACL-connected, those known to offer the
     *  service, those named like it.
     *
     *  Notification RFCOMM is a *separate* socket from HFP/A2DP. After a
     *  reconnect the phone can look "fully connected" while channel 22 is
     *  still down. Sweeping every paired device then costs ~10–20s each and
     *  is what made Test send appear ~2 minutes later. For [NOTIF_UUID] we
     *  only try Pi-like / last-good / already-connected peers. */
    private fun candidates(): List<BluetoothDevice> {
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return emptyList()
        if (!bt.isEnabled) return emptyList()
        val allBonded = (bt.bondedDevices?.toList() ?: return emptyList())
            .filter { it.bondState == BluetoothDevice.BOND_BONDED }
        // Bond-settle guard: a device that only just became bonded (a pairing
        // in progress) is skipped for BOND_SETTLE_MS so we never open a
        // service channel into a half-finished bond and abort it. Devices that
        // were already bonded at start-up are seeded as settled.
        val now = SystemClock.elapsedRealtime()
        if (!bondSeeded) { allBonded.forEach { bondedSince[it.address] = 0L }; bondSeeded = true }
        bondedSince.keys.retainAll(allBonded.map { it.address }.toSet())
        val bonded = allBonded.filter { d ->
            val t = bondedSince.getOrPut(d.address) { now }
            t == 0L || now - t >= BOND_SETTLE_MS
        }
        if (bonded.isEmpty()) return emptyList()
        val target = ParcelUuid(serviceUuid)
        fun named(d: BluetoothDevice): Boolean {
            val names = listOfNotNull(d.name, if (Build.VERSION.SDK_INT >= 30) d.alias else null)
            return names.any { it.contains("auxlink", true) || it.contains("teslabridge", true) }
        }
        fun aclConnected(d: BluetoothDevice): Boolean = try {
            val m = d.javaClass.getMethod("isConnected")
            m.invoke(d) as? Boolean == true
        } catch (_: Exception) { false }
        val likely = (bonded.filter { it.address == lastGood } +
            bonded.filter { aclConnected(it) } +
            bonded.filter { d -> d.uuids?.contains(target) == true } +
            bonded.filter { named(it) }).distinctBy { it.address }
        // Notif forwarder: never walk the whole bonded list (watches, cars, …).
        if (serviceUuid == NOTIF_UUID) {
            if (likely.isNotEmpty()) return likely
            // First run / rename: fall back to named-only, else empty (retry soon).
            return bonded.filter { named(it) }
        }
        return (likely + bonded).distinctBy { it.address }
    }

    /**
     * Open an RFCOMM socket. Prefer the Pi's fixed channel (no SDP) — BlueZ
     * often registers custom UUID profiles without a usable SDP record, which
     * makes [BluetoothDevice.createRfcommSocketToServiceRecord] fail before
     * anything reaches the Pi. Fall back to UUID discovery, then the insecure
     * fixed-channel API, for older stacks.
     */
    private fun openSocket(dev: BluetoothDevice): BluetoothSocket {
        val channel = channelFor(serviceUuid)
        val errors = mutableListOf<String>()
        if (channel != null) {
            try {
                return fixedChannelSocket(dev, channel, insecure = false).also { it.connect() }
            } catch (e: Exception) {
                errors += "ch$channel: ${e.message}"
                Log.i(TAG, "${dev.name}: fixed channel $channel failed: ${e.message}")
            }
            try {
                return fixedChannelSocket(dev, channel, insecure = true).also { it.connect() }
            } catch (e: Exception) {
                errors += "ch$channel/insec: ${e.message}"
                Log.i(TAG, "${dev.name}: insecure channel $channel failed: ${e.message}")
            }
        }
        try {
            return dev.createRfcommSocketToServiceRecord(serviceUuid).also { it.connect() }
        } catch (e: Exception) {
            errors += "uuid: ${e.message}"
            throw Exception(errors.joinToString("; "), e)
        }
    }

    /** Hidden BluetoothDevice.create[Insecure]RfcommSocket(int channel). */
    private fun fixedChannelSocket(dev: BluetoothDevice, channel: Int, insecure: Boolean): BluetoothSocket {
        val name = if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket"
        val m = dev.javaClass.getMethod(name, Int::class.javaPrimitiveType)
        @Suppress("UNCHECKED_CAST")
        return m.invoke(dev, channel) as BluetoothSocket
    }

    @Synchronized
    fun open(): Boolean {
        if (!permitted() || SystemClock.elapsedRealtime() < nextTry) return false
        val list = candidates()
        if (list.isEmpty()) {
            // Nothing eligible yet: no paired device, or a just-bonded one is
            // still in its settle window. Re-check soon and do NOT count it as
            // a connect failure (that would push the back-off out and delay the
            // first real connection once it settles).
            nextTry = SystemClock.elapsedRealtime() + retryMs
            lastTry = "no paired devices (or waiting/Bluetooth off)"; lastResult = lastTry
            return false
        }
        // Back off further after each failed sweep (3s, 6, 12, 24, 48, cap) so
        // a device that keeps refusing - or an RFCOMM that can't hold while the
        // phone is busy on a call - isn't hammered, which can destabilise the
        // phone↔Pi link and its call audio. Reset to the base on a success.
        val backoff = minOf(retryMs shl minOf(consecFails, 4), MAX_RETRY_MS)
        nextTry = SystemClock.elapsedRealtime() + backoff
        val tried = mutableListOf<String>()
        for (dev in list) {
            try {
                val s = openSocket(dev)
                sock = s
                out = s.outputStream
                if (onLine != null) {
                    val lines = PiLines { l -> onLine.invoke(l) }
                    Thread {
                        val b = ByteArray(1024)
                        try {
                            val input = s.inputStream
                            while (sock === s) {
                                val n = input.read(b)
                                if (n < 0) break
                                lines.feed(b, n)
                            }
                        } catch (_: Exception) {
                        } finally {
                            // Peer dropped: clear sock so isOpen() is false and
                            // the forwarder reconnects instead of writing into a
                            // zombie RFCOMM after the car/phone link comes back.
                            synchronized(this@BtLink) {
                                if (sock === s) close()
                            }
                        }
                    }.apply { isDaemon = true; name = "btlink-read"; start() }
                }
                deviceName = dev.name ?: dev.address
                lastGood = dev.address
                consecFails = 0
                val via = channelFor(serviceUuid)?.let { "ch$it" } ?: "uuid"
                lastTry = "connected to $deviceName ($via)"; lastResult = lastTry
                Log.i(TAG, "Connected to $deviceName over Bluetooth ($via)")
                return true
            } catch (e: Exception) {
                Log.i(TAG, "${dev.name}: ${e.message}")
                tried += "${dev.name ?: dev.address}: ${e.message}"
                lastTry = tried.joinToString("\n"); lastResult = lastTry
            }
        }
        consecFails++
        return false
    }

    @Synchronized
    fun close() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
    }
}
