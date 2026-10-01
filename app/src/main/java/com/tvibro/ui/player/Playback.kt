package com.tvibro.ui.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The single playback engine that lives across the full screen player and the mini player in the
 * guide panel.
 *
 * Both screens are activities, so the picture has to be moved rather than copied: the video view
 * of the engine is re-parented into whichever container currently hosts it, and the engine itself
 * keeps running. Leaving the full screen player therefore does not interrupt the stream - the same
 * decoder stays on the same position, only its window becomes small.
 *
 * The engine is created by [PlayerActivity] (it needs the playback preferences) and is handed to
 * this holder, which only tracks who owns it and clears the callbacks of an activity that goes
 * away.
 */
object Playback {

    const val HOST_NONE = 0
    const val HOST_PLAYER = 1
    const val HOST_GUIDE = 2

    private var engine: PlaybackEngine? = null
    private var host = HOST_NONE
    private var channelId = 0L
    private var channelName = ""

    private val main = Handler(Looper.getMainLooper())

    /**
     * How many seconds a channel has to play before it reaches the history, read live so a change
     * in the settings takes effect at once, and where such a channel is written. Both are supplied
     * by the application, which owns the preferences and the database: this holder knows about
     * neither and stays a plain counter.
     */
    internal var historyDelaySec: () -> Int = { 30 }
    internal var historyWrite: ((channelId: Long, watchedMs: Long) -> Unit)? = null

    /**
     * How long the current channel has really been playing, and whether it has already earned a
     * place in the history.
     *
     * The history is meant to hold what the user watched, and a channel that is on screen for two
     * seconds while the remote is still searching is not that. The counter therefore lives here
     * rather than in a window: the stream outlives both of them, so the seconds it played are the
     * same whether the picture was in the player or in the strip of the guide. Only real playback
     * counts, so a stream that is buffering or parked in the background never pushes a channel
     * over the line the user set.
     */
    private var watchedMs = 0L
    private var historyRecorded = false
    private var watchCounting = false

    private val watchTick = object : Runnable {
        override fun run() {
            val current = engine ?: run {
                watchCounting = false
                return
            }
            if (runCatching { current.isPlaying() }.getOrDefault(false)) {
                watchedMs += 1000L
                recordHistoryWhenDue()
            }
            main.postDelayed(this, 1000L)
        }
    }

    private fun ensureWatchCounting() {
        if (watchCounting || engine == null) return
        watchCounting = true
        main.postDelayed(watchTick, 1000L)
    }

    private fun stopWatchCounting() {
        main.removeCallbacks(watchTick)
        watchCounting = false
    }

    /** Milliseconds the current channel has actually been playing. */
    fun watchedMs(): Long = watchedMs

    private fun recordHistoryWhenDue() {
        if (historyRecorded || channelId <= 0L) return
        val delayMs = historyDelaySec().coerceAtLeast(0) * 1000L
        if (watchedMs < delayMs) return
        historyRecorded = true
        writeHistory()
    }

    /**
     * Stores the time the channel reached. It only ever updates a channel that is in the history
     * already, so a stream the user dropped early is still not written at all.
     */
    private fun writeHistory() {
        if (!historyRecorded || channelId <= 0L) return
        val write = historyWrite ?: return
        val id = channelId
        val watched = watchedMs
        write(id, watched)
    }

    /** Closes the current channel: a recorded one keeps its place with the final time, the rest stays out. */
    private fun closeWatch() {
        writeHistory()
        stopWatchCounting()
        watchedMs = 0L
        historyRecorded = false
    }

    fun engine(): PlaybackEngine? = engine

    /** True while a stream is loaded, no matter which screen is showing it. */
    fun active(): Boolean = engine != null

    /** True when the picture currently sits in the guide panel rather than in the player window. */
    fun inGuide(): Boolean = host == HOST_GUIDE

    fun channelId(): Long = channelId

    fun channelName(): String = channelName

    fun positionMs(): Long = runCatching { engine?.positionMs() ?: 0L }.getOrDefault(0L)

    /**
     * Takes ownership of a freshly created engine and shows its picture in [container]. The
* callbacks stay empty here: whoever wires the engine points them at itself.
     */
    fun claim(created: PlaybackEngine, container: ViewGroup) {
        claim(created, container, HOST_PLAYER)
    }

    /**
     * Same as [claim], but for the mini player: the guide panel is the host from the very first
     * frame, so nothing ever pretends the full screen player owns the stream.
     */
    fun claimInGuide(created: PlaybackEngine, container: ViewGroup) {
        claim(created, container, HOST_GUIDE)
    }

    private fun claim(created: PlaybackEngine, container: ViewGroup, host: Int) {
        detach(created)
        engine = created
        this.host = host
        attach(created, container)
        ensureWatchCounting()
    }

    /**
     * Hands the picture to [container] without touching the stream. Used when the guide panel adopts
     * the engine of the player that is going away.
     */
    fun attachTo(container: ViewGroup) {
        val current = engine ?: return
        attach(current, container)
        host = HOST_GUIDE
        ensureWatchCounting()
    }

    /** Puts the picture back into the player window, resuming the ownership of the full screen. */
    fun attachToPlayer(container: ViewGroup) {
        val current = engine ?: return
        attach(current, container)
        host = HOST_PLAYER
        ensureWatchCounting()
    }

    /**
     * Names the channel the stream belongs to. A different channel starts a fresh watch: the seconds
     * counted so far belong to the one the user just left. Taking the same channel over again - the
     * guide strip handing the picture back to the player window - keeps them, because the stream has
     * been running all along.
     */
    fun markChannel(id: Long, name: String) {
        if (channelId != id) closeWatch()
        channelId = id
        channelName = name
        ensureWatchCounting()
    }

    fun play() {
        runCatching { engine?.play() }
    }

    fun pause() {
        runCatching { engine?.pause() }
    }

    /**
     * The activity that is going away must not be referenced by the callbacks any more, otherwise
     * the engine keeps a destroyed activity alive while the guide is on screen.
     */
    fun detachCallbacks() {
        val current = engine ?: return
        current.onReady = null
        current.onBuffering = null
        current.onError = null
        current.onEnd = null
        current.onVideoSize = null
    }

    /** Stops the stream and frees the decoder. Called when playback is really over. */
    fun stop() {
        // The watch is closed even when there is no engine left: it keeps the counter from carrying
        // a channel of a stream that is long gone into the next one.
        closeWatch()
        val current = engine ?: return
        detach(current)
        current.onReady = null
        current.onBuffering = null
        current.onError = null
        current.onEnd = null
        current.onVideoSize = null
        runCatching { current.release() }
        engine = null
        host = HOST_NONE
        channelId = 0L
        channelName = ""
    }

    /** Drops the engine after it was released by its owner, so a dead one is never reused. */
    fun forget(used: PlaybackEngine) {
        if (engine !== used) return
        closeWatch()
        detachCallbacks()
        engine = null
        host = HOST_NONE
        channelId = 0L
        channelName = ""
    }

    private fun attach(current: PlaybackEngine, container: ViewGroup) {
        val view = current.surfaceView
        if (view.parent === container) return
        detach(current)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun detach(current: PlaybackEngine) {
        (current.surfaceView.parent as? ViewGroup)?.removeView(current.surfaceView)
    }
}
