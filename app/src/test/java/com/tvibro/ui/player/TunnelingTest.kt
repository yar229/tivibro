package com.tvibro.ui.player

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelingTest {

    @Test
    fun `asks for the tunnel when the setting is on and the device is new enough`() {
        assertTrue(tunnelingWanted(true, Build.VERSION_CODES.M))
        assertTrue(tunnelingWanted(true, Build.VERSION_CODES.TIRAMISU))
        assertTrue(tunnelingWanted(true, 36))
    }

    @Test
    fun `leaves the tunnel off when the setting is off`() {
        assertFalse(tunnelingWanted(false, Build.VERSION_CODES.M))
        assertFalse(tunnelingWanted(false, 36))
    }

    @Test
    fun `refuses to ask below Marshmallow, where there is no tunnel to join`() {
        // The tunnel rides on MediaCodec frame callbacks, which are Lollipop at best and
        // Marshmallow in practice, so an older device is not asked rather than asked in vain.
        assertFalse(tunnelingWanted(true, Build.VERSION_CODES.LOLLIPOP))
        assertFalse(tunnelingWanted(true, Build.VERSION_CODES.LOLLIPOP_MR1))
        assertFalse(tunnelingWanted(true, Build.VERSION_CODES.KITKAT))
    }
}