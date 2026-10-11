package au.chris.auxlink

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** The app's look (dark, for car screens), shared by its screens. */
class Ui(val ctx: Context) {
    val dp = ctx.resources.displayMetrics.density
    val bg = 0xFF0E1013.toInt()
    val cardBg = 0xFF181B20.toInt()
    val line = 0xFF262A31.toInt()
    val text1 = 0xFFF2F4F7.toInt()
    val text2 = 0xFF9AA3AF.toInt()
    val accent = 0xFF3B82F6.toInt()
    val good = 0xFF22C55E.toInt()
    val warn = 0xFFF59E0B.toInt()
    val bad = 0xFFEF4444.toInt()

    fun px(v: Int) = (v * dp).toInt()

    /** A tablet-sized screen: lay screens out side by side instead of stacked. */
    val wide = ctx.resources.configuration.screenWidthDp >= 840

    fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * dp
    }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun text(s: String, size: Float, color: Int = text1, bold: Boolean = false, lines: Int = 0) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        }

    fun button(label: String, color: Int, textColor: Int = Color.WHITE, click: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        background = round(color, 22f)
        stateListAnimator = null
        setPadding(px(18), 0, px(18), 0)
        minWidth = px(64); minimumWidth = px(64)
        setOnClickListener { click() }
    }

    /** A round button with a centred icon (vector drawable). */
    fun iconButton(icon: Int, color: Int, sizeDp: Int = 44, click: () -> Unit) =
        android.widget.ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val pad = (sizeDp * 0.24f * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = oval(color)
            isFocusable = false
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)).apply { marginStart = px(10) }
        }

    /** A round initial for a contact. */
    fun avatar(name: String, sizeDp: Int = 44): TextView {
        val palette = intArrayOf(0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(),
            0xFF059669.toInt(), 0xFFD97706.toInt(), 0xFF0891B2.toInt())
        val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        return TextView(ctx).apply {
            text = letter; textSize = sizeDp * 0.4f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = oval(palette[(name.hashCode() and 0x7fffffff) % palette.size])
            layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)).apply { marginEnd = px(14) }
        }
    }

    /** Placeholder text in the middle of an empty list. */
    fun empty(title: String, body: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(px(32), px(48), px(32), px(48))
        addView(text(title, 18f, text1, true).apply { gravity = Gravity.CENTER })
        addView(text(body, 14f, text2).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
    }

    /** Segmented control (e.g. Contacts | Recents | Keypad). */
    fun segments(labels: List<String>, onPick: (Int) -> Unit): Pair<LinearLayout, (Int) -> Unit> {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(cardBg, 22f)
            setPadding(px(4), px(4), px(4), px(4))
        }
        val views = labels.map { l ->
            text(l, 15f, text2, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(10), 0, px(10))
            }
        }
        fun select(i: Int) {
            views.forEachIndexed { k, v ->
                v.setTextColor(if (k == i) Color.WHITE else text2)
                v.background = if (k == i) round(accent, 18f) else null
            }
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener { select(i); onPick(i) }
            bar.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar to ::select
    }

    fun matchWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(top); bottomMargin = px(bottom)
        }

    fun fill() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    fun gone(v: View, hide: Boolean) { v.visibility = if (hide) View.GONE else View.VISIBLE }
}
