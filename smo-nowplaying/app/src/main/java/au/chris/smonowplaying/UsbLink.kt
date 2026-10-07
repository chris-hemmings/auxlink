package au.chris.smonowplaying

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Writes lines to the XIAO's vendor bulk-OUT endpoint. The XIAO forwards the
 * bytes out of its UART to the Pi. Opens lazily and reopens after any failure.
 */
class UsbLink(private val context: Context) {
    companion object {
        const val VID = 0x1209
        const val PID = 0x0001
        const val ACTION_PERMISSION = "au.chris.smonowplaying.USB_PERMISSION"
        private const val TAG = "SmoUsbLink"
    }

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var ep: UsbEndpoint? = null
    private var asked = false

    fun findDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }

    fun isOpen() = conn != null

    @Synchronized
    fun send(line: String): Boolean {
        if (conn == null && !open()) return false
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64, bytes.size - off)
            val chunk = bytes.copyOfRange(off, off + n)
            val r = conn!!.bulkTransfer(ep, chunk, n, 250)
            if (r < 0) {
                Log.w(TAG, "bulkTransfer failed, reopening")
                close()
                return false
            }
            off += n
        }
        return true
    }

    @Synchronized
    fun open(): Boolean {
        val dev = findDevice() ?: return false
        if (!usb.hasPermission(dev)) {
            if (!asked) {
                asked = true
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_PERMISSION).setPackage(context.packageName), flags
                )
                usb.requestPermission(dev, pi)
            }
            return false
        }
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
            for (e in 0 until itf.endpointCount) {
                val end = itf.getEndpoint(e)
                if (end.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    end.direction == UsbConstants.USB_DIR_OUT
                ) {
                    val c = usb.openDevice(dev) ?: return false
                    if (!c.claimInterface(itf, true)) {
                        c.close(); return false
                    }
                    conn = c; iface = itf; ep = end
                    Log.i(TAG, "Opened XIAO data interface")
                    return true
                }
            }
        }
        Log.w(TAG, "XIAO found but no vendor bulk-OUT interface (old firmware?)")
        return false
    }

    @Synchronized
    fun close() {
        try { iface?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        conn = null; iface = null; ep = null
    }

    fun resetPermissionPrompt() { asked = false }
}
