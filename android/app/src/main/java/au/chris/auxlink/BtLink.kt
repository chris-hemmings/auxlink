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
 */
@SuppressLint("MissingPermission")   // every call checks permitted() first
class BtLink(private val context: Context) {
    companion object {
        val APP_UUID: UUID = UUID.fromString("7e5b1a20-3c4d-4f8e-9a6b-74657362726b")
        private const val RETRY_MS = 20_000L
        private const val TAG = "SmoBtLink"
    }

    private var sock: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var nextTry = 0L
    private var lastGood: String? = null
    var deviceName: String = ""
        private set

    fun permitted() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isOpen() = sock != null

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
            false
        }
    }

    /** Bonded devices most likely to be the auxlink first: the one that
     *  worked last, those already known to offer the service, those named
     *  like it (name or the alias given here), then every other one - the
     *  phone may still have the Pi under an old name and an old service
     *  list, and the Pi itself says no quickly if it isn't the one. */
    private fun candidates(): List<BluetoothDevice> {
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return emptyList()
        if (!bt.isEnabled) return emptyList()
        val bonded = bt.bondedDevices?.toList() ?: return emptyList()
        val target = ParcelUuid(APP_UUID)
        fun named(d: BluetoothDevice): Boolean {
            val names = listOfNotNull(d.name, if (Build.VERSION.SDK_INT >= 30) d.alias else null)
            return names.any { it.contains("auxlink", true) || it.contains("teslabridge", true) }
        }
        return (bonded.filter { it.address == lastGood } +
            bonded.filter { d -> d.uuids?.contains(target) == true } +
            bonded.filter { named(it) } +
            bonded).distinctBy { it.address }
    }

    @Synchronized
    fun open(): Boolean {
        if (!permitted() || SystemClock.elapsedRealtime() < nextTry) return false
        nextTry = SystemClock.elapsedRealtime() + RETRY_MS
        for (dev in candidates()) {
            try {
                val s = dev.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                sock = s
                out = s.outputStream
                deviceName = dev.name ?: dev.address
                lastGood = dev.address
                Log.i(TAG, "Connected to $deviceName over Bluetooth")
                return true
            } catch (e: Exception) {
                Log.i(TAG, "${dev.name}: ${e.message}")
            }
        }
        return false
    }

    @Synchronized
    fun close() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
    }
}
