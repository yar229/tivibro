package com.tvibro.ui.player

import android.util.Log
import android.view.ViewGroup
import com.tvibro.TvBroApp
import com.tvibro.data.Prefs

/**
 * Builds a [PlaybackEngine] the one way the app knows how, so the full screen player and the mini
 * player in the guide panel cannot drift apart in their decoder settings.
 *
 * Both engines are built on the application context: the picture outlives the window it was started
 * from, so the engine must never keep a destroyed activity alive.
 */
object PlaybackEngineFactory {

    private const val TAG = "TvibroPlayer"

    fun create(prefs: Prefs, holder: ViewGroup, preferVlc: Boolean = prefs.engine == "vlc"): PlaybackEngine {
        if (preferVlc) {
            val vlc = runCatching { VlcEngine(TvBroApp.get(), holder) }.getOrNull()
            if (vlc != null) return vlc
            Log.e(TAG, "VLC init failed, falling back to ExoPlayer")
            // A half built VLC engine may already have put a view into the container.
            holder.removeAllViews()
        }
        val exo = ExoEngine(
            TvBroApp.get(), holder,
            bufferMs = prefs.bufferSizeMs,
            tunneled = prefs.tunneledPlayback,
        )
        exo.setPassthrough(prefs.audioPassthrough)
        exo.setHardwareDecoder(prefs.videoDecoder != "software")
        return exo
    }
}