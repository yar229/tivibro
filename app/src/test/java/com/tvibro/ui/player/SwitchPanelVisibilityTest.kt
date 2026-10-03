package com.tvibro.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip along the bottom of the picture is the one thing covering the video during a channel
 * change, so the switch that removes it has to be the user's and has to keep the panel when it is
 * left on, which is what the panel did before the setting existed.
 */
class SwitchPanelVisibilityTest {

    @Test
    fun `shows the strip on a switch while the setting is on`() {
        assertTrue(switchPanelAllowed(onChannelSwitch = true, infoAtBottom = true))
    }

    @Test
    fun `leaves the picture alone when the setting is off`() {
        assertFalse(switchPanelAllowed(onChannelSwitch = true, infoAtBottom = false))
    }

    @Test
    fun `a tap still gets the panel with the setting off`() {
        // Nothing is loading, so the setting about what covers the picture during a switch has
        // nothing to say here: the user asked for this panel by pressing.
        assertTrue(switchPanelAllowed(onChannelSwitch = false, infoAtBottom = false))
        assertTrue(switchPanelAllowed(onChannelSwitch = false, infoAtBottom = true))
    }
}