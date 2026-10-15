package au.chris.auxlink

import android.content.Context
import android.view.Gravity
import android.view.WindowManager

/**
 * Saved on-screen position for floating alert banners (and SMS pop-ups).
 * SharedPreferences persist across reboot. Drag a banner to move it, or pick
 * a preset in Tools on the head unit.
 */
object BannerPos {
    private const val PREFS = "auxlink"
    private const val KEY_X = "banner_x"   // -1 = horizontally centred
    private const val KEY_Y = "banner_y"   // -1 = default (~140 dp from top)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultY(ctx: Context): Int =
        (140 * ctx.resources.displayMetrics.density).toInt()

    /** Apply saved x/y onto overlay layout params. [width] is the banner width. */
    fun apply(ctx: Context, p: WindowManager.LayoutParams, width: Int) {
        val dm = ctx.resources.displayMetrics
        val pref = prefs(ctx)
        val y = pref.getInt(KEY_Y, -1).let { if (it < 0) defaultY(ctx) else it }
            .coerceIn(0, (dm.heightPixels - 80).coerceAtLeast(0))
        val x = pref.getInt(KEY_X, -1)
        if (x < 0) {
            p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            p.x = 0
            p.y = y
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            val maxX = (dm.widthPixels - width.coerceAtLeast(1)).coerceAtLeast(0)
            p.x = x.coerceIn(0, maxX)
            p.y = y
        }
    }

    fun save(ctx: Context, x: Int, y: Int) {
        val dm = ctx.resources.displayMetrics
        prefs(ctx).edit()
            .putInt(KEY_X, x.coerceIn(0, dm.widthPixels))
            .putInt(KEY_Y, y.coerceIn(0, dm.heightPixels))
            .apply()
    }

    /** Preset vertical slots; keeps horizontal position if already set. */
    fun setPreset(ctx: Context, preset: String) {
        val h = ctx.resources.displayMetrics.heightPixels
        val y = when (preset) {
            "top" -> (h * 0.08f).toInt()
            "upper" -> (h * 0.22f).toInt()
            "middle" -> (h * 0.40f).toInt()
            "lower" -> (h * 0.62f).toInt()
            else -> defaultY(ctx)
        }
        prefs(ctx).edit().putInt(KEY_Y, y).apply()
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY_X).remove(KEY_Y).apply()
    }

    fun summary(ctx: Context): String {
        val p = prefs(ctx)
        val y = p.getInt(KEY_Y, -1)
        val x = p.getInt(KEY_X, -1)
        if (y < 0 && x < 0) return "Default (top)"
        val h = ctx.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val slot = when {
            y < 0 -> "custom"
            y < h * 0.15f -> "Top"
            y < h * 0.30f -> "Upper"
            y < h * 0.50f -> "Middle"
            else -> "Lower"
        }
        return if (x < 0) slot else "$slot (dragged)"
    }
}
