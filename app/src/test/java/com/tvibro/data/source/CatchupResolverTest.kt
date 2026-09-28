package com.tvibro.data.source

import com.tvibro.data.model.Channel
import com.tvibro.data.model.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CatchupResolverTest {

    private val start = 1_700_000_000_000L
    private val stop = start + 3_600_000L

    private fun channel(template: String) = Channel(
        streamId = "ctv",
        name = "CTV",
        catchupSource = template,
    )

    private fun program() = Program(
        channelId = 1L,
        title = "Evening News",
        start = start,
        stop = stop,
    )

    private fun expected(timeMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(java.util.Date(timeMs))
    }

    @Test
    fun `empty catchup source returns empty url`() {
        assertEquals("", CatchupResolver.resolve(channel(""), program()))
    }

    @Test
    fun `brace placeholders are expanded`() {
        val out = CatchupResolver.resolve(channel("http://a/c?start={start}&end={end}"), program())
        assertEquals("http://a/c?start=${expected(start)}&end=${expected(stop)}", out)
    }

    @Test
    fun `dollar placeholders are expanded`() {
        val out = CatchupResolver.resolve(channel("http://a/c?start=\$start&end=\$end"), program())
        assertEquals("http://a/c?start=${expected(start)}&end=${expected(stop)}", out)
    }

    @Test
    fun `duration is in seconds`() {
        val out = CatchupResolver.resolve(channel("http://a/c?d={duration}"), program())
        assertEquals("http://a/c?d=3600", out)
    }

    @Test
    fun `zero length programme produces zero duration`() {
        val p = Program(channelId = 1L, title = "x", start = start, stop = start)
        val out = CatchupResolver.resolve(channel("http://a/c?d={duration}"), p)
        assertEquals("http://a/c?d=0", out)
    }

    @Test
    fun `utc tokens use the same formatted timestamps`() {
        val out = CatchupResolver.resolve(channel("http://a/c?s={start_utc}&e={end_utc}"), program())
        assertEquals("http://a/c?s=${expected(start)}&e=${expected(stop)}", out)
    }

    @Test
    fun `timestamp token is an alias for start`() {
        val out = CatchupResolver.resolve(channel("http://a/c?t={timestamp}"), program())
        assertEquals("http://a/c?t=${expected(start)}", out)
    }

    @Test
    fun `template without placeholders is returned unchanged`() {
        assertEquals("http://a/c?static=1", CatchupResolver.resolve(channel("http://a/c?static=1"), program()))
    }

    @Test
    fun `no placeholders remain after resolution`() {
        val out = CatchupResolver.resolve(
            channel("http://a/c?s={start}&e={end}&d={duration}&u={utc}&ts={timestamp}"),
            program(),
        )
        assertFalse(out.contains("{"))
        assertFalse(out.contains("\$"))
        assertTrue(out.startsWith("http://a/c?"))
    }
}
