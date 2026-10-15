package au.chris.auxlink

import android.content.Context
import android.content.Intent
import android.os.Build

/** Best-effort: collapse the notification shade so Activities can show on top. */
object SystemUi {
    fun collapseShade(ctx: Context) {
        try {
            @Suppress("DEPRECATION")
            ctx.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {}
        try {
            @Suppress("WrongConstant")
            val sb = ctx.getSystemService("statusbar") ?: return
            val cls = Class.forName("android.app.StatusBarManager")
            val method = try {
                cls.getMethod("collapsePanels")
            } catch (_: NoSuchMethodException) {
                // Some builds only expose collapse().
                cls.getMethod("collapse")
            }
            method.invoke(sb)
        } catch (_: Exception) {
            // Expected on locked-down builds — caller should still show a
            // full-screen Activity so Reply is visible above the shade.
        }
        if (Build.VERSION.SDK_INT >= 31) {
            // Extra nudge on Android 12+: close system dialogs again shortly.
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        @Suppress("DEPRECATION")
                        ctx.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                    } catch (_: Exception) {}
                }, 80)
            } catch (_: Exception) {}
        }
    }
}
