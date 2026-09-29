package com.tvibro.ui.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.Fmt
import com.tvibro.base.startActivitySafely
import com.tvibro.base.toast
import com.tvibro.base.visible
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.Channel
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.Program
import com.tvibro.data.source.CatchupResolver
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors

class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: TvBroRepository
    private lateinit var root: FrameLayout
    private lateinit var engineHolder: FrameLayout
    private lateinit var engine: PlaybackEngine
    private var exoEngine: ExoEngine? = null

    private lateinit var switchPanel: View
    private lateinit var switchProgramTitle: TextView
    private lateinit var switchTime: TextView
    private lateinit var switchProgress: ProgressBar
    private lateinit var switchRemaining: TextView
    private lateinit var switchChannelName: TextView
    private lateinit var switchLogo: ImageView
    private lateinit var switchNumber: TextView
    private lateinit var switchQuality: TextView
    private lateinit var switchFps: TextView
    private lateinit var switchAudio: TextView
private lateinit var switchVideoCodec: TextView
private lateinit var switchAudioCodec: TextView
    private lateinit var switchDescription: TextView
    private lateinit var switchNextProgram: TextView
    private lateinit var sideChannelsList: RecyclerView
    private lateinit var sideChannelAdapter: SideChannelAdapter
    private lateinit var sideContainer: View

    private val sideChannelsVisible: Boolean
        get() = sideContainer.visibility == View.VISIBLE
    private lateinit var sideProgramTitle: TextView
    private lateinit var sideProgramTime: TextView
    private lateinit var sideProgramDescription: TextView
    private lateinit var sideScheduleList: RecyclerView
    private lateinit var sideScheduleEmpty: TextView
    private lateinit var sideScheduleAdapter: SideScheduleAdapter
    private var sideScheduleChannelId = -1L
    private var sideFocusedChannelId = -1L
    private val sideScheduleCache = object : LinkedHashMap<Long, List<Program>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Program>>): Boolean = size > 8
    }
    private var sideProgramLoadId = -1L
    private val sideProgramDebounce = Runnable { loadSideProgramDetails(sideProgramLoadId) }
    private var sideChannelPosition = 0
    private var sideSchedulePosition = 0
    private var sideChannelsLoaded = false
    private lateinit var osdHeader: View
    private lateinit var osdLogo: ImageView
    private lateinit var osdChannelName: TextView
    private lateinit var osdGroup: TextView
    private lateinit var osdClock: TextView
    private lateinit var osdPanel: View
    private lateinit var panelSeek: ProgressBar
    private lateinit var panelButtons: LinearLayout
    private lateinit var infoPanel: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoChannelName: TextView
    private lateinit var infoGroup: TextView
    private lateinit var infoProgramTitle: TextView
    private lateinit var infoProgramTime: TextView
    private lateinit var infoNextProgram: TextView
    private lateinit var infoDescription: TextView
    private lateinit var infoStream: TextView
    private lateinit var bufferingView: ProgressBar
    private lateinit var messageView: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-player").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private var channel: Channel? = null
    private var requestedChannelId: Long = -1L
    private var playlist: Playlist? = null
    private var program: Program? = null
    private var channelIds: LongArray = LongArray(0)
    private var currentIndex = 0
    private var navigationIds: LongArray? = null
    private var navigationIdsLoading = false
    private var sideProgramChannelIds: List<Long> = emptyList()
    private var lastSideProgramsAt = 0L
private var panelTimeout = 0L
    private var switchTimeout = 0L
 private var watchStart = 0L
 private var watchTimeMs = 0L
    private var videoHeight = 0
    private var sleepTimerAt = 0L
    private var hidden = false
    private var keepPlayingBehind = false
    private var isSwitching = false
    private var bufferingChannelId: Long = -1L
    /** Last known stream metadata per channel (resolution/fps/audio), reused while a fresh
     *  stream has not reported its own values yet. LRU, bounded to avoid unbounded growth. */
    private val streamMetaCache = object : LinkedHashMap<Long, StreamMeta>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, StreamMeta>): Boolean =
            size > 200
    }

    // Playback readiness is the only reliable "switching finished" signal, and it does not
    // always arrive when channels are switched quickly: the engine may already have reported
    // ready for the previous channel and never report again for the new one. The label would
    // then stay on screen forever, so every buffering indicator is also bounded by a timeout.
    private val clearBuffering: Runnable = Runnable {
        bufferingChannelId = -1L
        bufferingView.visible(false)
        showMessage(null)
    }

    private fun armBufferingTimeout(channelId: Long) {
        bufferingChannelId = channelId
        main.removeCallbacks(clearBuffering)
        main.postDelayed(clearBuffering, BUFFERING_TIMEOUT_MS)
    }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // Only log focus changes. Muting the player on focus loss was silencing playback
        // on some Android TV devices where the focus state is not what we expected.
        Log.d("TvibroPlayer", "audio focus change: $focusChange")
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && ::engine.isInitialized) {
            engine.setVolume(1f)
        }
    }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        repo = TvBroApp.repo(this)
        setContentView(R.layout.activity_player)
        bindViews()
        root.isFocusableInTouchMode = true
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        channelIds = intent.getLongArrayExtra(EXTRA_CHANNEL_IDS) ?: LongArray(0)
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)

        buildPanelButtons()
        startTicker()

        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        if (channelId == 0L) {
            finish()
            return
        }
        loadChannel(channelId, fromStart = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(clearBuffering)
        saveWatchTime()
        if (::engine.isInitialized) engine.release()
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        abandonAudioFocus()
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::engine.isInitialized && !isFinishing && !keepPlayingBehind) engine.pause()
        keepPlayingBehind = false
    }

    override fun onResume() {
        super.onResume()
        keepPlayingBehind = false
        if (::engine.isInitialized && !hidden) engine.play()
        // font scales can change while Settings is open on top of the player
        if (::sideChannelAdapter.isInitialized) applyPanelFontScales()
        // a foreground service would need a type on API 34+, a window flag is enough
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        root = findViewById(R.id.player_root)
        engineHolder = FrameLayout(this).also {
            root.addView(it, 0, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        switchPanel = findViewById(R.id.switch_panel)
        switchProgramTitle = findViewById(R.id.switch_program_title)
        switchTime = findViewById(R.id.switch_time)
        switchProgress = findViewById(R.id.switch_progress)
        switchRemaining = findViewById(R.id.switch_remaining)
        switchChannelName = findViewById(R.id.switch_channel_name)
        switchLogo = findViewById(R.id.switch_logo)
        switchNumber = findViewById(R.id.switch_number)
        switchQuality = findViewById(R.id.switch_quality)
        switchFps = findViewById(R.id.switch_fps)
        switchAudio = findViewById(R.id.switch_audio)
switchVideoCodec = findViewById(R.id.switch_video_codec)
switchAudioCodec = findViewById(R.id.switch_audio_codec)
        switchDescription = findViewById(R.id.switch_description)
        switchNextProgram = findViewById(R.id.switch_next_program)

        sideChannelAdapter = SideChannelAdapter(
            onClick = { ch -> selectSideChannel(ch.id) },
            onFocus = { ch -> updateSideProgramDetails(ch.id) },
            fontScale = prefs.channelPanelFont,
        )
sideChannelsList = findViewById(R.id.side_channels_list)
        sideChannelsList.layoutManager = LinearLayoutManager(this)
        sideChannelsList.adapter = sideChannelAdapter
        sideContainer = findViewById(R.id.side_container)
        sideProgramTitle = findViewById(R.id.side_program_title)
        sideProgramTime = findViewById(R.id.side_program_time)
        sideProgramDescription = findViewById(R.id.side_program_description)
        sideScheduleList = findViewById(R.id.side_schedule_list)
        sideScheduleEmpty = findViewById(R.id.side_schedule_empty)
        sideScheduleAdapter = SideScheduleAdapter(fontScale = prefs.infoPanelFont)
        sideScheduleList.layoutManager = LinearLayoutManager(this)
        sideScheduleList.adapter = sideScheduleAdapter

        osdHeader = findViewById(R.id.osd_header)
        osdLogo = findViewById(R.id.osd_logo)
        osdChannelName = findViewById(R.id.osd_channel_name)
        osdGroup = findViewById(R.id.osd_group)
        osdClock = findViewById(R.id.osd_clock)
        osdPanel = findViewById(R.id.osd_panel)
        panelSeek = findViewById(R.id.panel_seek)
        panelButtons = findViewById(R.id.panel_buttons)
        infoPanel = findViewById(R.id.info_panel)
        infoLogo = findViewById(R.id.info_logo)
        infoChannelName = findViewById(R.id.info_channel_name)
        infoGroup = findViewById(R.id.info_group)
        infoProgramTitle = findViewById(R.id.info_program_title)
        infoProgramTime = findViewById(R.id.info_program_time)
        infoNextProgram = findViewById(R.id.info_next_program)
        infoDescription = findViewById(R.id.info_description)
        infoStream = findViewById(R.id.info_stream)
        bufferingView = findViewById(R.id.buffering)
        messageView = findViewById(R.id.player_message)

        captureFontScale(switchProgramTitle) { prefs.bottomPanelFont }
        captureFontScale(switchTime) { prefs.bottomPanelFont }
        captureFontScale(switchRemaining) { prefs.bottomPanelFont }
        captureFontScale(switchChannelName) { prefs.bottomPanelFont }
        captureFontScale(switchNumber) { prefs.bottomPanelFont }
        captureFontScale(switchQuality) { prefs.bottomPanelFont }
        captureFontScale(switchFps) { prefs.bottomPanelFont }
        captureFontScale(switchAudio) { prefs.bottomPanelFont }
captureFontScale(switchVideoCodec) { prefs.bottomPanelFont }
captureFontScale(switchAudioCodec) { prefs.bottomPanelFont }
        captureFontScale(switchDescription) { prefs.bottomPanelFont }
        captureFontScale(switchNextProgram) { prefs.bottomPanelFont }

        captureFontScale(sideProgramTitle) { prefs.infoPanelFont }
        captureFontScale(sideProgramTime) { prefs.infoPanelFont }
        captureFontScale(sideProgramDescription) { prefs.infoPanelFont }
        captureFontScale(findViewById(R.id.side_schedule_header)) { prefs.infoPanelFont }
        captureFontScale(sideScheduleEmpty) { prefs.infoPanelFont }

        applyPanelFontScales()
    }

    /** Base sp sizes captured once, so re-applying a scale never compounds. */
    private class FontScaledView(val view: TextView, val baseSp: Float, val scale: () -> Float)

    private val fontScaledViews = mutableListOf<FontScaledView>()

    private fun captureFontScale(view: TextView, scale: () -> Float) {
        fontScaledViews += FontScaledView(view, view.textSize / resources.displayMetrics.scaledDensity, scale)
    }

    private fun applyPanelFontScales() {
        for (item in fontScaledViews) {
            item.view.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.baseSp * item.scale())
        }
        sideChannelAdapter.setFontScale(prefs.channelPanelFont)
        sideScheduleAdapter.setFontScale(prefs.infoPanelFont)
    }

    private fun buildPanelButtons() {
        panelButtons.removeAllViews()
        val buttons = listOf(
            PanelButton(R.drawable.ic_close, R.string.close) { dismissPanels() },
            PanelButton(R.drawable.ic_rewind, R.string.action_rewind) { seekRelative(-prefs.seekStepRw) },
            PanelButton(R.drawable.ic_play, R.string.play_pause) { togglePlay() },
            PanelButton(R.drawable.ic_ffwd, R.string.action_fast_forward) { seekRelative(prefs.seekStepFf) },
            PanelButton(R.drawable.ic_info, R.string.show_info_panel) { showInfo() },
            PanelButton(R.drawable.ic_timer, R.string.sleep_timer) { showSleepTimer() },
            PanelButton(R.drawable.ic_aspect, R.string.aspect_ratio) {
                cycleAspectMode()
            },
            PanelButton(R.drawable.ic_catchup, R.string.catchup) { showCatchup() },
            PanelButton(R.drawable.ic_star, R.string.add_to_favorites) { toggleFavorite() },
            PanelButton(R.drawable.ic_stop, R.string.stop_playback) { stopPlayback() },
            PanelButton(R.drawable.ic_settings, R.string.nav_settings) { openSettings() },
        )
        buttons.forEach { button ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_player_button, panelButtons, false)
            view.findViewById<ImageView>(R.id.button_icon).setImageResource(button.icon)
            view.findViewById<TextView>(R.id.button_label).setText(button.label)
            view.setOnClickListener { button.action() }
            panelButtons.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        panelButtons.post { panelButtons.getChildAt(0)?.requestFocus() }
    }

    private data class PanelButton(val icon: Int, val label: Int, val action: () -> Unit)

    // -------------------------------------------------------------- playback

    private fun loadChannel(channelId: Long, fromStart: Boolean = false) {
        isSwitching = true
        clearBuffering.run()
        bufferingView.visible(true)
        armBufferingTimeout(channelId)
        requestedChannelId = channelId
        sideChannelAdapter.setSelected(channelId)
        executor.execute {
            val ch = repo.channel(channelId)
            val pl = ch?.let { repo.playlist(it.playlistId) }
            val prog = ch?.let { repo.currentProgram(it.id) }
            main.post {
                if (ch == null) {
                    isSwitching = false
                    showMessage(getString(R.string.channel_is_unavailable))
                    return@post
                }
                channel = ch
                playlist = pl
                program = prog
                videoHeight = 0
                watchStart = System.currentTimeMillis()
                watchTimeMs = 0
                repo.addHistory(ch.id, 0)
                val index = channelIds.indexOfFirst { it == ch.id }
                if (index >= 0) currentIndex = index
                prepareEngine(ch, pl, fromStart)
                updateOsd()
                showSwitchPanel()
                isSwitching = false
            }
        }
    }

    private fun prepareEngine(ch: Channel, pl: Playlist?, fromStart: Boolean) {
        val url = resolveUrl(ch, pl)
        if (url.isBlank()) {
            showMessage(getString(R.string.channel_is_unavailable))
            return
        }
        val userAgent = pl?.userAgent.orEmpty()
        val start = if (fromStart || ch.progressMs <= 0) 0L else ch.progressMs

        // engine setup can fail on exotic devices or bad settings: report it instead
        // of letting the exception kill the whole process. If VLC cannot be initialized,
        // fall back to ExoPlayer so playback still works.
        try {
            if (!::engine.isInitialized) {
                engine = createEngine(preferVlc = prefs.engine == "vlc")
                wireEngine()
            }
            // Keep the player's internal volume at 1.0 and let the system stream
            // volume control the loudness. Mirroring the system volume here used to
            // mute playback whenever the system stream volume was low.
                val focusResult = requestAudioFocus()
                Log.d("TvibroPlayer", "audio focus request result=$focusResult, stream vol=" +
                        "${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
                engine.setVolume(1f)
                engine.prepare(url, userAgent, start)
        } catch (t: Throwable) {
            Log.e("TvibroPlayer", "engine init failed for ${ch.name}", t)
            if (::engine.isInitialized) {
                runCatching { engine.release() }
                engineHolder.removeAllViews()
            }
            showMessage(errorMessage(t))
            finish()
        }
    }

    private fun tryVlcFallback() {
        val ch = channel ?: return
        val pl = playlist
        if (::engine.isInitialized) {
            runCatching { engine.release() }
            engineHolder.removeAllViews()
        }
        try {
            engine = createEngine(preferVlc = true)
            wireEngine()
            val url = resolveUrl(ch, pl)
            if (url.isBlank()) {
                showMessage(getString(R.string.channel_is_unavailable))
                return
            }
            val userAgent = pl?.userAgent.orEmpty()
            val start = if (ch.progressMs <= 0) 0L else ch.progressMs
            requestAudioFocus()
            engine.setVolume(1f)
            engine.prepare(url, userAgent, start)
        } catch (t: Throwable) {
            Log.e("TvibroPlayer", "VLC fallback failed", t)
            if (::engine.isInitialized) {
                runCatching { engine.release() }
                engineHolder.removeAllViews()
            }
            showMessage(errorMessage(t))
        }
    }

    private fun createEngine(preferVlc: Boolean): PlaybackEngine {
        if (preferVlc) {
            try {
                val vlc = VlcEngine(this, engineHolder)
                exoEngine = null
                return vlc
            } catch (t: Throwable) {
                Log.e("TvibroPlayer", "VLC init failed, falling back to ExoPlayer", t)
                engineHolder.removeAllViews()
            }
        }
        val exo = ExoEngine(
            this, engineHolder,
            bufferMs = prefs.bufferSizeMs,
            tunneled = prefs.tunneledPlayback,
        )
        exo.setPassthrough(prefs.audioPassthrough)
        exo.setHardwareDecoder(prefs.videoDecoder != "software")
        exoEngine = exo
        return exo
    }

    private fun errorMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: getString(R.string.channel_is_unavailable)

    private fun wireEngine() {
        engine.onReady = {
            main.post {
                clearBuffering.run()
            }
        }
        engine.onBuffering = { buffering ->
            main.post {
                bufferingView.visible(buffering)
                if (buffering && bufferingChannelId >= 0) armBufferingTimeout(bufferingChannelId)
            }
        }
        engine.onError = { error ->
            main.post {
                clearBuffering.run()
                if (error == "Audio codec not supported" && exoEngine != null) {
                    Log.d("TvibroPlayer", "audio codec unsupported, falling back to VLC")
                    tryVlcFallback()
                } else {
                    showMessage(getString(R.string.playback_failed))
                    toast(error, long = true)
                }
            }
        }
        engine.onEnd = {
            main.post {
                clearBuffering.run()
                nextChannel()
            }
        }
        engine.onVideoSize = { _, height ->
            main.post {
                videoHeight = height
                updateStreamBadges()
                if (infoPanel.visibility == View.VISIBLE) updateInfoPanel()
            }
        }
    }

    private fun resolveUrl(ch: Channel, pl: Playlist?): String {
        if (ch.isVod && ch.url.isNotEmpty()) return ch.url
        if (pl == null) return ch.url
        return runCatching { TvBroApp.get().sources.streamUrl(pl, ch) }.getOrDefault(ch.url)
    }

    // ------------------------------------------------------------------- OSD

    private fun startTicker() {
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                updatePanel()
                updateClock(now)
                if (sleepTimerAt > 0 && now >= sleepTimerAt) {
                    sleepTimerAt = 0
                    stopPlayback()
                }
                if (panelTimeout > 0 && now >= panelTimeout) hidePanels()
                if (switchTimeout > 0 && now >= switchTimeout) {
                    switchTimeout = 0
                    switchPanel.visibility = View.GONE
                }
                // stream metadata arrives asynchronously; keep the per-channel cache warm
                // even while the panel is hidden, then refresh badges while it is up
                if (::engine.isInitialized && channel != null) {
                    cacheStreamMeta()
                    if (switchPanel.visibility == View.VISIBLE) updateStreamBadges()
                }
      if (watchStart > 0 && engine.isPlaying()) watchTimeMs += 1000
      // Keep the EPG progress bars in the channel list moving while it stays open.
      if (sideChannelsVisible && now - lastSideProgramsAt > SIDE_PROGRAMS_REFRESH_MS) {
        refreshSidePrograms()
        if (sideFocusedChannelId > 0) {
          loadSideProgramDetails(sideFocusedChannelId)
          sideScheduleAdapter.refreshNow()
        }
      }
      main.postDelayed(this, 1000L)
            }
        }
        main.post(tick)
    }

    private fun updateClock(now: Long) {
        osdClock.text = clockFormat.format(Date(now))
    }

    private fun updatePanel() {
        val ch = channel ?: return
        if (!::engine.isInitialized) return
        val pos = engine.positionMs()
        val dur = engine.durationMs()
        val prog = program

        if (dur > 0) {
            panelSeek.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
        } else if (prog != null) {
            val now = System.currentTimeMillis()
            // panel_seek works on a 0..1000 scale (see the playback branch above), while
            // Fmt.percent returns 0..100, so the EPG progress has to be scaled to match.
            panelSeek.progress = (Fmt.percent(prog.start, prog.stop, now) * 10).coerceIn(0, 1000)
        } else {
            panelSeek.progress = 0
        }
        updateInfoPanel()
    }

    private fun updateOsd() {
        val ch = channel ?: return
        osdChannelName.text = ch.name
        osdGroup.text = buildString {
            append(ch.groupTitle)
            if (ch.number.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(ch.number)
            }
            if (playlist != null) {
                if (isNotEmpty()) append(" · ")
                append(playlist!!.name)
            }
        }
        if (ch.logoUrl.isNotBlank()) {
            osdLogo.load(ch.logoUrl) {
                placeholder(R.drawable.ic_logo_channel)
                error(R.drawable.ic_logo_channel)
            }
        } else {
            osdLogo.setImageResource(R.drawable.ic_logo_channel)
        }
    }

    private fun showSwitchPanel() {
        val ch = channel ?: return
        // The panel stays on screen while the user keeps switching channels, so its content
        // has to be refilled for the new channel even when it is already visible. Only the
        // badges used to be refreshed, by the ticker, which left the rest of the panel stale.
        val alreadyVisible = switchPanel.visibility == View.VISIBLE
        val prog = program
        val now = System.currentTimeMillis()

        switchProgramTitle.text = prog?.title ?: ch.name
        switchChannelName.text = ch.name
        switchNumber.text = ch.number
        switchNumber.visible(ch.number.isNotEmpty())

        if (prog != null) {
            switchTime.text = "${Fmt.time(prog.start)} - ${Fmt.time(prog.stop)}"
            switchProgress.progress = Fmt.percent(prog.start, prog.stop, now)
            switchProgress.visible(true)
            switchRemaining.text = Fmt.remainingText(prog.stop, now)
            switchRemaining.visible(true)
        } else {
            switchTime.text = ""
            switchProgress.visible(false)
            switchRemaining.visible(false)
        }

        if (ch.logoUrl.isNotBlank()) {
            switchLogo.load(ch.logoUrl) {
                placeholder(R.drawable.ic_logo_channel)
                error(R.drawable.ic_logo_channel)
            }
        } else {
            switchLogo.setImageResource(R.drawable.ic_logo_channel)
        }

        updateStreamBadges()

        val description = Fmt.shortDescription(prog?.description, maxChars = 2000)
        switchDescription.text = description
        switchDescription.visible(description.isNotEmpty())
        switchDescription.maxLines = prefs.switchDescriptionMaxLines

        switchNextProgram.visible(false)

        if (prefs.showInfoOnSwitch) {
            switchPanel.visible(true)
            if (alreadyVisible) {
                resetSwitchTimeout()
            } else {
                switchTimeout = System.currentTimeMillis() + (prefs.displayChangeTimeout * 1000L)
                if (!sideChannelsVisible) switchPanel.requestFocus()
            }
            if (prefs.showDescriptionOnSwitch) updateInfoPanel()
        }
        if (prefs.showBlackScreen) {
            showMessage(getString(R.string.stream_buffering))
            armBufferingTimeout(ch.id)
        }

        val channelId = ch.id
        executor.execute {
            val next = repo.programsFor(channelId, now, now + 12 * 3600_000L).firstOrNull()
            main.post {
                if (channel?.id == channelId && next != null) {
                    switchNextProgram.text = "${Fmt.time(next.start)} - ${Fmt.time(next.stop)}  ${next.title}"
                    switchNextProgram.visible(true)
                }
            }
        }
    }

    private fun cacheStreamMeta() {
        if (!::engine.isInitialized) return
        val ch = channel ?: return
        val meta = streamMetaCache.getOrPut(ch.id) { StreamMeta() }
        // refresh the cache with whatever the current stream reports
        val size = engine.videoSize()
        if (size != null) {
            meta.width = size.first
            meta.height = size.second
        }
        engine.videoFps()?.let { meta.fps = it }
        engine.audioChannels()?.let { meta.audio = it }
        engine.videoCodecLabel()?.let { meta.videoCodec = it }
        engine.audioCodecLabel()?.let { meta.audioCodec = it }
        Log.d("TvibroBadges", "ch=${ch.name} size=$size cached=${meta.width}x${meta.height} fps=${meta.fps} audio=${meta.audio} vcodec=${meta.videoCodec} acodec=${meta.audioCodec} vH=$videoHeight eng=${engine.javaClass.simpleName}")
    }

    private fun updateStreamBadges() {
        if (!::engine.isInitialized) return
        val ch = channel ?: return
        val meta = streamMetaCache.getOrPut(ch.id) { StreamMeta() }
        cacheStreamMeta()

        val height = if (meta.height > 0) meta.height else videoHeight
        val quality = if (prefs.showVideoResolution) {
            when {
                meta.resolution != null -> meta.resolution
                videoHeight > 0 -> "${videoHeight}p"
                else -> Fmt.qualityLabel(ch.name, height)
            }
        } else {
            Fmt.qualityLabel(ch.name, height)
        }
        switchQuality.text = quality.orEmpty()
        switchQuality.visible(quality != null)

        switchFps.text = if (meta.fps != null) "${meta.fps!!.toInt()} FPS" else ""
        switchFps.visible(meta.fps != null)

        switchAudio.text = meta.audio?.let { audioLabel(it) }.orEmpty()
        switchAudio.visible(meta.audio != null)

        switchVideoCodec.text = meta.videoCodec.orEmpty()
        switchVideoCodec.visible(meta.videoCodec != null)

        switchAudioCodec.text = meta.audioCodec.orEmpty()
        switchAudioCodec.visible(meta.audioCodec != null)
    }

    private fun audioLabel(channels: Int): String = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6, 8 -> "5.1"
        else -> "$channels"
    }

    private fun showPanels() {
        osdHeader.visible(true)
        osdPanel.visible(true)
        panelTimeout = System.currentTimeMillis() + (prefs.panelsTimeout * 1000L)
        updateOsd()
        updatePanel()
        if (!osdPanel.hasFocus()) {
            panelButtons.post { panelButtons.getChildAt(0)?.requestFocus() }
        }
    }

    private fun updateSideProgramDetails(channelId: Long) {
        // Instant, cheap feedback straight from the already-bound row while scrolling.
        val index = sideChannelAdapter.indexOf(channelId)
        sideChannelAdapter.getChannel(index)?.let { sideProgramTitle.text = it.name }
        // Debounce the heavy side panel load: rapid focus changes only trigger the last one.
        if (channelId != sideProgramLoadId) {
            sideProgramLoadId = channelId
            main.removeCallbacks(sideProgramDebounce)
            main.postDelayed(sideProgramDebounce, SIDE_PROGRAM_DEBOUNCE_MS)
        }
    }

    private fun loadSideProgramDetails(channelId: Long) {
        val needSchedule = channelId != sideScheduleChannelId
        executor.execute {
            val ch = repo.channel(channelId) ?: return@execute
            val prog = repo.currentProgram(channelId)
            val now = System.currentTimeMillis()
            val schedule = if (needSchedule) {
                sideScheduleCache[channelId] ?: repo.programsFor(channelId, now - 6 * 3600_000L, now + 12 * 3600_000L)
                    .filter { it.stop > now }
                    .also { sideScheduleCache[channelId] = it }
            } else {
                null
            }
            main.post {
                // Drop stale results: the user may have moved on while the query ran.
                if (channelId != sideProgramLoadId) return@post
                sideFocusedChannelId = channelId
                sideProgramTitle.text = prog?.title ?: ch.name
                sideProgramTime.text = if (prog != null) {
                    "${Fmt.time(prog.start)} — ${Fmt.time(prog.stop)} · ${Fmt.remainingText(prog.stop, now)}"
                } else {
                    ""
                }
                val description = Fmt.shortDescription(prog?.description, maxChars = 2000)
                sideProgramDescription.text = description
                sideProgramDescription.visible(description.isNotEmpty())
                sideProgramDescription.maxLines = prefs.switchDescriptionMaxLines
                if (schedule != null) {
                    sideScheduleChannelId = channelId
                    sideScheduleAdapter.submit(schedule)
                    sideScheduleList.visible(schedule.isNotEmpty())
                    sideScheduleEmpty.visible(schedule.isEmpty())
                }
            }
        }
    }

    private fun refreshSidePrograms() {
        val ids = sideProgramChannelIds
        if (ids.isEmpty()) return
        lastSideProgramsAt = System.currentTimeMillis()
        executor.execute {
            val now = System.currentTimeMillis()
            val programs = ids.mapNotNull { id ->
                repo.currentProgram(id, now)?.let { prog ->
                    id to SideChannelAdapter.ProgramInfo(
                        title = prog.title,
                        progress = Fmt.percent(prog.start, prog.stop, now),
                    )
                }
            }.toMap()
            main.post { sideChannelAdapter.updatePrograms(programs) }
        }
    }

    private fun showSideChannels() {
        if (!::sideContainer.isInitialized) return
        if (sideChannelsVisible) return
        if (!sideChannelsLoaded) {
            executor.execute {
                    val channels = if (channelIds.isEmpty()) {
                        val playlistIds = repo.playlists(onlyEnabled = true).map { it.id }
                        repo.channels(
                            playlistIds = playlistIds,
                            group = "",
                            filter = ChannelFilter.TV,
                            sort = "order",
                        ).ifEmpty { channel?.let { listOf(it) } ?: emptyList() }
                    } else {
                        repo.channelsByIds(channelIds.toList())
                    }
                main.post {
                    if (channelIds.isEmpty()) {
                        navigationIds = channels.map { it.id }.toLongArray()
                    }
                    sideChannelAdapter.submit(channels)
                    sideChannelsLoaded = true
                    sideProgramChannelIds = channels.map { it.id }
                    refreshSidePrograms()
                    showSideChannels()
                }
            }
            return
        }
        hidePanels()
        infoPanel.visible(false)
        sideContainer.visible(true)
        val currentChannelId = channel?.id ?: requestedChannelId
        sideChannelAdapter.setSelected(currentChannelId)
        sideScheduleChannelId = -1L
        sideChannelsList.post {
            val index = sideChannelAdapter.indexOf(currentChannelId)
            val target = if (index >= 0) index else 0
            sideChannelsList.scrollToPosition(target)
            focusSideChannel(target)
        }
    }

    private fun focusSideChannel(position: Int, attemptsLeft: Int = 5) {
        if (sideChannelsList.hasPendingAdapterUpdates()) {
            if (attemptsLeft > 0) {
                sideChannelsList.post { focusSideChannel(position, attemptsLeft - 1) }
            }
            return
        }
        val holder = sideChannelsList.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            sideChannelPosition = position
            sideChannelAdapter.getChannel(position)?.let { updateSideProgramDetails(it.id) }
        } else if (attemptsLeft > 0) {
            sideChannelsList.post { focusSideChannel(position, attemptsLeft - 1) }
        }
    }

    private fun focusSideSchedule() {
        if (sideScheduleList.visibility != View.VISIBLE) {
            hideSideChannels()
            return
        }
        sideChannelPosition = focusedPosition(sideChannelsList, sideChannelPosition)
        focusSideScheduleRow(sideSchedulePosition)
    }

    private fun focusSideScheduleRow(position: Int, attemptsLeft: Int = 5) {
        if (sideScheduleList.hasPendingAdapterUpdates()) {
            if (attemptsLeft > 0) {
                sideScheduleList.post { focusSideScheduleRow(position, attemptsLeft - 1) }
            }
            return
        }
        val holder = sideScheduleList.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            sideSchedulePosition = position
        } else if (attemptsLeft > 0) {
            sideScheduleList.post { focusSideScheduleRow(position, attemptsLeft - 1) }
        }
    }

    private fun focusedPosition(list: RecyclerView, fallback: Int): Int =
        list.focusedChild
            ?.let { list.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: fallback

    private fun hideSideChannels() {
        if (!::sideContainer.isInitialized) return
        sideContainer.visible(false)
        root.requestFocus()
    }

    private fun selectSideChannel(channelId: Long) {
        if (channelId != (channel?.id ?: requestedChannelId)) {
            loadChannel(channelId)
        }
        if (!prefs.stayOnList) {
            hideSideChannels()
        }
    }

    private fun hidePanels() {
        panelTimeout = 0
        osdHeader.visible(false)
        osdPanel.visible(false)
        infoPanel.visible(false)
        switchPanel.visible(false)
        switchTimeout = 0
    }

    private fun dismissPanels() {
        hidePanels()
        root.requestFocus()
    }

    private fun showInfo() {
        if (infoPanel.visibility == View.VISIBLE) {
            infoPanel.visible(false)
            return
        }
        updateInfoPanel()
        infoPanel.visible(true)
        infoPanel.requestFocus()
    }

    private fun updateInfoPanel() {
        val ch = channel ?: return
        if (!::engine.isInitialized) return
        infoChannelName.text = ch.name
        infoGroup.text = buildString {
            append(ch.groupTitle)
            if (ch.number.isNotEmpty()) append(" · ").append(ch.number)
            if (prefs.showVideoResolution) {
                engine.videoSize()?.let { append(" · ").append(it.first).append('x').append(it.second) }
            }
        }
        val prog = program
        infoProgramTitle.text = prog?.title ?: getString(R.string.no_programs)
        val now = System.currentTimeMillis()
        infoProgramTime.text = if (prog != null) {
            "${Fmt.timeRange(prog.start, prog.stop, Locale.getDefault())} · " +
                "${Fmt.percent(prog.start, prog.stop, now)}% · ${Fmt.remainingText(prog.stop, now)}"
        } else {
            getString(R.string.no_information)
        }
        executor.execute {
            val next = ch.id.let { repo.programsFor(it, now, now + 12 * 3600_000L) }.firstOrNull()
            main.post {
                if (channel?.id != ch.id) return@post
                infoNextProgram.text = if (next != null) {
                    getString(R.string.next_program) + ": " + next.title + " (" + Fmt.time(next.start) + ")"
                } else {
                    getString(R.string.no_next_program)
                }
            }
        }
        infoDescription.text = prog?.description.orEmpty()
        infoDescription.maxLines = prefs.switchDescriptionMaxLines
        if (prefs.showMediaProperties) {
            val size = engine.videoSize()
            infoStream.text = buildString {
                append(ch.url)
                if (size != null) append('\n').append(size.first).append('x').append(size.second)
            }
            infoStream.visible(true)
        } else {
            infoStream.visible(false)
        }
        if (ch.logoUrl.isNotBlank()) {
            infoLogo.load(ch.logoUrl) {
                placeholder(R.drawable.ic_logo_channel)
                error(R.drawable.ic_logo_channel)
            }
        } else {
            infoLogo.setImageResource(R.drawable.ic_logo_channel)
        }
    }

    private fun showMessage(message: String?) {
        if (message.isNullOrEmpty()) {
            messageView.visible(false)
        } else {
            messageView.text = message
            messageView.visible(true)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun togglePlay() {
        if (!::engine.isInitialized) return
        if (engine.isPlaying()) {
            engine.pause()
            toast(getString(R.string.pause))
        } else {
            engine.play()
            toast(getString(R.string.play))
        }
    }

    private fun seekRelative(seconds: Int) {
        if (!::engine.isInitialized) return
        val delta = seconds * 1000L
        val target = (engine.positionMs() + delta).coerceAtLeast(0L)
        engine.seekTo(target)
        showPanels()
    }

    private fun changeVolume(delta: Float) {
        val direction = if (delta >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        showVolumeToast()
        showPanels()
    }

    private fun isFocusInsideOsd(): Boolean = osdPanel.hasFocus()

    private fun focusFirstPanelButton() {
        panelButtons.post { panelButtons.getChildAt(0)?.requestFocus() }
    }

    private fun cycleAspectMode() {
        if (!::engine.isInitialized) return
        engine.cyclesAspectMode()
        toast(engine.aspectModeLabel(this))
    }

    private fun resetPanelTimeout() {
        if (osdPanel.visibility == View.VISIBLE) {
            panelTimeout = System.currentTimeMillis() + (prefs.panelsTimeout * 1000L)
        }
    }

    private fun resetSwitchTimeout() {
        if (switchPanel.visibility == View.VISIBLE) {
            switchTimeout = System.currentTimeMillis() + (prefs.displayChangeTimeout * 1000L)
        }
    }

    private fun performRemoteAction(action: String) {
        when (action) {
            "show_info" -> {
                showPanels()
                showSwitchPanel()
            }
            "show_channels" -> showSideChannels()
            "volume_up" -> changeVolume(0.1f)
            "volume_down" -> changeVolume(-0.1f)
            "prev_channel" -> previousChannel()
            "next_channel" -> nextChannel()
            "pause" -> togglePlay()
            "show_menu" -> showMainMenu()
            else -> { /* none */ }
        }
    }

    private fun requestAudioFocus(): Int {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AndroidAudioAttributes.Builder()
                            .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                            .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }
    }

    private fun showVolumeToast() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) (current * 100 / max) else 0
        toast(getString(R.string.volume) + ": $percent%")
    }

    private fun toggleFavorite() {
        val ch = channel ?: return
        executor.execute {
            repo.setChannelFlags(ch.id, favorite = !ch.favorite)
            main.post { toast(getString(if (ch.favorite) R.string.removed_from_favorites else R.string.added_to_favorites)) }
        }
    }

    private fun showSleepTimer() {
        val values = intArrayOf(0, 15, 30, 60, 90, 120)
        val labels = values.map {
            if (it == 0) getString(R.string.sleep_timer_is_off)
            else getString(R.string.sleep_timer_set, it)
        }
        Dialogs.show(
            this,
            getString(R.string.sleep_timer),
            null,
            labels.mapIndexed { index, label -> Dialogs.Item(label, checked = values[index] * 60_000L == sleepTimerAt) }
        ) { which ->
            val value = values[which]
            sleepTimerAt = if (value == 0) 0L else System.currentTimeMillis() + value * 60_000L
            toast(
                if (value == 0) getString(R.string.sleep_timer_is_off)
                else getString(R.string.sleep_timer_set, value)
            )
        }
    }

    private fun showCatchup() {
        val ch = channel ?: return
        if (ch.catchupSource.isBlank() || ch.catchupDays <= 0) {
            toast(getString(R.string.no_catchup_available))
            return
        }
        val now = System.currentTimeMillis()
        val from = now - ch.catchupDays * 86_400_000L
        executor.execute {
            val programs = repo.programsFor(ch.id, from, now)
            main.post {
                if (programs.isEmpty()) {
                    toast(getString(R.string.no_catchup_available))
                    return@post
                }
                val items = programs.map { Dialogs.Item(it.title, Fmt.timeRange(it.start, it.stop, Locale.getDefault())) }
                Dialogs.show(this, ch.name, getString(R.string.catchup), items) { which ->
                    val p = programs[which]
                    val url = CatchupResolver.resolve(ch, p)
                    if (url.isBlank()) {
                        toast(getString(R.string.no_catchup_available))
                    } else {
                        engine.seekTo(0)
                        engine.pause()
                        playExternal(url, ch.name)
                    }
                }
            }
        }
    }

    private fun playExternal(url: String, title: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(url), "video/*")
            .putExtra("title", title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (prefs.externalPlayerPackage.isNotEmpty()) intent.setPackage(prefs.externalPlayerPackage)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.external_players_not_found), long = true)
        }
    }

    private fun stopPlayback() {
        saveWatchTime()
        val result = Intent()
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun openSettings() {
        saveWatchTime()
        keepPlayingBehind = true
        startActivitySafely(Intent(this, SettingsActivity::class.java))
    }

    private fun nextChannel() {
        stepChannel(1)
    }

    private fun previousChannel() {
        stepChannel(-1)
    }

    private fun stepChannel(delta: Int) {
        if (isSwitching) return
        val known = channelIds.takeIf { it.isNotEmpty() }
        if (known != null) {
            val from = currentIndex
            val to = (from + delta + known.size) % known.size
            if (to == from) return
            currentIndex = to
            switchToChannel(known[to])
            return
        }
        // A direct launch ("turn on last channel") carries only the channel id, with no
        // playlist order, so navigation falls back to the channel order the side panel shows.
        withNavigationIds { ids ->
            if (ids.isEmpty()) {
                stopPlayback()
                return@withNavigationIds
            }
            val current = channel?.id ?: requestedChannelId
            val pos = ids.indexOfFirst { it == current }
            if (pos < 0) return@withNavigationIds
            val to = (pos + delta + ids.size) % ids.size
            if (to == pos) return@withNavigationIds
            currentIndex = to
            switchToChannel(ids[to])
        }
    }

    private fun withNavigationIds(action: (LongArray) -> Unit) {
        channelIds.takeIf { it.isNotEmpty() }?.let {
            action(it)
            return
        }
        navigationIds?.let {
            action(it)
            return
        }
        if (navigationIdsLoading) return
        navigationIdsLoading = true
        executor.execute {
            val playlistIds = repo.playlists(onlyEnabled = true).map { it.id }
            val ids = repo.channels(
                playlistIds = playlistIds,
                group = "",
                filter = ChannelFilter.TV,
                sort = "order",
            ).map { it.id }.toLongArray()
            main.post {
                navigationIdsLoading = false
                navigationIds = ids
                action(ids)
            }
        }
    }

    private fun switchToChannel(channelId: Long) {
        if (channelId == (channel?.id ?: requestedChannelId)) return
        saveWatchTime()
        if (prefs.switchDelay > 0) {
            showMessage(getString(R.string.press_again_to_switch))
            main.postDelayed({ loadChannel(channelId) }, prefs.switchDelay * 1000L)
        } else {
            loadChannel(channelId)
        }
    }

    private fun saveWatchTime() {
        val ch = channel ?: return
        if (watchTimeMs <= 1000 || !::engine.isInitialized) return
        executor.execute {
            repo.addWatchTime(ch.id, watchTimeMs)
            repo.addHistory(ch.id, watchTimeMs)
            repo.setProgress(ch.id, engine.positionMs(), engine.durationMs())
        }
    }

    // ------------------------------------------------------------------ input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && osdPanel.visibility == View.VISIBLE && !sideChannelsVisible) {
            resetPanelTimeout()
            if (switchPanel.visibility == View.VISIBLE) resetSwitchTimeout()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val ch = channel
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (sideChannelsVisible) {
                    val holder = sideChannelsList.findFocus() as? SideChannelAdapter.Holder
                    val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    sideChannelAdapter.getChannel(position)?.let { selectSideChannel(it.id) }
                    return true
                }
                if (isFocusInsideOsd()) {
                    currentFocus?.takeIf { it !== osdPanel && it !== panelButtons }?.performClick()
                    resetPanelTimeout()
                    return true
                }
                val action = prefs.remoteCenterAction
                if (action == "show_info" && (infoPanel.visibility == View.VISIBLE || switchPanel.visibility == View.VISIBLE)) {
                    hidePanels()
                    return true
                }
                performRemoteAction(action)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (sideChannelsVisible) {
                    if (sideScheduleList.hasFocus()) {
                        sideSchedulePosition = focusedPosition(sideScheduleList, sideSchedulePosition)
                        focusSideChannel(sideChannelPosition)
                    } else {
                        hideSideChannels()
                    }
                    return true
                }
                if (osdPanel.visibility == View.VISIBLE) {
                    if (isFocusInsideOsd()) {
                        resetPanelTimeout()
                        return false
                    }
                    focusFirstPanelButton()
                    resetPanelTimeout()
                    return true
                }
                val action = prefs.remoteLeftAction
                if (action == "show_channels") {
                    showSideChannels()
                } else {
                    performRemoteAction(action)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (sideChannelsVisible) {
                    focusSideSchedule()
                    return true
                }
                if (osdPanel.visibility == View.VISIBLE) {
                    if (isFocusInsideOsd()) {
                        resetPanelTimeout()
                        return false
                    }
                    focusFirstPanelButton()
                    resetPanelTimeout()
                    return true
                }
                performRemoteAction(prefs.remoteRightAction)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (sideChannelsVisible) return false
                if (osdPanel.visibility == View.VISIBLE) {
                    previousChannel()
                    return true
                }
                performRemoteAction(prefs.remoteUpAction)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (sideChannelsVisible) return false
                if (osdPanel.visibility == View.VISIBLE) {
                    nextChannel()
                    return true
                }
                performRemoteAction(prefs.remoteDownAction)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                previousChannel()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                nextChannel()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                engine.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                engine.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-prefs.seekStepRw)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(prefs.seekStepFf)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showMainMenu()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                showInfo()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS -> {
                toast(getString(R.string.closed_captions_hint))
                return true
            }
            KeyEvent.KEYCODE_HOME -> {
                if (prefs.switchToPipOnHome && Build.VERSION.SDK_INT >= 26) {
                    enterPip()
                } else {
                    moveTaskToBack(true)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (sideChannelsVisible) {
                    hideSideChannels()
                    return true
                }
                if (infoPanel.visibility == View.VISIBLE) {
                    infoPanel.visible(false)
                    return true
                }
                if (osdPanel.visibility == View.VISIBLE || switchPanel.visibility == View.VISIBLE) {
                    hidePanels()
                    return true
                }
                stopPlayback()
                return true
            }
        }
        if (ch == null) return super.onKeyDown(keyCode, event)
        return super.onKeyDown(keyCode, event)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    private fun showMainMenu() {
        val ch = channel ?: return
        val items = listOf(
            Dialogs.Item(getString(R.string.show_info_panel)),
            Dialogs.Item(getString(R.string.show_menu)),
            Dialogs.Item(getString(R.string.sleep_timer)),
            Dialogs.Item(
                if (ch.favorite) getString(R.string.remove_from_favorites)
                else getString(R.string.add_to_favorites)
            ),
            Dialogs.Item(getString(R.string.catchup)),
            Dialogs.Item(getString(R.string.watch_from_start)),
            Dialogs.Item(getString(R.string.open_in_external_player)),
            Dialogs.Item(getString(R.string.aspect_ratio)),
            Dialogs.Item(getString(R.string.video_resolution)),
            Dialogs.Item(getString(R.string.exit)),
        )
        Dialogs.show(this, ch.name, null, items) { which ->
            when (which) {
                0 -> showInfo()
                1 -> showPanels()
                2 -> showSleepTimer()
                3 -> toggleFavorite()
                4 -> showCatchup()
                5 -> loadChannel(ch.id, fromStart = true)
                6 -> playExternal(resolveUrl(ch, playlist), ch.name)
                7 -> if (::engine.isInitialized) engine.cyclesAspectMode()
                8 -> prefs.showVideoResolution = !prefs.showVideoResolution
                9 -> stopPlayback()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        if (isInPipMode && !hidden) {
            hidden = true
            hidePanels()
        } else if (!isInPipMode && hidden) {
            hidden = false
            showPanels()
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_IDS = "channel_ids"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        const val EXTRA_STAY_ON_LIST = "stay_on_list"
        const val EXTRA_CATEGORY_INDEX = "category_index"

        /** Upper bound for the buffering spinner and label, so neither can get stuck. */
        const val BUFFERING_TIMEOUT_MS = 15_000L

        /** How often the channel list EPG progress is reloaded while the list is open. */
        const val SIDE_PROGRAMS_REFRESH_MS = 30_000L

        /** How long the focused channel's details wait before the heavy DB load starts. */
        const val SIDE_PROGRAM_DEBOUNCE_MS = 250L
    }
}

/** Last known decoded stream properties for a channel, used until the stream reports new ones. */
class StreamMeta {
    var width: Int = 0
    var height: Int = 0
        var fps: Float? = null
        var audio: Int? = null
        var videoCodec: String? = null
        var audioCodec: String? = null

    val resolution: String? get() =
        if (width > 0 && height > 0) "${width}x${height}" else null
}

