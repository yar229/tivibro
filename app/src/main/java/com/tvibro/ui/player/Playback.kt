package com.tvibro.ui.player

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
        detach(created)
        engine = created
        host = HOST_PLAYER
        attach(created, container)
    }

    /**
     * Hands the picture to [container] without touching the stream. Used when the guide panel adopts
     * the engine of the player that is going away.
     */
    fun attachTo(container: ViewGroup) {
        val current = engine ?: return
        attach(current, container)
        host = HOST_GUIDE
    }

    /** Puts the picture back into the player window, resuming the ownership of the full screen. */
    fun attachToPlayer(container: ViewGroup) {
        val current = engine ?: return
        attach(current, container)
        host = HOST_PLAYER
    }

    fun markChannel(id: Long, name: String) {
        channelId = id
        channelName = name
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
