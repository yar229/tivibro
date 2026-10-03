package com.tvibro.ui.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ties the screen to the frame rate of what is playing, so a 25 or 50 fps channel is not resampled
 * against a panel that refreshes at neither: every frame then lands for an unequal length of time
 * and the picture judders.
 *
 * A screen must not be moved on every channel, because a mode switch costs a visible blackout. The
 * rate is therefore only touched when the stream calls for another one and the panel can really
 * deliver it, and the panel is handed back when playback ends.
 */
class FrameRateSync(private val activity: Activity) {

    private val window: Window get() = activity.window

    private var requested: Float? = null
    private var modeId = 0

    /** What the last [apply] asked of the panel, so the badge can show the decision. */
    var lastRequest: FrameRateRequest? = null
        private set

    /**
     * Shows a stream of [fps] at the pace of the panel. [surface] is what the picture is drawn to,
     * and a surface is the only thing that can be told about the rate: the VLC engine draws into a
     * TextureView, which owns no Surface, so there the request has to travel through the window.
     */
    fun apply(fps: Float?, surface: Surface?) {
        val wanted = fps?.takeIf { it > 1f }
        if (wanted == null) {
            restore()
            return
        }
        // The rate of a stream arrives late and is then refreshed on every badge update, so this is
        // called over and over with the same number and must not answer with a mode switch each time.
        if (requested != null && abs(requested!! - wanted) < 0.5f) return
        val modes = modes() ?: return
        val rate = chooseRefreshRate(modes.map { it.refreshRate }, wanted)
        if (rate == null) {
            // Nothing the panel offers divides this stream evenly, so there is no switch worth making.
            lastRequest = null
            requested = wanted
            Log.i(TAG, "no panel rate divides ${wanted}Hz, panel left at ${panelRateNow()}Hz")
            return
        }
        requested = wanted
        val viaSurface = surface != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            askSurface(surface, rate)
        if (!viaSurface) askWindow(modes, rate)
        lastRequest = FrameRateRequest(wanted, rate, viaSurface)
        Log.i(
            TAG,
            "stream ${wanted}Hz -> panel ${rate}Hz via " +
                if (viaSurface) "surface, panel now ${panelRateNow()}Hz" else "window mode, panel now ${panelRateNow()}Hz",
        )
    }

    /** Puts the panel back to whatever the system chose, which is what leaving the player means. */
    fun restore() {
        requested = null
        lastRequest = null
        if (modeId == 0) return
        modeId = 0
        Log.i(TAG, "panel handed back to the system")
        runCatching { window.attributes = window.attributes.apply { preferredDisplayModeId = 0 } }
    }

    /** The rate the panel is refreshing at right now, which is what the badge reports. */
    fun panelRateNow(): Float? = runCatching { display()?.refreshRate }.getOrNull()

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else activity.windowManager.defaultDisplay

    @Suppress("DEPRECATION")
    private fun modes(): List<Display.Mode>? =
        runCatching { display()?.supportedModes?.toList() }.getOrNull()

    /**
     * Android 11 learns the rate of the content from the surface itself, is free to pick the
     * closest mode on its own, and switches only where it can do so without a break in the picture.
     */
    private fun askSurface(surface: Surface, rate: Float): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
                rate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
        } else {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        true
    }.getOrDefault(false)

    /** Without a surface the window has to name the mode itself, and that really does blank the screen. */
    private fun askWindow(modes: List<Display.Mode>, rate: Float) {
        val mode = modes.firstOrNull { abs(it.refreshRate - rate) < 0.5f } ?: return
        if (mode.modeId == modeId) return
        modeId = mode.modeId
        runCatching {
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        }
    }
}

/**
 * The rate the panel should run at for a stream of [fps]. Only a whole multiple of the frame rate
 * holds every frame for exactly the same length of time, so anything else is left alone rather than
 * bought with a mode switch that would not have removed the judder anyway. Among the multiples the
 * one nearest the source wins, which keeps a 50 fps channel off a 100 Hz panel it has no use for.
 */
internal fun chooseRefreshRate(rates: List<Float>, fps: Float): Float? {
    if (fps <= 1f || rates.isEmpty()) return null
    return rates
        .filter { abs(it / fps - (it / fps).roundToInt()) < 0.02f }
        .minByOrNull { abs(it - fps) }
}

private const val TAG = "TvibroAfR"

/** One decision [FrameRateSync] made: a stream of [sourceFps] asked the panel for [panelRate]. */
data class FrameRateRequest(val sourceFps: Float, val panelRate: Float, val viaSurface: Boolean)

/**
 * The FPS badge, which carries both rates once there is something to say: the stream tells what is
 * arriving, the panel tells what it is being shown at, and where those two differ that gap is the
 * judder. How the request reached the panel is left to the log, because the badge has room for two
 * numbers and nothing else. [request] is null when the feature is off or no panel rate fits, and the
 * badge then stays as it was, since a bare source rate is all it could honestly claim.
 */
internal fun fpsBadgeLabel(sourceFps: Float, request: FrameRateRequest?): String {
    val source = sourceFps.roundToInt()
    if (request == null) return "$source FPS"
    return "$source→${request.panelRate.roundToInt()} FPS"
}