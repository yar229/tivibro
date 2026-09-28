package com.tvibro.ui.player

import androidx.media3.exoplayer.LoadControl
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Media3 throws IllegalArgumentException inside setBufferDurationsMs when the values coming from
 * settings are inconsistent, which used to kill the whole process on channel start.
 */
class ExoLoadControlTest {

    private fun build(value: Int): LoadControl = ExoEngine.buildLoadControl(value)

    @Test
    fun `builds for every buffer value reachable from settings`() {
        val values = listOf(0, 1, 1000, 1500, 2499, 2500, 5000, 10000, 20000, 40000, 60000, 999999, -1)
        values.forEach { assertNotNull("buffer $it must be sanitized", build(it)) }
    }

    @Test
    fun `keeps the default usable`() {
        assertNotNull(build(5000))
    }
}
