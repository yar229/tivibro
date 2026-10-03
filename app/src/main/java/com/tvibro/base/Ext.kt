package com.tvibro.base

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updateLayoutParams
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

    /** Day number and month, e.g. "12 Oct" / "12 окт": the second line of the scale day. */
    fun dayDate(timeMs: Long, locale: Locale): String =
        SimpleDateFormat("d MMM", locale).format(Date(timeMs))

    /**
     * ISO 8601 in the zone this device is on, e.g. "2026-10-02T11:00:00+03:00". The Web API hands its
     * times out in this shape, so a caller can place a programme in a calendar without first having
     * to work out which zone the player sits in. DateTimeFormatter is immutable, which matters here:
     * these answers are built on the server's worker threads rather than on one UI thread.
     *
     * The fraction is cut off. A programme time lands on a whole second while a watched-at time
     * carries milliseconds, and printing both as they come would make the same field look like two
     * different shapes to whoever parses it.
     */
    fun isoTime(timeMs: Long): String =
        Instant.ofEpochMilli(timeMs)
            .truncatedTo(ChronoUnit.SECONDS)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

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

    /** A signed shift of the guide in "h:mm", e.g. "0:00", "-1:30", "2:05". */
    fun formatOffsetMinutes(minutes: Int): String {
        val abs = if (minutes < 0) -minutes else minutes
        val sign = if (minutes < 0) "-" else ""
        return String.format(Locale.US, "%s%d:%02d", sign, abs / 60, abs % 60)
    }

    /**
     * Reads what [formatOffsetMinutes] writes, e.g. "-1:30" for an hour and a half back, and gives
     * back the minutes the setting holds. A bare number is taken as hours, which is how the row
     * this replaced took its value. Returns null for anything else, so a half-typed hour does not
     * quietly turn into a shift of zero.
     */
    fun parseOffsetMinutes(text: String): Int? {
        val body = text.trim().let { if (it.startsWith("-") || it.startsWith("+")) it.substring(1) else it }
        if (body.isEmpty()) return null
        val parts = body.split(':')
        if (parts.size > 2) return null
        val hours = parts[0].trim().toIntOrNull() ?: return null
        val minutes = if (parts.size == 2) parts[1].trim().toIntOrNull() ?: return null else 0
        if (hours < 0 || minutes < 0 || minutes > 59) return null
        val total = hours * 60 + minutes
        return if (text.trim().startsWith("-")) -total else total
    }

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
                videoHeight >= 1400 -> "2K"
                videoHeight >= 1000 -> "FHD"
                videoHeight >= 700 -> "HD"
                else -> "SD"
            }
        }
        val upper = channelName.uppercase(Locale.US)
        return when {
            upper.contains("8K") || upper.contains("4320") -> "8K"
            upper.contains("4K") || upper.contains("UHD") || upper.contains("2160") -> "4K"
            upper.contains("2K") || upper.contains("QHD") || upper.contains("1440") -> "2K"
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
    val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(this, message, duration).show()
    } else {
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, message, duration).show() }
    }
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

/**
 * How many pixels one sp covers on this display.
 *
 * `DisplayMetrics.scaledDensity` is deprecated as of Android 14 and, on top of that, says nothing
 * about the non-linear font scaling of that release. Asking [TypedValue] for a single sp accounts
 * for whatever the system actually does, and a size that is captured in sp can be divided by this
 * without drifting.
 */
fun Context.pxPerSp(): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

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
