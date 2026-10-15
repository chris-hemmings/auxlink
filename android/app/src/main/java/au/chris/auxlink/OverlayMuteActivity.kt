package au.chris.auxlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Choose how long to mute a mirrored app's alerts on the head unit.
 * 30 minutes / next boot stay on the car; Always tells the phone to stop
 * forwarding that app permanently.
 *
 * Full-screen dim so it shows above the notification shade (same as Reply).
 */
class OverlayMuteActivity : Activity() {
    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_APP = "app"
        private const val EXTRA_SHADE_ID = "shade_id"

        fun start(ctx: Context, pkg: String, app: String, shadeId: Int = -1) {
            SystemUi.collapseShade(ctx)
            ctx.startActivity(
                Intent(ctx, OverlayMuteActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    .putExtra(EXTRA_PKG, pkg)
                    .putExtra(EXTRA_APP, app)
                    .putExtra(EXTRA_SHADE_ID, shadeId)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemUi.collapseShade(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val app = intent.getStringExtra(EXTRA_APP).orEmpty().ifBlank { pkg.ifBlank { "this app" } }
        val shadeId = intent.getIntExtra(EXTRA_SHADE_ID, -1)
        if (pkg.isEmpty()) { finish(); return }

        fun pill(label: String, detail: String, click: () -> Unit): LinearLayout {
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 15f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xFF3C4043.toInt()); cornerRadius = 16 * dp
                }
                setPadding(px(14), 0, px(14), 0)
                minHeight = 0; minimumHeight = px(48)
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(48)
                )
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, px(10))
                addView(b)
                addView(TextView(this@OverlayMuteActivity).apply {
                    text = detail; setTextColor(0xFF9AA0A6.toInt()); textSize = 12f
                    setPadding(px(4), px(4), px(4), 0)
                })
            }
        }

        fun done(msg: String) {
            if (shadeId > 0) {
                try {
                    getSystemService(android.app.NotificationManager::class.java).cancel(shadeId)
                } catch (_: Exception) {}
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            finish()
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(12))
            background = GradientDrawable().apply {
                setColor(0xF2222124.toInt()); cornerRadius = 20 * dp
            }
            elevation = 12 * dp
            addView(TextView(this@OverlayMuteActivity).apply {
                text = "Mute $app"; setTextColor(Color.WHITE); textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@OverlayMuteActivity).apply {
                text = "How long should alerts from this app stay quiet?"
                setTextColor(0xFFBDC1C6.toInt()); textSize = 13f
                setPadding(0, px(6), 0, px(14))
            })
            addView(pill("30 minutes", "Only on this head unit") {
                CarNotifMute.mute30Minutes(this@OverlayMuteActivity, pkg)
                done("Muted $app for 30 minutes on this device")
            })
            addView(pill("Until next boot", "Only on this head unit; clears after reboot") {
                CarNotifMute.muteUntilNextBoot(this@OverlayMuteActivity, pkg)
                done("Muted $app until next boot on this device")
            })
            addView(pill("Always", "Tells the phone to stop forwarding this app") {
                val svc = NowPlayingService.instance
                if (svc == null || !NowPlayingService.connected()) {
                    Toast.makeText(this@OverlayMuteActivity,
                        "Not connected to the Pi — can't mute on the phone.",
                        Toast.LENGTH_LONG).show()
                    return@pill
                }
                CarNotifMute.muteUntilNextBoot(this@OverlayMuteActivity, pkg)
                svc.sendNotifMute(pkg, app)
                done("Asked the phone to always mute $app")
            })
            addView(Button(this@OverlayMuteActivity).apply {
                text = "Cancel"; isAllCaps = false; textSize = 14f
                setTextColor(0xFFBDC1C6.toInt())
                setBackgroundColor(0x00000000)
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(44)
                ).apply { topMargin = px(4) }
            })
        }
        val w = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(px(440))
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(card, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
        }
        setContentView(root)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window.decorView.post { SystemUi.collapseShade(this) }
        window.decorView.postDelayed({ SystemUi.collapseShade(this) }, 200)
    }

    override fun onResume() {
        super.onResume()
        SystemUi.collapseShade(this)
    }
}
