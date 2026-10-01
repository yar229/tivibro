package com.tvibro.ui.player

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A channel may only enter the history once it has really been playing for the delay the user set,
 * so these tests drive the watch counter of [Playback] through the main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackHistoryTest {

    private lateinit var context: Context
    private val writes = mutableListOf<Pair<Long, Long>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        writes.clear()
        Playback.historyDelaySec = { 5 }
        Playback.historyWrite = { id, watched -> writes += id to watched }
        Playback.stop()
    }

    @After
    fun tearDown() {
        Playback.stop()
    }

    @Test
    fun `channel reaches the history only after the configured delay`() {
        start(1L)

        play(3)
        assertTrue(writes.isEmpty())

        play(3)
        assertEquals(listOf(1L to 5000L), writes)
    }

    @Test
    fun `channel the user dropped before the delay stays out of the history`() {
        Playback.historyDelaySec = { 30 }
        start(1L)

        play(5)
        // Switching to another channel is what closes the watch of the first one.
        Playback.markChannel(2L, "B")

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a stream that is not playing does not count towards the delay`() {
        val engine = start(1L)
        engine.playing = false

        play(10)

        assertEquals(0L, Playback.watchedMs())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `taking the same channel over keeps the seconds already watched`() {
        val engine = start(1L)
        play(3)

        // The guide strip hands the running picture back to the player window.
        Playback.attachToPlayer(FrameLayout(context))
        play(3)

        assertEquals(listOf(1L to 5000L), writes)
        assertTrue(engine.playing)
    }

    @Test
    fun `a channel already in the history is updated with the time it reached`() {
        start(1L)
        play(6)
        assertEquals(listOf(1L to 5000L), writes)

        play(2)
        Playback.stop()

        assertEquals(listOf(1L to 5000L, 1L to 8000L), writes)
    }

    @Test
    fun `a zero delay puts the channel in the history straight away`() {
        Playback.historyDelaySec = { 0 }
        start(1L)

        play(1)

        assertEquals(listOf(1L to 1000L), writes)
    }

    /** Marks the channel and puts a fake engine behind it, the way a window starting playback would. */
    private fun start(channelId: Long): FakeEngine {
        val engine = FakeEngine(context)
        Playback.markChannel(channelId, "Channel $channelId")
        Playback.claimInGuide(engine, FrameLayout(context))
        return engine
    }

    /** Lets the watch counter run for the given number of seconds. */
    private fun play(seconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private class FakeEngine(context: Context) : PlaybackEngine {
        override val surfaceView: View = View(context)
        var playing = true

        override fun prepare(url: String, userAgent: String, startPositionMs: Long) = Unit
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) = Unit
        override fun positionMs(): Long = 0L
        override fun durationMs(): Long = 0L
        override fun isPlaying(): Boolean = playing
        override fun setVolume(volume: Float) = Unit
        override fun aspectMode(): Int = 0
        override fun cyclesAspectMode() = Unit
        override fun aspectModeLabel(context: Context): String = ""
        override fun videoSize(): Pair<Int, Int>? = null
        override fun videoFps(): Float? = null
        override fun audioChannels(): Int? = null
        override fun release() = Unit

        override var onReady: (() -> Unit)? = null
        override var onBuffering: ((Boolean) -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        override var onEnd: (() -> Unit)? = null
        override var onVideoSize: ((Int, Int) -> Unit)? = null
    }
}