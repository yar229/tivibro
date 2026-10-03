package com.tvibro.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The depth the user asked for is drawn in one place, and both sides of the guide have to agree on
 * it: a programme is not fetched and then thrown away, and it is not kept past what was asked for.
 */
class PastCutoffTest {

    @Test
    fun `nothing kept draws the line at now`() {
        assertEquals(1_700_000_000_000L, pastCutoff(1_700_000_000_000L, 0))
    }

    @Test
    fun `a day back is a day back`() {
        assertEquals(1_700_000_000_000L - 86_400_000L, pastCutoff(1_700_000_000_000L, 1))
    }

    @Test
    fun `a week back is seven days`() {
        assertEquals(1_700_000_000_000L - 7 * 86_400_000L, pastCutoff(1_700_000_000_000L, 7))
    }

    @Test
    fun `a whole month is thirty days`() {
        assertEquals(1_700_000_000_000L - 30 * 86_400_000L, pastCutoff(1_700_000_000_000L, 30))
    }
}