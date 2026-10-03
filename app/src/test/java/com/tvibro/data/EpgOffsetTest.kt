package com.tvibro.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guide keeps the times the source reported and moves them on the way out, so the shift has to
 * survive a round trip: what a query window walks on must come back to the same wall clock it was
 * asked for, whichever way the user has pushed the guide.
 */
class EpgOffsetTest {

    @Test
    fun `minutes become milliseconds`() {
        assertEquals(0L, EpgOffset.ms(0))
        assertEquals(90 * 60_000L, EpgOffset.ms(90))
        assertEquals(-90 * 60_000L, EpgOffset.ms(-90))
    }

    @Test
    fun `a window walked on the stored scale comes back where it started`() {
        val offset = EpgOffset.ms(-90)
        val from = 1_700_000_000_000L
        val to = from + 12 * 3600_000L

        val storedFrom = EpgOffset.toStored(from, offset)
        val storedTo = EpgOffset.toStored(to, offset)

        assertEquals(from, EpgOffset.toReal(storedFrom, offset))
        assertEquals(to, EpgOffset.toReal(storedTo, offset))
    }

    @Test
    fun `a row moves the way the setting says`() {
        val stored = 1_700_000_000_000L
        // An hour and a half back: the guide reads earlier than the source's own times.
        assertEquals(stored - 90 * 60_000L, EpgOffset.toReal(stored, EpgOffset.ms(-90)))
        assertEquals(stored + 135 * 60_000L, EpgOffset.toReal(stored, EpgOffset.ms(135)))
    }

    @Test
    fun `no offset leaves the times alone`() {
        val stored = 1_700_000_000_000L
        assertEquals(stored, EpgOffset.toStored(stored, 0))
        assertEquals(stored, EpgOffset.toReal(stored, 0))
    }
}