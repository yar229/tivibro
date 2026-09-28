package com.tvibro.ui.player

import android.content.Context
import android.net.Uri
import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvibro.R
import com.tvibro.data.MAX_BUFFER_MS
import com.tvibro.data.MIN_BUFFER_MS
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

interface PlaybackEngine {
    val surfaceView: View
    fun prepare(url: String, userAgent: String, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun positionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun setVolume(volume: Float)
    fun aspectMode(): Int
    fun cyclesAspectMode()
    fun aspectModeLabel(context: Context): String
    fun videoSize(): Pair<Int, Int>?
    fun videoFps(): Float?
    fun audioChannels(): Int?
    fun release()

    var onReady: (() -> Unit)?
    var onBuffering: ((Boolean) -> Unit)?
    var onError: ((String) -> Unit)?
    var onEnd: (() -> Unit)?
    var onVideoSize: ((Int, Int) -> Unit)?
}

@OptIn(UnstableApi::class)
class ExoEngine(
    context: Context,
    parent: ViewGroup,
    bufferMs: Int = 5000,
    tunneled: Boolean = false,
    passthrough: Boolean = false,
) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val trackSelector = DefaultTrackSelector(appContext)
    private val renderersFactory = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
    private val loadControl = buildLoadControl(bufferMs)
    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setAudioAttributes(AudioAttributes.DEFAULT, false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    companion object {
        /**
         * Media3 throws [IllegalArgumentException] unless minBuffer >= bufferForPlayback
         * and maxBuffer >= minBuffer, so every value coming from settings is sanitized.
         */
        fun buildLoadControl(bufferMs: Int): LoadControl {
            val minBuffer = bufferMs.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)
            val maxBuffer = (minBuffer * 5).coerceAtLeast(minBuffer + 10_000)
            val bufferForPlayback = 1500.coerceAtMost(minBuffer)
            val bufferForPlaybackAfterRebuffer = 2500.coerceAtMost(minBuffer)
            return DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, bufferForPlayback, bufferForPlaybackAfterRebuffer)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    private val playerView = PlayerView(appContext).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(android.graphics.Color.BLACK)
    }

    override val surfaceView: View get() = playerView

    var tunneledEnabled = tunneled
    var passthroughEnabled = passthrough
    var hardwareDecoderEnabled = true

    init {        parent.addView(
            playerView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        playerView.player = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        onBuffering?.invoke(false)
                        onReady?.invoke()
                    }
                    androidx.media3.common.Player.STATE_BUFFERING -> onBuffering?.invoke(true)
                    androidx.media3.common.Player.STATE_ENDED -> {
                        onBuffering?.invoke(false)
                        onEnd?.invoke()
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onError?.invoke(error.errorCodeName)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onVideoSize?.invoke(videoSize.width, videoSize.height)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                Log.d("ExoEngine", "tracks changed: ${audioGroups.size} audio group(s), ${tracks.groups.size} total")
                audioGroups.forEachIndexed { index, group ->
                    val formats = (0 until group.mediaTrackGroup.length).map { i ->
                        group.mediaTrackGroup.getFormat(i).sampleMimeType ?: "unknown"
                    }
                    Log.d("ExoEngine", "audio group #$index selected=${group.isSelected}, supported=${group.isSupported}, formats=$formats")
                }
                // ExoPlayer does not support some legacy IPTV audio codecs (e.g. MPEG-1 Layer II).
                // Signal this upstream so the app can fall back to VLC.
                if (audioGroups.isNotEmpty() && audioGroups.none { it.isSelected }) {
                    onError?.invoke("Audio codec not supported")
                }
            }
        })
    }

    fun enableTunneled(enable: Boolean) {
        tunneledEnabled = enable
    }

    fun setPassthrough(enable: Boolean) {
        passthroughEnabled = enable
    }

    fun setHardwareDecoder(hardware: Boolean) {
        hardwareDecoderEnabled = hardware
    }

    override fun prepare(url: String, userAgent: String, startPositionMs: Long) {
        val mediaItem = MediaItem.Builder().setUri(url).build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent.ifBlank { "TiViBro" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
        player.playWhenReady = true
        if (startPositionMs > 0) player.seekTo(startPositionMs)
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        runCatching { player.seekTo(positionMs) }
    }

    override fun positionMs(): Long = runCatching { player.currentPosition }.getOrDefault(0L)

    override fun durationMs(): Long = runCatching { player.duration }.getOrDefault(0L)

    override fun isPlaying(): Boolean = player.isPlaying

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    fun currentFormatLabel(): String? {
        val size = videoSize() ?: return null
        return "${size.first}x${size.second}"
    }

    fun player(): ExoPlayer = player

    override fun aspectMode(): Int = playerView.resizeMode

    override fun cyclesAspectMode() {
        playerView.resizeMode = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun aspectModeLabel(context: Context): String = when (playerView.resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> context.getString(R.string.aspect_fit)
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> context.getString(R.string.aspect_fill)
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> context.getString(R.string.aspect_zoom)
        else -> context.getString(R.string.aspect_ratio)
    }

    override fun videoSize(): Pair<Int, Int>? = runCatching {
        val width = player.videoSize.width
        val height = player.videoSize.height
        if (width <= 0 || height <= 0) null else width to height
    }.getOrNull()

    override fun videoFps(): Float? = runCatching {
        player.videoFormat?.frameRate?.takeIf { it > 0 }
    }.getOrNull()

    override fun audioChannels(): Int? = runCatching {
        player.audioFormat?.channelCount?.takeIf { it > 0 }
    }.getOrNull()

    override fun release() {
        runCatching { playerView.player = null }
        runCatching { player.release() }
    }

    override var onReady: (() -> Unit)? = null
    override var onBuffering: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onEnd: (() -> Unit)? = null
    override var onVideoSize: ((Int, Int) -> Unit)? = null
}

class VlcEngine(context: Context, private val parent: ViewGroup) : PlaybackEngine {

    // Use TextureView for VLC: it does not recreate the surface on layout changes
    // and works correctly with MediaPlayer.setVideoScale().
    private val libVLC: LibVLC = LibVLC(context, arrayListOf("--no-video-title-show"))
    private val textureView = TextureView(context)
    private val mediaPlayer: MediaPlayer = MediaPlayer(libVLC)
    private var aspectMode = 0
    private var surfaceReady = false
    private var pending: Triple<String, String, Long>? = null
    private var lastLayout: VideoLayout? = null

    override val surfaceView: View get() = textureView

    private data class VideoLayout(
        val width: Int,
        val height: Int,
        val visibleWidth: Int,
        val visibleHeight: Int,
        val sarNum: Int,
        val sarDen: Int,
    )

    init {
        textureView.isOpaque = true
        parent.addView(
            textureView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
                attachSurface(width, height)
                pending?.let { (url, ua, pos) ->
                    pending = null
                    startMedia(url, ua, pos)
                }
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    runCatching { mediaPlayer.getVLCVout().setWindowSize(width, height) }
                    applyAspectMode()
                }
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                surfaceReady = false
                runCatching { mediaPlayer.getVLCVout().detachViews() }
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
        }
        mediaPlayer.setEventListener(Events())
    }

    private fun attachSurface(width: Int, height: Int) {
        runCatching {
            val vout = mediaPlayer.getVLCVout()
            vout.setVideoView(textureView)
            if (width > 0 && height > 0) {
                vout.setWindowSize(width, height)
            }
            vout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
                override fun onNewVideoLayout(
                    vlcVout: IVLCVout,
                    width: Int,
                    height: Int,
                    visibleWidth: Int,
                    visibleHeight: Int,
                    sarNum: Int,
                    sarDen: Int,
                ) {
                    lastLayout = VideoLayout(width, height, visibleWidth, visibleHeight, sarNum, sarDen)
                    applyAspectMode()
                }
            })
            mediaPlayer.setVideoTrackEnabled(true)
            applyAspectMode()
        }
    }

    private inner class Events : MediaPlayer.EventListener {
        override fun onEvent(event: MediaPlayer.Event) {
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    onBuffering?.invoke(false)
                    onReady?.invoke()
                    applyAspectMode()
                }
                MediaPlayer.Event.Buffering -> onBuffering?.invoke(event.buffering < 100f)
                MediaPlayer.Event.EndReached -> {
                    onBuffering?.invoke(false)
                    onEnd?.invoke()
                }
                MediaPlayer.Event.EncounteredError -> {
                    onBuffering?.invoke(false)
                    onError?.invoke("Playback error")
                }
                else -> Unit
            }
        }
    }

    override fun videoSize(): Pair<Int, Int>? = runCatching {
        val vw = mediaPlayer.currentVideoTrack?.width ?: 0
        val vh = mediaPlayer.currentVideoTrack?.height ?: 0
        if (vw > 0 && vh > 0) vw to vh else null
    }.getOrNull()

    override fun videoFps(): Float? = runCatching {
        val track = mediaPlayer.currentVideoTrack ?: return null
        if (track.frameRateDen > 0) track.frameRateNum.toFloat() / track.frameRateDen else null
    }.getOrNull()

    override fun audioChannels(): Int? {
        // libvlc MediaPlayer exposes audio track names/ids, but not channel count directly.
        return null
    }

    override fun prepare(url: String, userAgent: String, startPositionMs: Long) {
        if (!surfaceReady) {
            pending = Triple(url, userAgent, startPositionMs)
            return
        }
        startMedia(url, userAgent, startPositionMs)
    }

    private fun startMedia(url: String, userAgent: String, startPositionMs: Long) {
        val media = Media(libVLC, Uri.parse(url))
        if (userAgent.isNotBlank()) media.addOption(":http-user-agent=$userAgent")
        media.addOption(":http-referrer=")
        media.addOption(":network-caching=3000")
        if (startPositionMs > 0) media.addOption(":start-time=$startPositionMs")
        media.setDefaultMediaPlayerOptions()
        mediaPlayer.setMedia(media)
        mediaPlayer.play()
        mediaPlayer.setVideoTrackEnabled(true)
        media.release()
    }

    override fun play() {
        runCatching { mediaPlayer.play() }
    }

    override fun pause() {
        runCatching { mediaPlayer.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { mediaPlayer.setTime(positionMs.coerceAtLeast(0L)) }
    }

    override fun positionMs(): Long = runCatching { mediaPlayer.time }.getOrDefault(0L)

    override fun durationMs(): Long = runCatching { mediaPlayer.length }.getOrDefault(0L)

    override fun isPlaying(): Boolean = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)

    override fun setVolume(volume: Float) {
        runCatching { mediaPlayer.setVolume((volume.coerceIn(0f, 1f) * 100f).toInt()) }
    }

    override fun aspectMode(): Int = aspectMode

    override fun cyclesAspectMode() {
        aspectMode = (aspectMode + 1) % ASPECT_MODES
        applyAspectMode()
    }

    private fun applyAspectMode() {
        runCatching {
            val viewW = textureView.width.toFloat().coerceAtLeast(1f)
            val viewH = textureView.height.toFloat().coerceAtLeast(1f)

            val layout = lastLayout
            val rectW: Float
            val rectH: Float
            if (layout != null && layout.visibleWidth > 0 && layout.visibleHeight > 0) {
                val sar = if (layout.sarDen > 0) layout.sarNum.toFloat() / layout.sarDen else 1f
                rectW = layout.visibleWidth.toFloat() * sar
                rectH = layout.visibleHeight.toFloat()
            } else {
                val track = mediaPlayer.currentVideoTrack
                val videoW = track?.width?.toFloat()?.takeIf { it > 0 } ?: viewW
                val videoH = track?.height?.toFloat()?.takeIf { it > 0 } ?: viewH
                val videoAr = videoW / videoH
                val viewAr = viewW / viewH
                if (viewAr > videoAr) {
                    rectH = viewH
                    rectW = viewH * videoAr
                } else {
                    rectW = viewW
                    rectH = viewW / videoAr
                }
            }

            val matrix = Matrix()
            when (aspectMode) {
                // Fit screen: keep libVLC letterboxing untouched.
                0 -> Unit
                // Fill screen: stretch the video rect onto the whole view.
                1 -> {
                    matrix.setScale(viewW / rectW, viewH / rectH)
                }
                // Zoom: scale the video rect up until it covers the view.
                2 -> {
                    val scale = maxOf(viewW / rectW, viewH / rectH)
                    val scaledW = rectW * scale
                    val scaledH = rectH * scale
                    matrix.setScale(scale, scale)
                    matrix.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)
                }
            }
            textureView.setTransform(matrix)
        }
    }

    override fun aspectModeLabel(context: Context): String = when (aspectMode) {
        0 -> context.getString(R.string.aspect_fit)
        1 -> context.getString(R.string.aspect_fill)
        2 -> context.getString(R.string.aspect_zoom)
        else -> context.getString(R.string.aspect_ratio)
    }

    override fun release() {
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
        runCatching { libVLC.release() }
    }

    override var onReady: (() -> Unit)? = null
    override var onBuffering: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onEnd: (() -> Unit)? = null
    override var onVideoSize: ((Int, Int) -> Unit)? = null

    companion object {
        private const val ASPECT_MODES = 3
    }
}
