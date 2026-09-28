package com.tvibro.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FmtQualityTest {

    @Test
    fun `prefers the real video height`() {
        assertEquals("8K", Fmt.qualityLabel("Канал 1", 4320))
        assertEquals("4K", Fmt.qualityLabel("Канал 1", 2160))
        assertEquals("FHD", Fmt.qualityLabel("Канал 1", 1080))
        assertEquals("HD", Fmt.qualityLabel("Канал 1", 720))
        assertEquals("SD", Fmt.qualityLabel("Канал 1", 480))
    }

    @Test
    fun `falls back to the channel name when the size is unknown`() {
        assertEquals("4K", Fmt.qualityLabel("Матч ТВ UHD"))
        assertEquals("4K", Fmt.qualityLabel("Channel 2160p"))
        assertEquals("FHD", Fmt.qualityLabel("Канал 1080"))
        assertEquals("HD", Fmt.qualityLabel("Sky Sports HD"))
        assertEquals("SD", Fmt.qualityLabel("Локальный SD"))
    }

    @Test
    fun `returns null for an unknown channel`() {
        assertNull(Fmt.qualityLabel("Первый канал", 0))
    }

    @Test
    fun `shortens the description and collapses whitespace`() {
        assertEquals("", Fmt.shortDescription(null))
        assertEquals("", Fmt.shortDescription("   "))
        assertEquals("Матч: обзор игры", Fmt.shortDescription("Матч:\n  обзор   игры "))
    }

    @Test
    fun `truncates a long description on a word boundary`() {
        val text = "word ".repeat(100)
        val short = Fmt.shortDescription(text, 50)
        assertEquals(true, short.length <= 51)
        assertEquals(true, short.endsWith("…"))
        assertEquals(false, short.contains("  "))
    }
}
