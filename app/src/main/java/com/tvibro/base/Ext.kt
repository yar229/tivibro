package com.tvibro.base

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updateLayoutParams
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {

    fun time(timeMs: Long): String = timeFormat.format(Date(timeMs))

    fun time(timeMs: Long, locale: Locale): String =
        SimpleDateFormat("HH:mm", locale).format(Date(timeMs))

    fun date(timeMs: Long, locale: Locale): String =
        SimpleDateFormat("dd.MM.yyyy", locale).format(Date(timeMs))

    fun dateTime(timeMs: Long, locale: Locale): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", locale).format(Date(timeMs))

    fun dayName(timeMs: Long, locale: Locale): String =
        SimpleDateFormat("EEEE", locale).format(Date(timeMs))

    fun relativeDay(timeMs: Long, locale: Locale): String {
        val today = startOfDay(System.currentTimeMillis())
        val target = startOfDay(timeMs)
        val days = ((target - today) / 86_400_000L).toInt()
        return when (days) {
            0, 1, -1 -> date(timeMs, locale)
            in 2..6 -> dayName(timeMs, locale)
            else -> date(timeMs, locale)
        }
    }

    fun startOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun duration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun clockDuration(ms: Long): String {
        if (ms <= 0) return ""
        val h = ms / 3600_000L
        val m = (ms % 3600_000L) / 60_000L
        return if (h > 0) String.format(Locale.US, "%d:%02d", h, m)
        else String.format(Locale.US, "%d:%02d", m, (ms % 60_000L) / 1000L)
    }

    fun timeRange(start: Long, stop: Long, locale: Locale): String =
        time(start, locale) + " - " + time(stop, locale)

    fun elapsedText(start: Long, now: Long): String {
        val diff = now - start
        if (diff < 0) return ""
        val h = diff / 3600_000L
        val m = (diff % 3600_000L) / 60_000L
        return when {
            h > 0 && m > 0 -> String.format(Locale.US, "%d:%02d", h, m)
            h > 0 -> String.format(Locale.US, "%dh", h)
            else -> String.format(Locale.US, "%d min", m)
        }
    }

    fun remainingText(stop: Long, now: Long): String {
        val diff = stop - now
        if (diff <= 0) return ""
        val h = diff / 3600_000L
        val m = (diff % 3600_000L) / 60_000L
        return when {
            h > 0 -> String.format(Locale.US, "-%d:%02d", h, m)
            else -> String.format(Locale.US, "-%d min", m)
        }
    }

    fun percent(start: Long, stop: Long, now: Long): Int {
        val total = stop - start
        if (total <= 0) return 0
        val done = now - start
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }

    fun episodeLabel(season: String, episode: String): String = when {
        season.isNotEmpty() && episode.isNotEmpty() -> "S${season.padStart(2, '0')}E${episode.padStart(2, '0')}"
        season.isNotEmpty() -> "S${season.padStart(2, '0')}"
        else -> ""
    }

    /**
     * Quality badge for the channel switch overlay: the real video height wins, the channel
     * name is only used as a fallback for live streams that do not report a size yet.
     */
    fun qualityLabel(channelName: String, videoHeight: Int = 0): String? {
        if (videoHeight > 0) {
            return when {
                videoHeight >= 4320 -> "8K"
                videoHeight >= 2000 -> "4K"
                videoHeight >= 1000 -> "FHD"
                videoHeight >= 700 -> "HD"
                else -> "SD"
            }
        }
        val upper = channelName.uppercase(Locale.US)
        return when {
            upper.contains("8K") || upper.contains("4320") -> "8K"
            upper.contains("4K") || upper.contains("UHD") || upper.contains("2160") -> "4K"
            upper.contains("FHD") || upper.contains("1080") -> "FHD"
            upper.contains("HD") || upper.contains("HEVC") || upper.contains("H265") -> "HD"
            upper.contains("SD") || upper.contains("480") -> "SD"
            else -> null
        }
    }

    /** Collapses whitespace and trims the EPG description to a few lines worth of text. */
    fun shortDescription(text: String?, maxChars: Int = 220): String {
        if (text.isNullOrBlank()) return ""
        val clean = text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return clean
        val cut = clean.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut).trimEnd(' ', ',', '.', '-', ':') + "…"
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
}

fun Context.toast(message: String, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun Context.toastRes(id: Int) = toast(getString(id))

fun ViewGroup.inflate(layoutId: Int): View =
    android.view.LayoutInflater.from(context).inflate(layoutId, this, false)

fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun View.setPaddingDp(left: Int, top: Int, right: Int, bottom: Int) {
    val d = resources.displayMetrics.density
    setPadding((left * d).toInt(), (top * d).toInt(), (right * d).toInt(), (bottom * d).toInt())
}

fun View.marginDp(left: Int, top: Int, right: Int, bottom: Int) {
    val d = resources.displayMetrics.density
    updateLayoutParams<ViewGroup.MarginLayoutParams> {
        setMargins((left * d).toInt(), (top * d).toInt(), (right * d).toInt(), (bottom * d).toInt())
    }
}

fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}

fun View.onClick(action: () -> Unit) {
    setOnClickListener { action() }
}

fun View.focusOnShow() {
    post { requestFocus() }
}

fun Context.isTv(): Boolean {
    val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
    return uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
}

fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        toast(e.message ?: "Cannot open")
    }
}

fun View.applyFocusScale(enabled: Boolean = true, scale: Float = 1.06f) {
    isFocusable = enabled
    isFocusableInTouchMode = false
    stateListAnimator = null
    val target = if (enabled && hasFocus()) scale else 1f
    scaleX = target
    scaleY = target
}

fun View.onFocusChange(action: (Boolean) -> Unit) {
    setOnFocusChangeListener { _, hasFocus -> action(hasFocus) }
}

fun View.withFocusScale(scale: Float = 1.06f) {
    onFocusChange { focused ->
        val target = if (focused) scale else 1f
        animate().scaleX(target).scaleY(target).setDuration(140).start()
    }
}

fun applyLetterSpacing(view: TextView, spacing: Float) {
    view.letterSpacing = spacing
}

fun ViewGroup.removeAllViews() {
    while (childCount > 0) removeViewAt(0)
}
