package com.tvibro.ui.player

import android.content.Context
import android.net.Uri
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvibro.R
import com.tvibro.data.MAX_BUFFER_MS
import com.tvibro.data.MIN_BUFFER_MS
import com.tvibro.data.NO_BUFFER_MS
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import java.util.concurrent.Executors

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
      /**
       * Size of the picture as it is actually drawn, in the surface view's own pixels. An engine
       * letterboxes a frame that does not match the screen, so this is normally smaller than the
       * view: a gesture that drags the picture has to stop at these edges rather than at the edges
       * of the view, or the frame would slide off the screen and uncover what is behind it.
       */
      fun contentSize(): Pair<Float, Float>? = null
    fun videoFps(): Float?
    fun audioChannels(): Int?
    fun videoCodecLabel(): String? = null
    fun audioCodecLabel(): String? = null
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
    // PREFER puts the FFmpeg extension ahead of MediaCodec: Stalker portals hand out MPEG audio
    // layer 2 streams that no platform decoder on these boxes can handle, and FFmpeg is the only
    // way they produce sound instead of a hard "track not supported" failure.
    private val renderersFactory =
        DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    private val loadControl = buildLoadControl(bufferMs)
    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setAudioAttributes(AudioAttributes.DEFAULT, false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private var lastUrl = ""
    private var lastUserAgent = ""
    private var lastStartMs = 0L
    private var useHls = true
    private var attempts = 0
    @Volatile private var spsInfo: H264Sps.Info? = null
    @Volatile private var spsKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val WATCHDOG_MS = 8_000L
        private const val MAX_START_ATTEMPTS = 3

        /**
         * Media3 throws [IllegalArgumentException] unless minBuffer >= bufferForPlayback
         * and maxBuffer >= minBuffer, so every value coming from settings is sanitized.
         */
        fun buildLoadControl(bufferMs: Int): LoadControl {
            if (bufferMs == NO_BUFFER_MS) {
                // "No buffering": start at the first available byte and keep nothing queued,
                // so the picture stays as close to the live edge as the network allows.
                return DefaultLoadControl.Builder()
                    .setBufferDurationsMs(0, 0, 0, 0)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
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

    init {
        parent.addView(
            playerView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        playerView.player = player
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                Log.w("ExoEngine", "потеряно кадров: $droppedFrames за ${elapsedMs}мс")
            }
        })
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        onBuffering?.invoke(false)
                        onReady?.invoke()
                        maybeScheduleSps()
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
                // A portal URL carries no file extension, so the container has to be guessed.
                // Stalker proxies answer with HLS far more often than with a plain TS body,
                // and the two are tried in turn, one connection at a time.
                Log.w("ExoEngine", "ошибка ${error.errorCodeName}, пробуем другой контейнер")
                if (tryNextAttempt()) return
                onError?.invoke(error.errorCodeName)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    cancelWatchdog()
                    onVideoSize?.invoke(videoSize.width, videoSize.height)
                    maybeScheduleSps()
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
        lastUrl = url
        lastUserAgent = userAgent
        lastStartMs = startPositionMs
        attempts = 0
        useHls = true
        spsInfo = null
        spsKey = null
        startPlayback(url, userAgent, startPositionMs)
    }

    private fun startPlayback(url: String, userAgent: String, startPositionMs: Long) {
        val mediaItem = MediaItem.Builder().setUri(url).build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent.ifBlank { "TiViBro" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val source = if (useHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        armWatchdog()
    }

    /**
     * A Stalker proxy answers with HLS or a plain TS body and never says which, and either attempt
     * can also stall without a single error event. Each channel therefore gets a bounded run of
     * single-connection tries: the container flips every time, a stall is caught by the watchdog,
     * and once the run is over the failure is reported instead of a dead black screen. A fresh
     * [prepare] on the same URL demonstrably recovers a stalled proxy, hence the re-tries.
     */
    private fun tryNextAttempt(): Boolean {
        if (attempts >= MAX_START_ATTEMPTS) return false
        attempts++
        useHls = !useHls
        Log.d("ExoEngine", "попытка #$attempts: ${if (useHls) "HLS" else "progressive"}")
        startPlayback(lastUrl, lastUserAgent, lastStartMs)
        return true
    }

    private fun armWatchdog() {
        mainHandler.removeCallbacks(watchdogRun)
        mainHandler.postDelayed(watchdogRun, WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdogRun)
    }

    private val watchdogRun = Runnable {
        // Audio-only streams (radio) are legitimately READY without any video size.
        val progressing = runCatching {
            player.playbackState == androidx.media3.common.Player.STATE_READY || videoSize() != null
        }.getOrDefault(false)
        if (progressing) return@Runnable
        Log.w("ExoEngine", "нет данных за ${WATCHDOG_MS}мс, попытка #$attempts")
        if (!tryNextAttempt()) {
            onError?.invoke("Stream stalled")
        }
    }

    /**
     * Media3 leaves [androidx.media3.common.Format.frameRate] unset for TS, so the real frame rate
     * has to come out of the SPS. Decoding it here is off the critical path on purpose: the track
     * change only hands a few dozen bytes to a background thread and the badge reads the result
     * through a volatile field, so neither opening a channel nor playback is held up.
     */
    private fun maybeScheduleSps() {
        val format = runCatching { player.videoFormat }.getOrNull() ?: return
        scheduleSpsParse(format)
    }

    private fun scheduleSpsParse(format: Format) {
        if (format.sampleMimeType != MimeTypes.VIDEO_H264) return
        val csd = format.copyCsd() ?: return
        // Content hash, not the size: two channels of the same resolution must not share a result.
        val key = "${format.width}x${format.height}:${csd.contentHashCode()}"
        if (key == spsKey) return
        spsKey = key
        spsInfo = null
        spsExecutor.execute {
            val parsed = H264Sps.parse(csd)
            if (parsed == null) {
                spsKey = null
                return@execute
            }
            Log.d(
                "ExoEngine",
                "SPS: ${parsed.width}x${parsed.height} fps=${parsed.frameRate} " +
                    "profile=${parsed.profileIdc} level=${parsed.levelIdc} " +
                    "interlaced=${parsed.interlaced}"
            )
            spsInfo = parsed
        }
    }

    private fun Format.copyCsd(): ByteArray? =
        initializationData[0].takeIf { it.isNotEmpty() }

    override fun videoCodecLabel(): String? =
        runCatching { player.videoFormat?.sampleMimeType?.let { codecLabel(it) } }.getOrNull()

    override fun audioCodecLabel(): String? = runCatching {
        player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.mediaTrackGroup
            ?.getFormat(0)
            ?.sampleMimeType
            ?.let { codecLabel(it) }
    }.getOrNull()

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

    override fun contentSize(): Pair<Float, Float>? {
        // The aspect frame measures the surface to the shape of the frame, so the surface is the
        // picture and the view around it is the letterboxing.
        val surface = playerView.videoSurfaceView ?: return null
        return if (surface.width > 0 && surface.height > 0)
            surface.width.toFloat() to surface.height.toFloat()
        else null
    }

    override fun videoFps(): Float? {
        spsInfo?.frameRate?.let { if (it > 0) return it }
        return runCatching { player.videoFormat?.frameRate?.takeIf { it > 0 } }.getOrNull()
    }

    override fun audioChannels(): Int? = runCatching {
        player.audioFormat?.channelCount?.takeIf { it > 0 }
    }.getOrNull()

    override fun release() {
        cancelWatchdog()
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

      // Kept from [applyAspectMode] so a gesture can ask how big the picture really is.
      private var pictureWidth = 0f
      private var pictureHeight = 0f

      override fun contentSize(): Pair<Float, Float>? =
          if (pictureWidth > 0f && pictureHeight > 0f) pictureWidth to pictureHeight else null

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
                    notifyVideoSize()
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
                    kickParse()
                    mainHandler.postDelayed({
                        if (!videoReady(bestSnapshot) && decoderSize == null) kickDecoderProbe()
                    }, 6000L)
                    notifyVideoSize()
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
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Vout -> {
                    val track = mediaPlayer.currentVideoTrack
                    if (track != null) lastEventVideoTrack = track
                    kickParse()
                    notifyVideoSize()
                }
                else -> Unit
            }
        }
    }

    private fun eventName(type: Int): String = when (type) {
        MediaPlayer.Event.ESAdded -> "ESAdded"
        MediaPlayer.Event.ESSelected -> "ESSelected"
        MediaPlayer.Event.Vout -> "Vout"
        else -> "EVT$type"
    }

    /** libvlc fills video/audio tracks asynchronously; expose whichever is known first. */
    private fun notifyVideoSize() {
        if (onVideoSize == null) return
        val size = videoSize()
        if (size != null && size.first > 0 && size.second > 0) {
            runCatching { onVideoSize?.invoke(size.first, size.second) }
        }
    }

    override fun videoSize(): Pair<Int, Int>? = snapshotSize(bestSnapshot)
        ?: eventTrackSize()
        ?: currentTrackSize()
        ?: decoderSize
        ?: lastLayout?.takeIf { l -> l.visibleWidth > 0 && l.visibleHeight > 0 }
            ?.let { it.visibleWidth to it.visibleHeight }

    private fun eventTrackSize(): Pair<Int, Int>? {
        val track = lastEventVideoTrack ?: return null
        val w = track.width
        val h = track.height
        return if (w > 0 && h > 0) w to h else null
    }

    private fun currentTrackSize(): Pair<Int, Int>? {
        val track = mediaPlayer.currentVideoTrack ?: return null
        val w = track.width
        val h = track.height
        return if (w > 0 && h > 0) w to h else null
    }

    override fun videoFps(): Float? = snapshotFps(bestSnapshot)
        ?: eventTrackFps()
        ?: currentTrackFps()
        ?: decoderFps

    private fun eventTrackFps(): Float? {
        val track = lastEventVideoTrack ?: return null
        return if (track.frameRateDen > 0) track.frameRateNum.toFloat() / track.frameRateDen else null
    }

    private fun currentTrackFps(): Float? {
        val track = mediaPlayer.currentVideoTrack ?: return null
        return if (track.frameRateDen > 0) track.frameRateNum.toFloat() / track.frameRateDen else null
    }

    override fun audioChannels(): Int? = snapshotChannels(bestSnapshot)

    private data class ParsedTrack(
        val type: Int,
        val codec: String,
        val videoWidth: Int,
        val videoHeight: Int,
        val fpsNum: Int,
        val fpsDen: Int,
        val audioChannels: Int,
        val audioRate: Int,
    )

    /** Snapshot of the last parse result, read on the main thread so the UI never blocks on
     *  libvlc. [IMedia] objects are ref-counted: every [MediaPlayer.getMedia] must end with
     *  [IMedia.release] or the native object leaks. */
    private var parsedSnapshot: List<ParsedTrack>? = null

    /** libvlc fills live-stream track dimensions asynchronously: the first parse often reports 0x0
     *  and a later one the real size. Keep the best snapshot so a bad read never erases a good one.
     *  Re-parses run off the UI thread on the executor; the [IMedia] object from
     *  [MediaPlayer.getMedia] is released right after extraction. */
    private var bestSnapshot: List<ParsedTrack>? = null

    private var lastParseAt = 0L
    private var lastEventVideoTrack: IMedia.VideoTrack? = null
    private var probeParsing = false
    private var parseAttempts = 0
    private var parseGeneration = 0
    private val parseExecutor = Executors.newSingleThreadExecutor()
    private val decoderExecutor = Executors.newSingleThreadExecutor()
    private var decoderProbing = false
    private var decoderProbeUrl: String? = null
    private var parseProbeUa: String? = null
    /** Real dimensions parsed straight from the stream's SPS (what the hardware decoder sees),
     *  independent of VLC's Java track API which often stays 0x0 on live channels. */
    private var decoderSize: Pair<Int, Int>? = null
    private var decoderFps: Float? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun videoReady(tracks: List<ParsedTrack>?): Boolean =
        tracks?.any { it.videoWidth > 0 && it.videoHeight > 0 && it.fpsDen > 0 } == true

    /** Parse the PLAYER's own media off the UI thread. libvlc fills live-stream track dimensions
     *  asynchronously right after the demuxer is live, so re-parsing the same (playing) media is
     *  what catches the window: a standalone probe with the same URL is a second connection that is
     *  observably slower and often reports 0x0. Every [MediaPlayer.getMedia] is ref-counted and
     *  must end with [IMedia.release]. */
    private fun kickParse() {
        val now = System.currentTimeMillis()
        if (probeParsing || videoReady(bestSnapshot) || now - lastParseAt < 1500L) return
        if (parseAttempts >= MAX_PARSE_ATTEMPTS) return
        parseAttempts++
        lastParseAt = now
        probeParsing = true
        val gen = parseGeneration
        parseExecutor.execute {
            val media = runCatching { mediaPlayer.getMedia() }.getOrNull()
            val snapshot = if (media != null) {
                try {
                    runCatching { media.parse(3000) }
                    extractTracks(media)
                } finally {
                    runCatching { media.release() }
                }
            } else emptyList()
            mainHandler.post {
                probeParsing = false
                if (gen != parseGeneration) return@post
                if (snapshot.isNotEmpty()) {
                    if (videoReady(snapshot) || !videoReady(bestSnapshot)) {
                        bestSnapshot = snapshot
                    }
                    parsedSnapshot = snapshot
                }
                notifyVideoSize()
                if (!videoReady(bestSnapshot) && parseAttempts < MAX_PARSE_ATTEMPTS) {
                    mainHandler.postDelayed(::kickParse, 1500L)
                } else if (!videoReady(bestSnapshot)) {
                    kickDecoderProbe()
                }
            }
        }
    }

    /** VLC's IMedia/VLC vout API keeps reporting 0x0 for many live channels even though the
     *  hardware decoder knows the real size. Fall back to reading the H.264 SPS right out of the
     *  stream (the same bytes the demuxer hands to the decoder) and decoding width/height from it.
     *  Runs on its own executor so it never blocks parsing or rendering. */
    private fun kickDecoderProbe() {
        if (decoderProbing || decoderSize != null || parseGeneration == 0) return
        val url = decoderProbeUrl ?: return
        decoderProbing = true
        val gen = parseGeneration
        decoderExecutor.execute {
            Log.d("TvibroBadges", "SPS decoder probe start")
            val raw = runCatching { probeSps(url) }.getOrNull()
            val result = raw ?: runCatching { probeExtractor(url) }.getOrNull()
            mainHandler.post {
                decoderProbing = false
                if (gen != parseGeneration) return@post
                if (result != null) {
                    decoderSize = result.first
                    decoderFps = result.second
                    Log.d("TvibroBadges", "SPS decoder size=${result.first} fps=${result.second}")
                    notifyVideoSize()
                } else {
                    Log.d("TvibroBadges", "SPS decoder no result, retrying")
                    mainHandler.postDelayed({
                        if (gen == parseGeneration && decoderSize == null && !decoderProbing) {
                            kickDecoderProbe()
                        }
                    }, 8000L)
                }
            }
        }
    }

    private fun probeSps(url: String): Pair<Pair<Int, Int>, Float?>? {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", parseProbeUa ?: "VLC/3.6.5 LibVLC/3.6.5")
        }
        try {
            val input = connection.inputStream
            val buffer = ByteArray(32 * 1024)
            val acc = java.io.ByteArrayOutputStream()
            var lastScan = 0
            val deadline = System.currentTimeMillis() + 45000
            while (System.currentTimeMillis() < deadline) {
                val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (read < 0) break
                acc.write(buffer, 0, read)
                if (acc.size() - lastScan > 32 * 1024) {
                    lastScan = acc.size()
                    findSps(acc.toByteArray())?.let { return it }
                }
            }
            return findSps(acc.toByteArray())
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Locate an H.264 SPS NAL unit (type 7) with a recognized profile and yield
     *  (width, height) and fps (from VUI timing_info, may be null). Mirrors what the
     *  hardware decoder reads from the stream. */
    private fun findSps(bytes: ByteArray): Pair<Pair<Int, Int>, Float?>? {
        var i = 0
        while (i < bytes.size - 8) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1 &&
                (bytes[i + 3].toInt() and 0x1f) == 7
            ) {
                // profile_idc sanity: reject random TS payloads that happen to look like an SPS.
                val profile = bytes[i + 4].toInt() and 0xff
                if (profile !in intArrayOf(66, 77, 88, 100, 110, 122, 144, 244)) {
                    i += 3
                    continue
                }
                val nalLen = bytes.size - (i + 4)
                val rbsp = unescapeRbsp(bytes, i + 4, nalLen)
                parseSps(rbsp)?.let { return it }
                i += 3
            }
            i++
        }
        return null
    }

    /** Strip emulation-prevention 0x03 bytes from H.264 ES, like the decoder does. */
    private fun unescapeRbsp(bytes: ByteArray, start: Int, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(len)
        var zeros = 0
        for (j in start until start + len) {
            val b = bytes[j].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Parse an SPS NV unit (starting right after the 1-byte NAL header): width/height from
     *  pic_width_in_mbs_minus1 / pic_height_in_map_units_minus1 with crops, fps from VUI. */
    private fun parseSps(rbsp: ByteArray): Pair<Pair<Int, Int>, Float?>? {
        if (rbsp.size < 4) return null

        // Bit offset after profile_idc(8) + constraint flags(8) + level_idc(8).
        var bitPos = 24

        // seq_parameter_set_id ue(v)
        val s = readUe(rbsp, bitPos) ?: return null
        bitPos = s.first
        // log2_max_frame_num_minus4 ue(v)
        val m = readUe(rbsp, bitPos) ?: return null
        bitPos = m.first
        // pic_order_cnt_type ue(v)
        val poct = readUe(rbsp, bitPos) ?: return null
        bitPos = poct.first
        when (poct.second) {
            0 -> {
                val off = readUe(rbsp, bitPos) ?: return null
                bitPos = off.first
            }
            1 -> {
                // delta_pic_order_always_zero_flag(1)
                if (readBits(rbsp, bitPos, 1) == null) return null
                bitPos++
                // offset_for_non_ref_pic se(v)
                val se1 = readSe(rbsp, bitPos) ?: return null
                bitPos = se1.first
                // offset_for_top_to_bottom_field se(v)
                val se2 = readSe(rbsp, bitPos) ?: return null
                bitPos = se2.first
                val n = readUe(rbsp, bitPos) ?: return null
                bitPos = n.first
                repeat(n.second) {
                    val se = readSe(rbsp, bitPos) ?: return null
                    bitPos = se.first
                }
            }
            else -> return null
        }
        // max_num_ref_frames ue(v)
        val ref = readUe(rbsp, bitPos) ?: return null
        bitPos = ref.first
        // gaps_in_frame_num_value_allowed_flag(1)
        if (readBits(rbsp, bitPos, 1) == null) return null
        bitPos++

        // pic_width_in_mbs_minus1 ue(v)
        val widthMb = readUe(rbsp, bitPos) ?: return null
        bitPos = widthMb.first
        // pic_height_in_map_units_minus1 ue(v)
        val heightMap = readUe(rbsp, bitPos) ?: return null
        bitPos = heightMap.first

        // frame_mbs_only_flag(1)
        val frameMbsOnly = readBits(rbsp, bitPos, 1) ?: return null
        bitPos = frameMbsOnly.second
        if (frameMbsOnly.first == 0) {
            // mb_adaptive_frame_field_flag(1)
            if (readBits(rbsp, bitPos, 1) == null) return null
            bitPos++
        }
        // direct_8x8_inference_flag(1)
        if (readBits(rbsp, bitPos, 1) == null) return null
        bitPos++
        // frame_cropping_flag(1)
        val cropping = readBits(rbsp, bitPos, 1) ?: return null
        bitPos = cropping.second
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (cropping.first == 1) {
            val l = readUe(rbsp, bitPos) ?: return null
            bitPos = l.first
            val r = readUe(rbsp, bitPos) ?: return null
            bitPos = r.first
            val t = readUe(rbsp, bitPos) ?: return null
            bitPos = t.first
            val b = readUe(rbsp, bitPos) ?: return null
            bitPos = b.first
            cropLeft = l.second
            cropRight = r.second
            cropTop = t.second
            cropBottom = b.second
        }
        // vui_parameters_present_flag(1)
        val vuiPresent = readBits(rbsp, bitPos, 1)?.first == 1

        var fps: Float? = null
        if (vuiPresent) {
            bitPos++
            parseVui(rbsp, bitPos)?.let { (fpsValue, next) ->
                fps = fpsValue
                bitPos = next
            }
        }

        // 4:2:0 chroma → crop units are 2 and 2 in the sample dimensions.
        val width = (widthMb.second + 1) * 16 - (cropLeft + cropRight) * 2
        val height = (2 - frameMbsOnly.first) * (heightMap.second + 1) * 16 -
            (cropTop + cropBottom) * 2
        if (width < 320 || height < 176 || width > 4096 || height > 2160) return null
        return (width to height) to fps
    }

    /** Parse VUI from the given bit offset; returns (fpsOrNull, newBitPos). VUI must be walked
     *  field by field to reach timing_info: many flags are followed by fixed-size payloads. */
    private fun parseVui(rbsp: ByteArray, start: Int): Pair<Float?, Int>? {
        var bitPos = start
        // aspect_ratio_info_present_flag(1)
        if (readBits(rbsp, bitPos, 1)?.first == 1) {
            bitPos++
            val aspectIdc = readBits(rbsp, bitPos, 8)?.first ?: return null
            bitPos += 8
            if (aspectIdc == 255) bitPos += 32 // extended_sar_width(16)+height(16)
        } else {
            bitPos++
        }
        // overscan_info_present_flag(1)
        if (readBits(rbsp, bitPos, 1)?.first == 1) {
            bitPos++
            bitPos++ // overscan_appropriate_flag
        } else {
            bitPos++
        }
        // video_signal_type_present_flag(1)
        if (readBits(rbsp, bitPos, 1)?.first == 1) {
            bitPos++
            bitPos += 3 // video_format
            bitPos++ // video_full_range_flag
            // colour_description_present_flag(1)
            if (readBits(rbsp, bitPos, 1)?.first == 1) {
                bitPos++
                bitPos += 24 // colour_primaries, transfer_characteristics, matrix_coefficients
            } else {
                bitPos++
            }
        } else {
            bitPos++
        }
        // chroma_loc_info_present_flag(1)
        if (readBits(rbsp, bitPos, 1)?.first == 1) {
            bitPos++
            val u1 = readUe(rbsp, bitPos) ?: return null
            bitPos = u1.first
            val u2 = readUe(rbsp, bitPos) ?: return null
            bitPos = u2.first
        } else {
            bitPos++
        }
        // timing_info_present_flag(1)
        if (readBits(rbsp, bitPos, 1)?.first != 1) return null to start
        bitPos++
        val numUnits = readBits(rbsp, bitPos, 32) ?: return null
        bitPos = numUnits.second
        val timeScale = readBits(rbsp, bitPos, 32) ?: return null
        bitPos = timeScale.second
        val fps = if (numUnits.first > 0 && timeScale.first > 0)
            timeScale.first.toFloat() / (2f * numUnits.first)
        else null
        return fps to bitPos
    }

    /** Try Android's MediaExtractor (system demuxer) on the same URL — same stack the
     *  platform decoder uses, so it's the closest analogue to what the decoder sees. */
    private fun probeExtractor(url: String): Pair<Pair<Int, Int>, Float?>? {
        val ext = android.media.MediaExtractor()
        try {
            ext.setDataSource(url)
            for (i in 0 until ext.trackCount) {
                val fmt = ext.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    val w = fmt.getInteger(android.media.MediaFormat.KEY_WIDTH)
                    val h = fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                    val fps = if (fmt.containsKey(android.media.MediaFormat.KEY_FRAME_RATE))
                        fmt.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) else null
                    if (w in 320..4096 && h in 176..2160) {
                        return (w to h) to fps
                    }
                }
            }
        } finally {
            runCatching { ext.release() }
        }
        return null
    }

    /** Read an unsigned Exp-Golomb code; returns (newBitPos, value). */
    private fun readUe(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var zeros = 0
        var p = start
        while (true) {
            val bit = readBits(bytes, p, 1) ?: return null
            p = bit.second
            if (bit.first == 1) break
            zeros++
            if (zeros > 31) return null
        }
        var value = 1
        for (j in 0 until zeros) {
            val bit = readBits(bytes, p, 1) ?: return null
            p = bit.second
            value = (value shl 1) or bit.first
        }
        value--
        if (value < 0 || value > 1 shl 20) return null
        return p to value
    }

    /** Read a signed Exp-Golomb code; returns (newBitPos, value). */
    private fun readSe(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        val ue = readUe(bytes, start) ?: return null
        val code = ue.second
        val value = if (code % 2 == 0) (code / -2) else ((code + 1) / 2)
        return ue.first to value
    }

    private fun readBits(bytes: ByteArray, pos: Int, count: Int): Pair<Int, Int>? {
        if (count > 64) return null
        var value = 0
        var p = pos
        for (j in 0 until count) {
            val byteIdx = p / 8
            if (byteIdx >= bytes.size) return null
            val bit = (bytes[byteIdx].toInt() shr (7 - (p % 8))) and 1
            value = (value shl 1) or bit
            p++
        }
        return value to p
    }

    private fun extractTracks(media: IMedia): List<ParsedTrack> = runCatching {
        val count = media.trackCount
        (0 until count).mapNotNull { i ->
            val t = runCatching { media.getTrack(i) }.getOrNull() ?: return@mapNotNull null
            val v = t as? IMedia.VideoTrack
            val a = t as? IMedia.AudioTrack
            ParsedTrack(
                type = t.type,
                codec = t.codec,
                videoWidth = v?.width ?: 0,
                videoHeight = v?.height ?: 0,
                fpsNum = v?.frameRateNum ?: 0,
                fpsDen = v?.frameRateDen ?: 0,
                audioChannels = a?.channels ?: 0,
                audioRate = a?.rate ?: 0,
            )
        }
    }.getOrElse { emptyList() }

    private fun snapshotSize(snapshot: List<ParsedTrack>?): Pair<Int, Int>? =
        snapshot?.firstNotNullOfOrNull { it.takeIf { t -> t.videoWidth > 0 && t.videoHeight > 0 } }
            ?.let { it.videoWidth to it.videoHeight }

    override fun videoCodecLabel(): String? =
        bestSnapshot?.firstNotNullOfOrNull { if (it.type == 1) fourccLabel(it.codec) else null }

    override fun audioCodecLabel(): String? =
        bestSnapshot?.firstNotNullOfOrNull { if (it.type == 2) fourccLabel(it.codec) else null }
    private fun snapshotFps(snapshot: List<ParsedTrack>?): Float? =
        snapshot?.firstNotNullOfOrNull { it.takeIf { t -> t.fpsDen > 0 } }
            ?.let { it.fpsNum.toFloat() / it.fpsDen }

    private fun snapshotChannels(snapshot: List<ParsedTrack>?): Int? =
        snapshot?.firstNotNullOfOrNull { it.takeIf { t -> t.audioChannels > 0 } }?.audioChannels

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
        parsedSnapshot = null
        bestSnapshot = null
        lastEventVideoTrack = null
        lastParseAt = 0L
        parseAttempts = 0
        parseGeneration++
        probeParsing = false
        decoderSize = null
        decoderFps = null
        decoderProbing = false
        decoderProbeUrl = url
        parseProbeUa = userAgent
        mediaPlayer.setMedia(media)
        mediaPlayer.play()
        mediaPlayer.setVideoTrackEnabled(true)
        kickParse()
        mainHandler.postDelayed({
            if (!videoReady(bestSnapshot) && decoderSize == null) kickDecoderProbe()
        }, 3500L)
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
                0 -> {
                    pictureWidth = rectW
                    pictureHeight = rectH
                }
                // Fill screen: stretch the video rect onto the whole view.
                1 -> {
                    matrix.setScale(viewW / rectW, viewH / rectH)
                    pictureWidth = viewW
                    pictureHeight = viewH
                }
                // Zoom: scale the video rect up until it covers the view.
                2 -> {
                    val scale = maxOf(viewW / rectW, viewH / rectH)
                    val scaledW = rectW * scale
                    val scaledH = rectH * scale
                    matrix.setScale(scale, scale)
                    matrix.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)
                    pictureWidth = scaledW
                    pictureHeight = scaledH
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
        probeParsing = false
        parseExecutor.shutdownNow()
        decoderExecutor.shutdownNow()
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
        private const val MAX_PARSE_ATTEMPTS = 6
    }
}

/** Single background thread for codec introspection, kept off the playback and main threads. */
private val spsExecutor: java.util.concurrent.ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tvibro-sps").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

/** Short, badge-sized names for the codecs an IPTV stream actually uses. */
private fun codecLabel(mime: String?): String? {
    val m = mime?.lowercase() ?: return null
    return when {
        m == "video/avc" || m == "video/h264" -> "H264"
        m == "video/hevc" || m == "video/h265" -> "HEVC"
        m == "video/mp4v-es" -> "MPEG4"
        m == "video/mpeg2" -> "MPEG2"
        m == "video/x-vnd.on2.vp8" -> "VP8"
        m == "video/x-vnd.on2.vp9" -> "VP9"
        m == "video/av01" -> "AV1"
        m == "audio/mpeg-l2" || m == "audio/mp2" || m == "audio/mp4a-6b" -> "MP2"
        m == "audio/mpeg" || m == "audio/mpeg-l3" || m == "audio/mp3" -> "MP3"
        m == "audio/mp4a-latm" -> "AAC"
        m == "audio/ac3" -> "AC3"
        m == "audio/eac3" || m == "audio/eac3-joc" -> "EAC3"
        m == "audio/vnd.dts" || m == "audio/vnd.dts.hd" -> "DTS"
        m == "audio/vorbis" -> "VORBIS"
        m == "audio/opus" -> "OPUS"
        m == "audio/flac" -> "FLAC"
        m == "audio/3gpp" -> "AMR"
        m == "audio/amr-wb" -> "AMR-WB"
        m == "audio/raw" -> "PCM"
        m.startsWith("audio/") -> m.removePrefix("audio/").substringBefore(';').uppercase().take(6)
        m.startsWith("video/") -> m.removePrefix("video/").substringBefore(';').uppercase().take(6)
        else -> null
    }
}

/** libvlc hands back either a four character code ("H264") or a longer codec name. */
private fun fourccLabel(codec: String?): String? {
    val raw = codec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (raw.lowercase()) {
        "h264", "avc", "avc1" -> "H264"
        "hevc", "h265" -> "HEVC"
        "mpgv", "mpeg2video", "mp2v" -> "MPEG2"
        "mp4v" -> "MPEG4"
        "vp8" -> "VP8"
        "vp9" -> "VP9"
        "mpga", "mp3" -> "MP3"
        "mp2", "mp2a" -> "MP2"
        "aac" -> "AAC"
        "ac3" -> "AC3"
        "eac3" -> "EAC3"
        else -> raw.filter { it.isLetterOrDigit() }.uppercase().take(6)
    }
}
