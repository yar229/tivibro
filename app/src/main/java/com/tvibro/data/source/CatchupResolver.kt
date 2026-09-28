package com.tvibro.data.source

import com.tvibro.data.model.Channel
import com.tvibro.data.model.Program
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Builds catch-up stream URLs from the channel catch-up template.
 * Supported placeholders: $start, $end, $utc, $duration, $start_utc, $end_utc
 * and the brace style {start}/{end}/{utc}/{duration}.
 */
object CatchupResolver {

    fun resolve(channel: Channel, program: Program): String {
        if (channel.catchupSource.isBlank()) return ""
        val durationSeconds = ((program.stop - program.start) / 1000L).coerceAtLeast(0L)
        var url = channel.catchupSource
        url = url.replace("{duration}", durationSeconds.toString())
        url = url.replace("{utc}", format(program.start))
        url = url.replace("{timestamp}", format(program.start))
        url = url.replace("{start_utc}", format(program.start))
        url = url.replace("{end_utc}", format(program.stop))
        url = url.replace("{start}", format(program.start))
        url = url.replace("{end}", format(program.stop))
        url = url.replace("\$duration", durationSeconds.toString())
        url = url.replace("\$utc", format(program.start))
        url = url.replace("\$timestamp", format(program.start))
        url = url.replace("\$start_utc", format(program.start))
        url = url.replace("\$end_utc", format(program.stop))
        url = url.replace("\$start", format(program.start))
        url = url.replace("\$end", format(program.stop))
        return url
    }

    private fun format(timeMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(java.util.Date(timeMs))
    }
}
