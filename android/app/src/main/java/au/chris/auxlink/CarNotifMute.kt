package au.chris.auxlink

import android.content.Context
import android.os.SystemClock

/**
 * Head-unit (car) mutes for mirrored notifications. Independent of the phone's
 * "Apps to forward" mute list.
 *
 * - 30 minutes / until next boot: local only (car stops showing that app)
 * - Always: handled by the caller (relay to the phone to mute permanently)
 */
object CarNotifMute {
    private const val PREFS = "auxlink"
    private const val KEY_UNTIL = "car_mute_until"       // "pkg=epochMs,pkg=…"
    private const val KEY_BOOT = "car_mute_boot_pkgs"    // StringSet of pkgs
    private const val KEY_BOOT_ID = "car_mute_boot_id"

    /** Approximate boot identity: changes after a reboot. */
    private fun bootId(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Clear "until next boot" mutes when the device has rebooted. */
    fun onBoot(ctx: Context) {
        val p = prefs(ctx)
        val id = bootId()
        val prev = p.getLong(KEY_BOOT_ID, 0L)
        if (prev != 0L && prev != id) {
            p.edit().remove(KEY_BOOT).putLong(KEY_BOOT_ID, id).apply()
        } else if (prev == 0L) {
            p.edit().putLong(KEY_BOOT_ID, id).apply()
        }
        pruneExpired(ctx)
    }

    fun isMuted(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        onBoot(ctx)
        val p = prefs(ctx)
        if (p.getStringSet(KEY_BOOT, emptySet())?.contains(pkg) == true) return true
        val until = untilMap(p)[pkg] ?: return false
        if (until > System.currentTimeMillis()) return true
        // expired
        muteUntil(ctx, pkg, 0L)
        return false
    }

    fun mute30Minutes(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        muteUntil(ctx, pkg, System.currentTimeMillis() + 30L * 60L * 1000L)
    }

    fun muteUntilNextBoot(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        onBoot(ctx)
        val p = prefs(ctx)
        val set = p.getStringSet(KEY_BOOT, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(pkg)
        p.edit().putStringSet(KEY_BOOT, set).apply()
    }

    private fun muteUntil(ctx: Context, pkg: String, untilMs: Long) {
        val p = prefs(ctx)
        val map = untilMap(p).toMutableMap()
        if (untilMs <= 0L) map.remove(pkg) else map[pkg] = untilMs
        val enc = map.entries.joinToString(",") { "${it.key}=${it.value}" }
        p.edit().putString(KEY_UNTIL, enc).apply()
    }

    private fun untilMap(p: android.content.SharedPreferences): Map<String, Long> {
        val raw = p.getString(KEY_UNTIL, "").orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (part in raw.split(',')) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val pkg = part.substring(0, i)
            val t = part.substring(i + 1).toLongOrNull() ?: continue
            out[pkg] = t
        }
        return out
    }

    private fun pruneExpired(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val map = untilMap(p).filterValues { it > now }
        val enc = map.entries.joinToString(",") { "${it.key}=${it.value}" }
        p.edit().putString(KEY_UNTIL, enc).apply()
    }
}
