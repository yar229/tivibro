package com.tvibro.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rate a channel runs at is only known once it is decoded, so the panel has to be matched to it
 * afterwards. Picking a rate that does not divide the frame rate evenly buys a visible mode switch
 * and leaves the judder exactly where it was, which is what these cases hold the choice down to.
 */
class FrameRateSyncTest {

    /** The panel modes a 1080p TV box reports: 23.976, 24, 29.97, 30, 50, 59.94 and 60. */
    private val tvRates = listOf(23.976025f, 24f, 29.97003f, 30f, 50f, 59.94006f, 60f)

    @Test
    fun `takes the panel rate that holds every frame for the same time`() {
        assertEquals(50f, chooseRefreshRate(tvRates, 25f)!!, 0.01f)
        assertEquals(50f, chooseRefreshRate(tvRates, 50f)!!, 0.01f)
        assertEquals(30f, chooseRefreshRate(tvRates, 30f)!!, 0.01f)
        assertEquals(24f, chooseRefreshRate(tvRates, 24f)!!, 0.01f)
        assertEquals(60f, chooseRefreshRate(tvRates, 60f)!!, 0.01f)
    }

    @Test
    fun `keeps the source rate itself instead of a multiple of it`() {
        // Both 30 and 60 hold a 30 fps frame for a whole refresh, but doubling the panel rate for it
        // is work with nothing to show: nearest to the source wins.
        assertEquals(30f, chooseRefreshRate(listOf(30f, 60f), 30f)!!, 0.01f)
    }

    @Test
    fun `leaves the panel alone when no rate divides the stream evenly`() {
        // A 60/90/120 Hz tablet cannot show 25 or 50 fps content at its own pace, and no mode switch
        // would fix that, so nothing is asked of it.
        assertNull(chooseRefreshRate(listOf(60f, 90f, 120f), 25f))
        assertNull(chooseRefreshRate(listOf(60f, 90f, 120f), 50f))
    }

    @Test
    fun `refuses rates that are not a frame rate at all`() {
        assertNull(chooseRefreshRate(emptyList(), 25f))
        assertNull(chooseRefreshRate(tvRates, 0f))
        assertNull(chooseRefreshRate(tvRates, -25f))
        // A source that reports 1 fps is a broken report, not something to move a panel for.
        assertNull(chooseRefreshRate(tvRates, 1f))
    }

    @Test
    fun `accepts a rate the source and the panel round to`() {
        // 59.94 against a 60 Hz panel is the same rhythm to within half a frame per second.
        assertEquals(59.94006f, chooseRefreshRate(tvRates, 59.94f)!!, 0.01f)
    }

    @Test
    fun `badge shows both rates once the panel was moved`() {
        // Stream first, panel second: the arrow reads as "arriving at 25, shown at 50".
        assertEquals("25→50 FPS", fpsBadgeLabel(25f, FrameRateRequest(25f, 50f, viaSurface = true)))
        // How the request reached the panel is not the badge's business, so both routes read alike.
        assertEquals("25→50 FPS", fpsBadgeLabel(25f, FrameRateRequest(25f, 50f, viaSurface = false)))
    }

    @Test
    fun `badge stays plain when there is no decision to report`() {
        // Feature off, or nothing on the panel divides the stream: the source rate is all we know.
        assertEquals("25 FPS", fpsBadgeLabel(25f, null))
    }

    @Test
    fun `badge rounds a fractional rate to the number people say out loud`() {
        assertEquals(
            "50→50 FPS",
            fpsBadgeLabel(49.97f, FrameRateRequest(49.97f, 50f, viaSurface = true)),
        )
        assertEquals(
            "60→60 FPS",
            fpsBadgeLabel(59.94f, FrameRateRequest(59.94f, 59.94006f, viaSurface = true)),
        )
    }
}