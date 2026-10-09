package au.chris.auxlink

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(grant)
        })
        // Bluetooth music source: the app reaches the Pi over Bluetooth.
        if (Build.VERSION.SDK_INT >= 31 && !BtLink(this).permitted())
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }

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
            appendLine("Data link open: " + if (NowPlayingService.linkOpen) "yes - " + NowPlayingService.linkKind else "no")
            if (!BtLink(this@MainActivity).permitted())
                appendLine("Bluetooth (for a Bluetooth music source): NOT allowed - reopen the app to allow")
            appendLine()
            appendLine("Last sent:")
            appendLine(NowPlayingService.lastSent)
        }
    }
}
