package com.tvibro.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media3 has no passthrough switch in this version, so the option works by aiming the audio track
 * choice. These cases pin down which way it aims it, because aiming it one way only would leave the
 * bitstream preferred after the user had asked for it not to be.
 */
class PassthroughTest {

    @Test
    fun `prefers the bitstream the far end can decode when passthrough is on`() {
        val mimes = audioMimePreference(passthrough = true, softwareAudio = false)
        assertTrue(mimes.contains(MimeTypes.AUDIO_AC3))
        assertTrue(mimes.contains(MimeTypes.AUDIO_E_AC3))
        assertTrue(mimes.contains(MimeTypes.AUDIO_DTS))
        assertTrue(mimes.contains(MimeTypes.AUDIO_TRUEHD))
        // Nothing the box decodes itself belongs in the list, or it would win over the bitstream.
        assertFalse(mimes.contains(MimeTypes.AUDIO_AAC))
        assertFalse(mimes.contains(MimeTypes.AUDIO_RAW))
    }

    @Test
    fun `prefers what the box can decode when passthrough is off`() {
        val mimes = audioMimePreference(passthrough = false, softwareAudio = false)
        assertTrue(mimes.contains(MimeTypes.AUDIO_AAC))
        assertTrue(mimes.contains(MimeTypes.AUDIO_MPEG))
        assertFalse(mimes.contains(MimeTypes.AUDIO_AC3))
        assertFalse(mimes.contains(MimeTypes.AUDIO_DTS))
    }

    @Test
    fun `a software decoder takes the option out of the question`() {
        // FFmpeg turns the stream into PCM, and PCM has nothing left to hand to the TV, so asking
        // for a bitstream there would only cost the channel count FFmpeg was chosen for.
        assertEquals(
            audioMimePreference(passthrough = false, softwareAudio = false),
            audioMimePreference(passthrough = true, softwareAudio = true),
        )
    }

    @Test
    fun `recognises a bitstream format for the report`() {
        assertTrue(isBitstreamAudio(MimeTypes.AUDIO_AC3))
        assertTrue(isBitstreamAudio(MimeTypes.AUDIO_TRUEHD))
        assertTrue(isBitstreamAudio(MimeTypes.AUDIO_DTS_HD))
        // The report has to survive a container that names the same thing with different casing.
        assertTrue(isBitstreamAudio(MimeTypes.AUDIO_AC3.uppercase()))
    }

    @Test
    fun `does not mistake decoded audio for a bitstream`() {
        assertFalse(isBitstreamAudio(MimeTypes.AUDIO_AAC))
        assertFalse(isBitstreamAudio(MimeTypes.AUDIO_MPEG))
        assertFalse(isBitstreamAudio(MimeTypes.AUDIO_RAW))
        assertFalse(isBitstreamAudio(null))
    }
}