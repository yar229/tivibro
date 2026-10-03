package com.tvibro.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The offset row and its dialog speak the same "h:mm", so what one writes the other has to read. */
class FmtOffsetTest {

    @Test
    fun `writes an offset the way a clock reads it`() {
        assertEquals("0:00", Fmt.formatOffsetMinutes(0))
        assertEquals("0:05", Fmt.formatOffsetMinutes(5))
        assertEquals("1:00", Fmt.formatOffsetMinutes(60))
        assertEquals("2:15", Fmt.formatOffsetMinutes(135))
        assertEquals("-1:30", Fmt.formatOffsetMinutes(-90))
        assertEquals("-12:00", Fmt.formatOffsetMinutes(-720))
    }

    @Test
    fun `reads back what it writes`() {
        for (minutes in listOf(0, 5, 59, 60, 135, 720, -1, -90, -720)) {
            assertEquals(
                "round trip failed for $minutes",
                minutes,
                Fmt.parseOffsetMinutes(Fmt.formatOffsetMinutes(minutes)),
            )
        }
    }

    @Test
    fun `reads a sign, spaces and a bare number of hours`() {
        assertEquals(90, Fmt.parseOffsetMinutes("1:30"))
        assertEquals(-90, Fmt.parseOffsetMinutes("-1:30"))
        assertEquals(90, Fmt.parseOffsetMinutes("+1:30"))
        assertEquals(90, Fmt.parseOffsetMinutes(" 1 : 30 "))
        assertEquals(120, Fmt.parseOffsetMinutes("2"))
        assertEquals(-120, Fmt.parseOffsetMinutes("-2"))
        assertEquals(0, Fmt.parseOffsetMinutes("0:00"))
    }

    @Test
    fun `refuses a half-typed offset instead of taking it for zero`() {
        assertNull(Fmt.parseOffsetMinutes(""))
        assertNull(Fmt.parseOffsetMinutes("   "))
        assertNull(Fmt.parseOffsetMinutes("-"))
        assertNull(Fmt.parseOffsetMinutes(":30"))
        assertNull(Fmt.parseOffsetMinutes("1:"))
        assertNull(Fmt.parseOffsetMinutes("abc"))
        assertNull(Fmt.parseOffsetMinutes("1:60"))
        assertNull(Fmt.parseOffsetMinutes("1:-30"))
        assertNull(Fmt.parseOffsetMinutes("1:2:3"))
    }
}