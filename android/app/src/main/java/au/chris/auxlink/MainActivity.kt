package au.chris.auxlink

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var note: TextView
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { update(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 18f }
        val grant = Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        note = TextView(this).apply { textSize = 16f }
        val battery = Button(this).apply {
            text = "Let AuxLink run in the background"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        val allowUsb = Button(this).apply {
            text = "Allow USB"
            setOnClickListener { UsbLink(this@MainActivity).askPermission() }
        }
        // Same as pressing play in the car while it is silent: the Pi
        // restarts the car's stream once.
        val fix = Button(this).apply {
            text = "Fix sound"
            setOnClickListener { send("fix", "Asked the Pi to restart the music stream to the car") }
        }
        // The setup page, without the web page's own way in: the Pi turns on
        // its setup Wi-Fi for 15 minutes.
        val setup = Button(this).apply {
            text = "Setup: turn on the setup Wi-Fi"
            setOnClickListener {
                send("setup", "Setup Wi-Fi is turning on (up to 30 s).\n" +
                    "Connect this device's Wi-Fi to \"AuxLink-setup\" (password auxlink-setup, " +
                    "unless you changed them), then tap Open setup page. If the Pi is on your " +
                    "home Wi-Fi instead, it opens at http://auxlink.local")
            }
        }
        val open = Button(this).apply {
            text = "Open setup page"
            setOnClickListener { openSetup() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(grant)
            addView(battery)
            addView(allowUsb)
            addView(fix)
            addView(setup)
            addView(open)
            addView(note)
        })
        // Bluetooth music source: the app reaches the Pi over Bluetooth.
        // Microphone: only so that the USB plug-in prompt offers "Always"
        // (see the manifest); nothing is ever recorded.
        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !BtLink(this).permitted())
            want += Manifest.permission.BLUETOOTH_CONNECT
        if (!micAllowed()) want += Manifest.permission.RECORD_AUDIO
        // The permanent "AuxLink connected" notification (Android 13+ asks).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            want += Manifest.permission.POST_NOTIFICATIONS
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    /** The setup page wherever the Pi is: its setup Wi-Fi (10.42.0.1), else
     *  the same network as this device (auxlink.local, looked up here and
     *  opened by address - browsers often can't find .local names). */
    private fun openSetup() {
        note.text = "Looking for the Pi..."
        Thread {
            fun answers(host: String) = try {
                Socket().use { it.connect(InetSocketAddress(host, 80), 1500) }; true
            } catch (e: Exception) { false }
            val url = if (answers("10.42.0.1")) "http://10.42.0.1/" else {
                val ip = try { InetAddress.getByName("auxlink.local").hostAddress } catch (e: Exception) { null }
                if (ip != null && answers(ip)) "http://$ip/" else null
            }
            main.post {
                note.text = if (url != null) "Opening $url" else
                    "Pi not found. Tap Setup, join the AuxLink-setup Wi-Fi, then try again. " +
                    "(On home Wi-Fi: http://auxlink.local, or the Pi's address from your router.)"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url ?: "http://auxlink.local/")))
                } catch (e: Exception) {
                    Toast.makeText(this, "No web browser on this device", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun send(cmd: String, ok: String) {
        val svc = NowPlayingService.instance
        if (svc == null) {
            note.text = "Not connected: allow notification access first."
            return
        }
        note.text = "Sending..."
        svc.command(cmd) { sent ->
            note.text = if (sent) ok else
                "Couldn't reach the Pi: plug in the XIAO's USB, or (Bluetooth music source) " +
                "make sure this device is connected to AuxLink-music."
        }
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }

    private fun unrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun micAllowed() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val me = ComponentName(this, NowPlayingService::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    private fun update() {
        val xiao = UsbLink(this).findDevice() != null
        status.text = buildString {
            appendLine("Notification access: " + if (hasAccess()) "granted" else "NOT granted - tap below")
            appendLine("XIAO plugged in: " + if (xiao) "yes" else "no")
            appendLine("Background running: " + if (unrestricted()) "allowed" else
                "battery saving may stop it - tap Let AuxLink run in the background")
            if (!micAllowed())
                appendLine("Microphone: NOT allowed - reopen the app and allow it (only so USB can be set to Always; nothing is recorded)")
            if (xiao && !UsbLink(this@MainActivity).permitted())
                appendLine("USB access: NOT allowed - tap Allow USB (or re-plug the XIAO and tick Always)")
            appendLine("Data link open: " + if (NowPlayingService.linkOpen) "yes - " + NowPlayingService.linkKind else "no")
            if (!BtLink(this@MainActivity).permitted())
                appendLine("Bluetooth (for a Bluetooth music source): NOT allowed - reopen the app to allow")
            if (!NowPlayingService.linkOpen && !xiao) {
                appendLine("Bluetooth link to the Pi, last try:")
                appendLine(BtLink.lastTry)
            }
            appendLine()
            appendLine("Last sent:")
            appendLine(NowPlayingService.lastSent)
        }
    }
}
