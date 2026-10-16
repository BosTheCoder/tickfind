package io.github.bosthecoder.tickfind

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

const val TEXT = 0xFFF2F2F4.toInt()
const val DIM = 0x99F2F2F4.toInt()
const val FAINT = 0x66F2F2F4.toInt()
const val ACCENT = 0xFF8FB0FF.toInt()
const val BLUE = 0xFF4772FA.toInt()
const val LATE = 0xFFFF7A70.toInt()
const val CARD = 0xF7161619.toInt()
const val FIELD = 0x1AFFFFFF

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float) = GradientDrawable().apply { cornerRadius = radius; setColor(color) }

/** A press highlight for rows and icon buttons. */
fun Context.ripple(radius: Int = dp(10)) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(-1, radius.toFloat()))

fun Context.text(s: CharSequence, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.icon(res: Int, size: Int = 22, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(res); imageTintList = ColorStateList.valueOf(DIM)
    val pad = dp(9); setPadding(pad, pad, pad, pad)
    background = ripple(dp(20)); isClickable = true
    layoutParams = android.widget.LinearLayout.LayoutParams(dp(size + 18), dp(size + 18))
    setOnClickListener { onClick() }
}

fun priorityColor(p: Int) = when (p) { 5 -> 0xFFD52B24.toInt(); 3 -> 0xFFFAA80C.toInt(); 1 -> BLUE; else -> 0x40FFFFFF }

/** [text] with the matched words in bold and tinted. */
fun marked(text: String, terms: List<String>): CharSequence {
    if (terms.isEmpty()) return text
    val s = SpannableString(text)
    for (r in Search.marks(text, terms)) {
        s.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(ACCENT), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return s
}

/** "Today", "Tomorrow", "Friday", "14 Oct"; and whether it's overdue. */
fun dueText(due: Long?): Pair<String, Boolean>? {
    val d = Instant.ofEpochMilli(due ?: return null).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val days = d.toEpochDay() - today.toEpochDay()
    val text = when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days == -1L -> "Yesterday"
        days in 2..6 -> d.format(DateTimeFormatter.ofPattern("EEEE", Locale.UK))
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", Locale.UK))
    }
    return text to (days < 0)
}

fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

/** A ScrollView that grows with its content up to [maxH] pixels. */
class MaxScroll(ctx: Context) : ScrollView(ctx) {
    var maxH = Int.MAX_VALUE
    override fun onMeasure(w: Int, h: Int) =
        super.onMeasure(w, if (maxH == Int.MAX_VALUE) h else View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
}

/** True if release tag [tag] ("v1.8.0") is a later version than [current] ("1.7.1"), compared number by number. */
fun isNewer(tag: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val a = parts(tag); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
