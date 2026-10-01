package com.tvibro.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.Fmt
import com.tvibro.base.toast
import com.tvibro.base.visible
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.Channel
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.Program
import com.tvibro.data.source.EpgProgress
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.main.guide.GuideChannelsAdapter
import com.tvibro.ui.main.guide.GuideRowsAdapter
import com.tvibro.ui.main.guide.TimeRulerView
import com.tvibro.ui.pin.PinActivity
import com.tvibro.ui.player.Playback
import com.tvibro.ui.player.PlayerActivity
import com.tvibro.ui.playlist.PlaylistWizardActivity
import com.tvibro.ui.search.SearchActivity
import com.tvibro.ui.settings.SettingsActivity
import com.tvibro.ui.vod.VodActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: TvBroRepository
    private lateinit var groupsAdapter: GroupsAdapter
    private lateinit var guideChannelsAdapter: GuideChannelsAdapter
    private lateinit var guideRowsAdapter: GuideRowsAdapter
    private lateinit var groupsList: RecyclerView
    private lateinit var groupsColumn: LinearLayout
    private lateinit var groupsButton: View
    private lateinit var menuButton: View
    private lateinit var guideChannelsList: RecyclerView
    private lateinit var programsList: RecyclerView
    private lateinit var menuStrip: LinearLayout
    private lateinit var gridContainer: LinearLayout
    private lateinit var timeRuler: TimeRulerView
    private lateinit var guideInfoPanel: LinearLayout
    private lateinit var guideInfoProgramme: LinearLayout
    private lateinit var guideInfoTitle: TextView
    private lateinit var guideInfoTime: TextView
    private lateinit var guideInfoDescription: TextView
    private lateinit var guideInfoProgress: ProgressBar
    private lateinit var guideInfoRemaining: TextView
    private lateinit var guideInfoPlayer: FrameLayout
    private lateinit var guideInfoSlot: FrameLayout
    private lateinit var guideInfoPlaceholder: ImageView
    private lateinit var nowLine: View
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
    private lateinit var statusEpg: TextView
    private lateinit var clockView: TextView
    private lateinit var clockDateView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-main").apply { isDaemon = true } }
    // A whole day of EPG for a large group is a heavy query, it must not block channel loading
    private val guideExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-guide").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private var categories: List<Category> = emptyList()
    private var currentChannels: List<Channel> = emptyList()
    private var guidePrograms: Map<Long, List<Program>> = emptyMap()
    private val guideCache = object : LinkedHashMap<String, Map<Long, List<Program>>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Long, List<Program>>>): Boolean =
            size > GUIDE_CACHE_MAX
    }
    private var guideLoadId = 0L
    private var syncingRows = false
    private var lastSyncPosition = RecyclerView.NO_POSITION
    private var lastSyncTop = 0
    private var restoreFocusPosition = RecyclerView.NO_POSITION
    private var gridStart = 0L
    private var loadedDays = GuideRowsAdapter.INITIAL_DAYS
    /** True while a longer range is being read, so the scroll does not ask for it over and over. */
    private var loadingMoreDays = false
    private var leftStage = STAGE_CONTENT
    private var answeredConfirm = 0L
    private var pendingFocus = true
    /** Crosshair target that still has to be reached, kept until the cell really holds the focus. */
    private var focusTargetChannel = RecyclerView.NO_POSITION
    private var focusTargetProgram: Program? = null
    private var firstResume = true
    private var pendingAutoPlay = false
    private var playerLaunched = false
    private var lastExitPress = 0L
    private var clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    private var isRefreshing = false
    private var tapDownX = 0f
    private var tapDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        prefs.applyFontScale()
        setContentView(R.layout.activity_main)
        repo = TvBroApp.repo(this)

        bindViews()
        buildMenuStrip()
        startClock()

        // A touch screen has no Left key, so every visible column carries the button that opens the
        // next hidden one. The buttons stay out of the focus chain, the remote goes on using its keys.
        groupsButton.setOnClickListener { openLeftColumn() }
        menuButton.setOnClickListener { openLeftColumn() }
        updateRevealButtons()

        groupsAdapter = GroupsAdapter(
            onClick = { index -> onCategorySelected(index) },
            onLongClick = { index -> onCategoryLongClick(index) },
        )
        groupsList.layoutManager = LinearLayoutManager(this)
        groupsList.adapter = groupsAdapter

        guideChannelsAdapter = GuideChannelsAdapter(
            onClick = { position -> onGuideChannelClick(position) },
            onLongClick = { position -> onChannelLongClick(position) },
            onFocus = { onGuideChannelFocus() },
        )
        guideChannelsList.layoutManager = LinearLayoutManager(this)
        guideChannelsList.adapter = guideChannelsAdapter

        guideRowsAdapter = GuideRowsAdapter(
            hourWidthPx = resources.getDimensionPixelSize(R.dimen.epg_hour_width),
            onProgramClick = { position, program -> onGuideProgramClick(position, program) },
            onCellHighlighted = { _, _ -> updateGuideInfo() },
            onCellFocused = { _, _ -> revealFocusedCell() },
            onOffsetChanged = {
                positionNowLine()
                timeRuler.setOffset(guideRowsAdapter.currentOffset())
                extendGuideIfNearEnd()
            },
        )
        programsList.layoutManager = LinearLayoutManager(this)
        programsList.adapter = guideRowsAdapter
        val timelineTouch = TimelineTouchListener()
        programsList.addOnItemTouchListener(timelineTouch)
        // The scale belongs to the grid, so dragging it scrolls the grid.
        timeRuler.setOnTouchListener(timelineTouch)

        // The channel column and the program grid hold the same rows, so they have to share one
        // vertical position: otherwise a channel ends up next to somebody else's programs.
        guideChannelsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                syncVerticalScroll(guideChannelsList, programsList)
            }
        })
        programsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                syncVerticalScroll(programsList, guideChannelsList)
            }
        })

        gridStart = Fmt.startOfDay(System.currentTimeMillis())
        timeRuler.setRange(gridStart, loadedDays)
        applyHighlighting()

        maybeAutoUpdate()

        if (savedInstanceState == null && prefs.turnOnLastChannel && !playerLaunched) {
            val lastId = runCatching { repo.lastWatchedChannelId() }.getOrNull()
            if (lastId != null && lastId > 0L) {
                firstResume = false
                playerLaunched = true
                val intent = Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, lastId)
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pendingFocus = true
        // The panel font size can be changed while Settings is open on top of the guide.
        applyGuideInfoFontScale()
        // The window can be a different size than it was at startup, so the strip is measured again.
        applyGuideInfoPanelSize()
        // A stream that the full screen player handed over keeps running, so the panel has to pick
        // the picture up again every time the guide comes back on top.
        attachMiniPlayer()
        watchEpgProgress()
        reload(autoPlay = firstResume && prefs.turnOnLastChannel)
        firstResume = false
    }

    override fun onPause() {
        super.onPause()
        // The line belongs to this window only: a background update keeps running, but nothing
        // here is left to show it in.
        TvBroApp.get().sources.stopWatching()
        statusEpg.visible(false)
        // Only the picture this screen owns is parked here. A stream that is on its way into the
        // player window must not be touched, the player starts playing it by itself.
        if (Playback.inGuide()) Playback.pause()
    }

    /**
     * The EPG update is normally started somewhere else, by the settings screen or by the worker,
     * and it outlives this window. The line therefore only listens to what the manager publishes
     * and gets the current state at once, so coming back to the guide does not lose a running update.
     */
    private fun watchEpgProgress() {
        TvBroApp.get().sources.watch { progress -> showEpgProgress(progress) }
    }

    private fun showEpgProgress(progress: EpgProgress?) {
        if (progress == null) {
            statusEpg.visible(false)
            return
        }
        // The file is read while it arrives and the programmes go straight into the database, so
        // there is no state between "downloading" and "parsing": what grows on screen is the
        // number of channels of the playlist that are already in.
        if (progress.channelsTotal <= 0) {
            statusEpg.text = getString(R.string.epg_updating)
            statusEpg.visible(true)
            return
        }
        val source = progress.label.ifBlank { getString(R.string.epg_updating) }
        statusEpg.text = getString(
            R.string.epg_progress_line,
            source,
            getString(R.string.epg_stage_parse),
            getString(R.string.epg_progress_channels, progress.channels, progress.channelsTotal),
        )
        statusEpg.visible(true)
    }

    /**
     * Coming back from the player, the window takes the focus for the first focusable view of the
     * tree - the "Today" button above the guide - and it does that after onResume, so a crosshair
     * that was set there would be thrown away again. The target is therefore re-applied as soon as
     * the window owns the focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyPendingFocus()
    }

    /**
     * The app left the screen. The picture in the strip is a live tuner, and a tuner serves one
     * channel at a time: a stream that only sits in the background would keep the source and no
     * other player could take it. So it is stopped here, and the slot goes back to being empty.
     * Another screen of this app covering the guide is not a background - the new window has already
     * started, so the app is still in the foreground and the stream keeps running.
     */
    override fun onStop() {
        super.onStop()
        if (Playback.inGuide() && !TvBroApp.get().inForeground) Playback.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        guideExecutor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        playerLaunched = false
        if (Playback.inGuide()) Playback.stop()
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        groupsList = findViewById(R.id.groups_list)
        groupsColumn = findViewById(R.id.groups_column)
        groupsButton = findViewById(R.id.groups_button)
        menuButton = findViewById(R.id.menu_button)
        guideChannelsList = findViewById(R.id.guide_channels_list)
        programsList = findViewById(R.id.programs_rows)
        menuStrip = findViewById(R.id.menu_strip)
        gridContainer = findViewById(R.id.grid_container)
        timeRuler = findViewById(R.id.time_ruler)
        guideInfoPanel = findViewById(R.id.guide_info_panel)
        guideInfoProgramme = findViewById(R.id.guide_info_programme)
        guideInfoTitle = findViewById(R.id.guide_info_title)
        guideInfoTime = findViewById(R.id.guide_info_time)
        guideInfoDescription = findViewById(R.id.guide_info_description)
        guideInfoProgress = findViewById(R.id.guide_info_progress)
        guideInfoRemaining = findViewById(R.id.guide_info_remaining)
        guideInfoPlayer = findViewById(R.id.guide_info_player)
        guideInfoSlot = findViewById(R.id.guide_info_slot)
        guideInfoPlaceholder = findViewById(R.id.guide_info_placeholder)
        // The picture is decoration: the remote has to walk the guide, never the video, so nothing
        // inside this slot may become a focus target.
        guideInfoPlayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        applyGuideInfoPanelSize()
        nowLine = findViewById(R.id.now_line)
        // Captured after the global font scale is applied, so the base sizes are the real ones.
        captureFontScale(guideInfoTitle) { prefs.infoPanelFont }
        captureFontScale(guideInfoTime) { prefs.infoPanelFont }
        captureFontScale(guideInfoRemaining) { prefs.infoPanelFont }
        captureFontScale(guideInfoDescription) { prefs.infoPanelFont }
        // A stream that is still running belongs into the panel as soon as the guide is back on top.
        attachMiniPlayer()
        emptyView = findViewById(R.id.empty_view)
        statusText = findViewById(R.id.status_text)
        statusEpg = findViewById(R.id.status_epg)
        clockView = findViewById(R.id.clock)
        clockDateView = findViewById(R.id.clock_date)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
    }

    private fun buildMenuStrip() {
        menuStrip.removeAllViews()
        val buttons = buildList {
            add(NavButton(R.drawable.ic_search, R.string.search) { openSearch() })
            add(NavButton(R.drawable.ic_star, R.string.nav_favorites) { selectCategoryByFilter(ChannelFilter.FAVORITES) })
            if (prefs.showHistoryButton) add(NavButton(R.drawable.ic_history, R.string.nav_history) { openHistory() })
            add(NavButton(R.drawable.ic_movie, R.string.nav_movies) { openVod() })
            add(NavButton(R.drawable.ic_settings, R.string.nav_settings) { openSettings() })
        }
        buttons.forEach { button -> menuStrip.addView(createNavView(button)) }
    }

    private fun applyHighlighting() {
        guideChannelsAdapter.setHighlightCurrent(prefs.highlightCurrentChannel)
        guideRowsAdapter.setHighlightCurrent(prefs.highlightCurrentPrograms)
    }

    private data class NavButton(val icon: Int, val label: Int, val action: () -> Unit)

    private fun createNavView(button: NavButton): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_nav_vertical, menuStrip, false)
        view.findViewById<android.widget.ImageView>(R.id.nav_icon).setImageResource(button.icon)
        view.findViewById<TextView>(R.id.nav_label).setText(button.label)
        view.setOnClickListener { button.action() }
        return view
    }

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                updateClock()
                main.postDelayed(this, 1000L)
            }
        }
        main.post(tick)
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        clockView.visible(prefs.showClock)
        clockDateView.visible(prefs.showClock && prefs.showDate)
        clockView.text = clockFormat.format(Date(now))
        clockDateView.text = dateFormat.format(Date(now))
        positionNowLine()
        updateGuideInfo()
    }

    // ----------------------------------------------------------------- data

    fun reload(autoPlay: Boolean = false) {
        pendingAutoPlay = autoPlay
        val selectedIndex = groupsAdapter.selectedIndex()
        executor.execute {
            val playlists = repo.playlists()
            val list = Categories.build(this, repo, playlists)
            val lastPlayed = repo.lastWatchedChannelId() ?: 0L
            main.post {
                categories = list
                groupsAdapter.submit(list, selectedIndex)
                guideChannelsAdapter.setCurrent(lastPlayed)
                if (list.isNotEmpty()) {
                    onCategorySelected(groupsAdapter.selectedIndex(), force = true)
                } else {
                    showEmpty(getString(R.string.no_playlists))
                }
            }
        }
    }

    private fun onCategorySelected(index: Int, force: Boolean = false) {
        val category = categories.getOrNull(index) ?: return
        groupsAdapter.select(index)
        titleView.text = category.playlistName.ifEmpty { category.name }
        subtitleView.text = if (category.playlistName.isNotEmpty()) category.name else getString(R.string.app_name)
        val sort = prefsFor(category)
        executor.execute {
            val channels = when (category.filter) {
                ChannelFilter.FAVORITES -> repo.channels(category.playlistIds, "", category.filter, "name")
                ChannelFilter.HISTORY -> repo.historyChannels(prefs.recentChannelCount)
                else -> repo.channels(category.playlistIds, category.group, category.filter, sort)
            }
            main.post {
                currentChannels = channels
                guideChannelsAdapter.submit(channels)
                if (channels.isEmpty()) {
                    showEmpty(if (categories.size == 1) getString(R.string.no_channels) else getString(R.string.there_is_no_channel_in_group))
                } else {
                    emptyView.visible(false)
                }
                statusText.text = getString(R.string.channels_count, channels.size)
                if (pendingAutoPlay) {
                    pendingAutoPlay = false
                    autoPlayLastChannel()
                }
                loadGuidePrograms(index)
            }
        }
    }

    private fun loadGuidePrograms(categoryIndex: Int) {
        val channels = currentChannels
        val loadId = ++guideLoadId
        if (channels.isEmpty()) {
            guidePrograms = emptyMap()
            guideRowsAdapter.setData(emptyList(), emptyMap())
            nowLine.visible(false)
            return
        }
        val key = "$categoryIndex:$loadedDays"
        guideCache[key]?.let { cached ->
            guidePrograms = cached
            applyGuideData(channels, cached)
            return
        }
        val from = gridStart
        guideExecutor.execute {
            val map = repo.programsForChannels(
                channels.map { it.id },
                from,
                from + loadedDays * GuideRowsAdapter.DAY_MS,
            )
            main.post {
                if (loadId != guideLoadId) return@post
                guideCache[key] = map
                guidePrograms = map
                applyGuideData(channels, map)
            }
        }
    }

    /**
     * Asks for the next day once the right edge of the window comes within an hour of the end of the
     * loaded range, so scrolling forward keeps running into the following days instead of stopping.
     * Called from the offset callback, which every scroll path goes through.
     */
    private fun extendGuideIfNearEnd() {
        if (loadingMoreDays || !guideRowsAdapter.canGrow()) return
        val viewport = programsList.width
        if (viewport <= 0) return
        val reached = guideRowsAdapter.currentOffset() + viewport
        if (reached >= guideRowsAdapter.loadedWidth() - guideRowsAdapter.hourWidthPx()) {
            loadMoreGuideDays()
        }
    }

    /**
     * Reaches for the next day as soon as the grid approaches the end of what it holds, so scrolling
     * to the right keeps going instead of stopping at a wall. Only days the database already holds
     * are read, and the axis is never grown past [GuideRowsAdapter.MAX_DAYS].
     *
     * The offset is restored afterwards: appending to the left of the current position would throw
     * the view back to "now" while the user is looking at tomorrow.
     */
    private fun loadMoreGuideDays() {
        if (loadingMoreDays || guideRowsAdapter.loadedDays() >= GuideRowsAdapter.MAX_DAYS) return
        val channels = currentChannels
        if (channels.isEmpty()) return
        val from = gridStart + guideRowsAdapter.loadedDays() * GuideRowsAdapter.DAY_MS
        loadingMoreDays = true
        val loadId = guideLoadId
        guideExecutor.execute {
            val map = repo.programsForChannels(
                channels.map { it.id },
                from,
                from + GuideRowsAdapter.DAY_MS,
            )
            main.post {
                loadingMoreDays = false
                if (loadId != guideLoadId) return@post
                val days = guideRowsAdapter.loadedDays()
                // Nothing was stored for that day, so the axis would end in empty space.
                if (map.isEmpty() || !guideRowsAdapter.canGrow()) return@post
                guideRowsAdapter.setRange(gridStart, days + 1)
                timeRuler.setRange(gridStart, days + 1)
                loadedDays = guideRowsAdapter.rangeDays()
                val merged = HashMap<Long, MutableList<Program>>(guidePrograms.size * 2)
                for ((id, list) in guidePrograms) merged[id] = ArrayList(list)
                for ((id, list) in map) merged.getOrPut(id) { ArrayList() }.addAll(list)
                val sorted = merged.mapValues { (_, list) -> list.sortedBy { it.start } }
                guidePrograms = sorted
                applyGuideData(channels, sorted, keepOffset = true)
            }
        }
    }

    /**
     * The rows of the grid are built during layout, so the timeline can only be positioned
     * once the rows exist - before that every horizontal scroll is a no-op.
     */
    private fun applyGuideData(
        channels: List<Channel>,
        programs: Map<Long, List<Program>>,
        keepOffset: Boolean = false,
    ) {
        val offsetBefore = guideRowsAdapter.currentOffset()
        guideRowsAdapter.setData(channels, programs)
        // The crosshair needs a built row, so where it has to land is only known now.
        if (pendingFocus) {
            pendingFocus = false
            prepareGuideFocus()
        }
        programsList.doOnPreDraw {
            if (keepOffset) guideRowsAdapter.setOffset(offsetBefore) else scrollTimelineToNow()
            // Last attempt of this layout pass: the window gives the focus to the first focusable
            // view of the tree ("Today") after onResume, so the crosshair has to be set again here.
            applyPendingFocus()
        }
        // A picked cell disappears together with the old data, so hand focus back to the row it
        // belonged to instead of leaving the remote with nothing to confirm.
        if (restoreFocusPosition != RecyclerView.NO_POSITION) {
            val position = restoreFocusPosition
            restoreFocusPosition = RecyclerView.NO_POSITION
            focusList(guideChannelsList, position)
        }
    }

    private fun scrollTimelineToNow() {
        val now = System.currentTimeMillis()
        guideRowsAdapter.setViewport(programsList.width)
        if (now < gridStart || now > gridStart + loadedDays * GuideRowsAdapter.DAY_MS) {
            guideRowsAdapter.setOffset(0)
            return
        }
        // Keep "now" a little inside the viewport, otherwise the marker ends up flush
        // against the divider on the left and reads as missing.
        val margin = (gridContainer.width * NOW_LINE_MARGIN).toInt()
        guideRowsAdapter.setOffset(guideRowsAdapter.pixelForTime(now) - margin)
    }

    private fun positionNowLine() {
        val now = System.currentTimeMillis()
        // The scale carries its own marker so the current time stays readable there too.
        timeRuler.setNow(now)
        if (!prefs.showCurrentTimeIndicator) {
            nowLine.visible(false)
            return
        }
        if (now < gridStart || now > gridStart + loadedDays * GuideRowsAdapter.DAY_MS) {
            nowLine.visible(false)
            return
        }
        val x = guideRowsAdapter.pixelForTime(now) - guideRowsAdapter.currentOffset()
        // "Now" can legitimately be scrolled out of sight. Hiding the marker beats leaving a
        // stray line glued to the edge, which reads as a rendering bug.
        if (x < 0 || x > gridContainer.width) {
            nowLine.visible(false)
            return
        }
        nowLine.visible(true)
        nowLine.translationX = x.toFloat()
    }

    // ------------------------------------------------------------- info panel

    /** Base sp sizes captured once, so re-applying a scale never compounds. */
    private class FontScaledView(val view: TextView, val baseSp: Float, val scale: () -> Float)

    private val fontScaledViews = mutableListOf<FontScaledView>()

    private fun captureFontScale(view: TextView, scale: () -> Float) {
        fontScaledViews += FontScaledView(view, view.textSize / resources.displayMetrics.scaledDensity, scale)
    }

    /** The guide panel follows the same "Info panel font size" setting as the player panel. */
    private fun applyGuideInfoFontScale() {
        for (item in fontScaledViews) {
            item.view.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.baseSp * item.scale())
        }
    }

    /**
     * Describes the programme the user is looking at. The pick wins when a cell was confirmed, then
     * the cell under the remote crosshair, so walking the grid with a remote is enough to read what
     * is on. The channel itself stays in the column on the left, so only the programme is repeated
     * here. The strip itself is never hidden: it holds the video slot, and its height must not
     * depend on whether a stream is running.
     */
    private fun updateGuideInfo() {
        if (!::guideInfoPanel.isInitialized) return
        val channel = currentChannels.getOrNull(infoChannelPosition())
        if (channel == null) {
            guideInfoTitle.text = getString(R.string.no_programs)
            guideInfoTime.text = ""
            guideInfoProgress.visible(false)
            guideInfoRemaining.visible(false)
            fillGuideDescription("")
            return
        }
        val program = guideRowsAdapter.selectedProgram()
            ?: guideRowsAdapter.focusedProgram()
            ?: programAt(channel, guideFocusTime())
        guideInfoTitle.text = program?.title ?: getString(R.string.no_programs)
        val now = System.currentTimeMillis()
        if (program == null) {
            guideInfoTime.text = getString(R.string.no_information)
            guideInfoProgress.visible(false)
            guideInfoRemaining.visible(false)
        } else {
            guideInfoTime.text = Fmt.timeRange(program.start, program.stop, Locale.getDefault())
            // The bar carries the progress, exactly as in the bottom panel of the player, so the
            // percentage does not have to be read as text. Both it and the countdown only mean
            // something while the programme is running.
            val running = now in program.start until program.stop
            guideInfoProgress.visible(running)
            guideInfoProgress.progress = Fmt.percent(program.start, program.stop, now)
            guideInfoRemaining.visible(running)
            guideInfoRemaining.text = Fmt.remainingText(program.stop, now)
        }
        val description = program?.description.orEmpty()
        fillGuideDescription(description)
    }

    /**
     * The strip is exactly as tall as the video slot, so the description is the part that gives
     * way: it takes the room left under the title and the time and is cut with an ellipsis. The
     * line count is measured from the real font metrics instead of being guessed, so a bigger
     * panel font shrinks the text instead of pushing the strip out of the layout.
     */
    private fun fillGuideDescription(description: String) {
        if (description.isEmpty()) {
            guideInfoDescription.text = ""
            return
        }
        guideInfoDescription.text = description
        guideInfoDescription.maxLines = descriptionLines()
    }

    /** How many description lines fit into the space the panel has left. */
    private fun descriptionLines(): Int {
        val available = guideInfoDescription.height
        if (available <= 0) return prefs.switchDescriptionMaxLines.coerceAtLeast(1)
        val lineHeight = guideInfoDescription.lineHeight
        if (lineHeight <= 0) return prefs.switchDescriptionMaxLines.coerceAtLeast(1)
        return (available / lineHeight).coerceAtLeast(1)
    }

    /**
     * Sizes the strip from the real height of the window instead of a dp constant, so it is a bit
     * less than a third of the screen on a TV and on a tablet alike. The video slot keeps 16:9 and
     * takes exactly that height, which makes the slot the reference: the description gives way, the
     * strip never changes its height. The grid does not get a fixed height either, so it simply
     * takes the room that is left.
     */
    private fun applyGuideInfoPanelSize() {
        val screen = screenHeight()
        if (screen <= 0) return
        val height = (screen * resources.getFloat(R.dimen.guide_info_panel_height)).toInt()
        if (height <= 0) return
        guideInfoPanel.updateLayoutParams<ViewGroup.LayoutParams> { this.height = height }
        guideInfoSlot.updateLayoutParams<LinearLayout.LayoutParams> {
            width = (height * 16f / 9f).toInt()
        }
    }

    /** Height of the window this activity lives in, in pixels. */
    private fun screenHeight(): Int {
        val decor = findViewById<View>(android.R.id.content)
        val measured = decor?.height ?: 0
        if (measured > 0) return measured
        val metrics = resources.displayMetrics
        return if (metrics.heightPixels > 0) metrics.heightPixels else decor?.measuredHeight ?: 0
    }

    /**
     * Puts the picture of a running stream into the panel. The engine is the very one the full
     * screen player used, so the channel is neither restarted nor reconnected - only its window
     * becomes small. The slot stays the same size in every state: with nothing playing it shows the
     * sign of a television, so the strip never jumps and never leaves a hole.
     */
    private fun attachMiniPlayer() {
        if (!::guideInfoPlayer.isInitialized) return
        val engine = Playback.engine()
        if (engine == null) {
            guideInfoPlayer.removeAllViews()
            guideInfoPlaceholder.visible(true)
            guideInfoPlayer.setOnClickListener(null)
            return
        }
        guideInfoPlaceholder.visible(false)
        Playback.attachTo(guideInfoPlayer)
        Playback.play()
        guideInfoPlayer.setOnClickListener { expandMiniPlayer() }
    }

    /**
     * A tap on the picture of the mini player is the way back into the full screen player. The
     * channel that is running is handed over, and the player takes the live engine over instead of
     * opening the stream again, so the picture continues at the position it had reached.
     */
    private fun expandMiniPlayer() {
        val id = Playback.channelId()
        if (id <= 0L) return
        val index = currentChannels.indexOfFirst { it.id == id }
        if (index >= 0) {
            play(currentChannels[index], currentChannels)
            return
        }
        // The stream belongs to another category, so the list of the guide cannot describe it: the
        // player gets the id alone and looks the channel up on its own.
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, id)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_IDS, longArrayOf(id))
                .putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, 0)
                .putExtra(PlayerActivity.EXTRA_STAY_ON_LIST, prefs.stayOnList)
                .putExtra(PlayerActivity.EXTRA_CATEGORY_INDEX, groupsAdapter.selectedIndex())
        )
    }

    /** Pick first, then the focused cell, then the focused row, then the highlighted channel. */
    private fun infoChannelPosition(): Int {
        val selected = guideRowsAdapter.selectedChannelPosition()
        if (selected in currentChannels.indices) return selected
        val focusedCell = guideRowsAdapter.focusedChannelPosition()
        if (focusedCell in currentChannels.indices) return focusedCell
        val focused = guideChannelsAdapter.focusedPosition()
        if (focused in currentChannels.indices) return focused
        val current = currentChannels.indexOfFirst { it.id == guideChannelsAdapter.currentId() }
        return if (current >= 0) current else 0
    }

    private fun programAt(channel: Channel, time: Long): Program? {
        val list = guidePrograms[channel.id].orEmpty()
        return list.firstOrNull { time in it.start until it.stop }
            ?: list.minByOrNull { kotlin.math.abs(it.start - time) }
    }

    /**
     * Moment the panel describes: "now" while it is on screen, otherwise the left edge of the
     * grid, which is then what the user is actually reading.
     */
    private fun guideFocusTime(): Long {
        val now = System.currentTimeMillis()
        val viewport = programsList.width
        if (viewport <= 0) return now
        val offset = guideRowsAdapter.currentOffset()
        if (guideRowsAdapter.pixelForTime(now) in offset..(offset + viewport)) return now
        return gridStart + (offset + 1).toLong() * 3_600_000L / guideRowsAdapter.hourWidthPx()
    }

    /**
     * Mirrors the vertical position of [source] onto [target]. Both lists use the same layout
     * manager and the same item height, so the first visible row and its offset fully describe
     * the position. The applied position is remembered, which keeps the two scroll listeners
     * from bouncing the same change back and forth.
     */
    private fun syncVerticalScroll(source: RecyclerView, target: RecyclerView) {
        if (syncingRows) return
        val sourceLm = source.layoutManager as? LinearLayoutManager ?: return
        val targetLm = target.layoutManager as? LinearLayoutManager ?: return
        val first = sourceLm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val view = source.findViewHolderForAdapterPosition(first)?.itemView ?: return
        val top = view.top
        if (first == lastSyncPosition && top == lastSyncTop) return
        lastSyncPosition = first
        lastSyncTop = top
        syncingRows = true
        targetLm.scrollToPositionWithOffset(first, top)
        syncingRows = false
    }

    private fun prefsFor(category: Category): String = when (prefs.channelsSorting) {
        "name" -> "name"
        "date_added" -> "date_added"
        "last_modified" -> "last_modified"
        "watch_time" -> "watch_time"
        "manual" -> "manual"
        else -> "order"
    }

    private fun selectCategoryByFilter(filter: ChannelFilter) {
        val index = categories.indexOfFirst { it.filter == filter }
        if (index >= 0) {
            groupsList.smoothScrollToPosition(index)
            onCategorySelected(index)
        } else {
            toast(getString(R.string.no_channels))
        }
    }

    private fun showEmpty(message: String) {
        emptyView.text = message
        emptyView.visible(true)
        updateGuideInfo()
    }

    // ---------------------------------------------------------------- events

    private fun onGuideChannelClick(position: Int) {
        val channel = currentChannels.getOrNull(position) ?: return
        openPlayerFor(channel, currentChannels)
    }

    private fun onGuideChannelFocus() {
        updateGuideInfo()
    }

    /**
     * A tap on a cell only marks the programme, a second activation of the same cell plays it.
     * That keeps a plain tap from throwing the user into the player while still allowing a single
     * pick to be read and confirmed.
     *
     * The pick deliberately stays out of the input focus. Handing it to the crosshair made the grid
     * scroll the row into view under the finger that is still resting on it, and that scroll cancels
     * the gesture: the click never arrived, so the first tap only drew the crosshair, the second one
     * only drew the pick, and only the third one started the channel. The pick is remembered on its
     * own and the info panel follows it, so the remote is none the wiser and keeps its own steps.
     */
    private fun onGuideProgramClick(position: Int, program: Program) {
        if (guideRowsAdapter.isSelected(position, program)) {
            onGuideChannelClick(position)
            return
        }
        guideRowsAdapter.select(position, program)
    }

    private fun autoPlayLastChannel() {
        val lastId = repo.lastWatchedChannelId() ?: return
        val channel = currentChannels.find { it.id == lastId }
        if (channel != null) {
            openPlayerFor(channel, currentChannels)
        } else {
            executor.execute {
                val ch = repo.channel(lastId)
                main.post {
                    if (ch != null) openPlayerFor(ch, listOf(ch))
                }
            }
        }
    }

    private fun openPlayerFor(channel: Channel, channelList: List<Channel>) {
        if (prefs.pin.isNotEmpty() && prefs.pinRequiredFor == "always") {
            PinActivity.start(this, channel.id)
            return
        }
        play(channel, channelList)
    }

    private fun play(channel: Channel, channelList: List<Channel> = currentChannels) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            .putExtra(
                PlayerActivity.EXTRA_CHANNEL_IDS,
                channelList.map { it.id }.toLongArray()
            )
            .putExtra(
                PlayerActivity.EXTRA_CHANNEL_INDEX,
                channelList.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
            )
            .putExtra(PlayerActivity.EXTRA_STAY_ON_LIST, prefs.stayOnList)
            .putExtra(PlayerActivity.EXTRA_CATEGORY_INDEX, groupsAdapter.selectedIndex())
        startActivity(intent)
    }

    private fun onChannelLongClick(index: Int) {
        val channel = currentChannels.getOrNull(index) ?: return
        val options = listOf(
            Dialogs.Item(getString(R.string.watch_channel)),
            Dialogs.Item(getString(R.string.programs)),
            Dialogs.Item(
                if (channel.favorite) getString(R.string.remove_from_favorites)
                else getString(R.string.add_to_favorites)
            ),
            Dialogs.Item(getString(R.string.hide_channel)),
            Dialogs.Item(
                if (channel.blocked) getString(R.string.unblock_channel)
                else getString(R.string.block_channel)
            ),
            Dialogs.Item(getString(R.string.sorting)),
            Dialogs.Item(getString(R.string.remove_from_history)),
        )
        Dialogs.show(this, channel.name, null, options) { which ->
            when (which) {
                0 -> play(channel)
                1 -> showProgramMenu(index)
                2 -> toggleFavorite(channel)
                3 -> repo.setChannelFlags(channel.id, hidden = true)
                4 -> repo.setChannelFlags(channel.id, blocked = !channel.blocked)
                5 -> showSorting()
                6 -> {
                    repo.removeHistory(channel.id)
                    toast(getString(R.string.remove_from_history))
                }
            }
            reload()
        }
    }

    private fun showProgramMenu(channelIndex: Int) {
        val channel = currentChannels.getOrNull(channelIndex) ?: return
        val list = guidePrograms[channel.id].orEmpty()
        if (list.isEmpty()) {
            toast(getString(R.string.no_programs))
            return
        }
        val options = list.map { Dialogs.Item(it.title, Fmt.timeRange(it.start, it.stop, Locale.getDefault())) }
        Dialogs.show(this, channel.name, getString(R.string.programs), options) { which ->
            play(channel)
        }
    }

    private fun toggleFavorite(channel: Channel) {
        val newValue = !channel.favorite
        executor.execute {
            repo.setChannelFlags(channel.id, favorite = newValue)
            main.post { toast(getString(if (newValue) R.string.added_to_favorites else R.string.removed_from_favorites)) }
        }
    }

    private fun showSorting() {
        val values = resources.getStringArray(R.array.sort_values)
        val labels = resources.getStringArray(R.array.sort_entries)
        val items = values.mapIndexed { index, value -> Dialogs.Item(labels[index], checked = prefs.channelsSorting == value) }
        Dialogs.show(this, getString(R.string.sorting), null, items) { which ->
            prefs.channelsSorting = values[which]
            onCategorySelected(groupsAdapter.selectedIndex(), force = true)
        }
    }

    private fun onCategoryLongClick(index: Int) {
        val category = categories.getOrNull(index) ?: return
        if (category.playlistIds.size != 1) return
        val playlistId = category.playlistIds[0]
        val options = listOf(
            Dialogs.Item(getString(R.string.update_playlist)),
            Dialogs.Item(getString(R.string.manage_groups)),
            Dialogs.Item(getString(R.string.add_epg_url)),
            Dialogs.Item(getString(R.string.hide_playlist)),
            Dialogs.Item(getString(R.string.delete_playlist)),
        )
        Dialogs.show(this, category.name, null, options) { which ->
            when (which) {
                0 -> refreshPlaylist(playlistId)
                1 -> refreshPlaylist(playlistId)
                2 -> addEpgUrl(playlistId)
                3 -> hidePlaylist(playlistId)
                4 -> confirmDeletePlaylist(playlistId)
            }
        }
    }

    private fun hidePlaylist(playlistId: Long) {
        executor.execute {
            val playlist = repo.playlist(playlistId) ?: return@execute
            repo.setPlaylistHidden(playlistId, !playlist.hidden)
            main.post { reload() }
        }
    }

    private fun confirmDeletePlaylist(playlistId: Long) {
        val playlist = repo.playlist(playlistId) ?: return
        Dialogs.confirm(
            this,
            getString(R.string.delete_playlist_q),
            getString(R.string.delete_playlist_msg, playlist.name),
            getString(R.string.delete)
        ) {
            executor.execute {
                repo.deletePlaylist(playlistId)
                main.post {
                    toast(getString(R.string.playlist_deleted))
                    reload()
                }
            }
        }
    }

    private fun addEpgUrl(playlistId: Long) {
        Dialogs.input(
            this,
            getString(R.string.add_epg_url),
            hint = "http://example.com/epg.xml.gz",
            onOk = { url, _ ->
                if (url.isBlank()) return@input
                executor.execute {
                    repo.insertEpgSource(
                        com.tvibro.data.model.EpgSource(
                            name = url.substringAfterLast('/'),
                            url = url,
                            playlistId = playlistId,
                        )
                    )
                    main.post {
                        toast(getString(R.string.epg_source_added))
                        refreshEpg(playlistId)
                    }
                }
            }
        )
    }

    private fun refreshPlaylist(playlistId: Long) {
        if (isRefreshing) return
        isRefreshing = true
        val dialog = Dialogs.progress(this, getString(R.string.playlist_updating))
        dialog.show()
        TvBroApp.get().sources.refreshPlaylist(playlistId) { result ->
            isRefreshing = false
            dialog.dismiss()
            result.onSuccess { (programs, channels) ->
                toast(getString(R.string.playlist_updated))
                statusText.text = getString(R.string.channels_count, channels)
            }.onFailure {
                toast(getString(R.string.playlist_update_failed), long = true)
            }
            reload()
        }
    }

    private fun refreshEpg(playlistId: Long) {
        val dialog = Dialogs.progress(this, getString(R.string.update_epg))
        dialog.show()
        TvBroApp.get().sources.refreshEpgForPlaylist(playlistId) { count ->
            dialog.dismiss()
            toast(getString(R.string.epg_updated, count, ""))
            onCategorySelected(groupsAdapter.selectedIndex(), force = true)
        }
    }

    private fun maybeAutoUpdate() {
        val interval = prefs.updateIntervalHours.toLong() * 3600_000L
        val last = repo.playlists().maxOfOrNull { it.lastUpdate } ?: 0L
        val stale = System.currentTimeMillis() - last > interval
        if (prefs.updateOnStart && stale && repo.playlists().isNotEmpty()) {
            val dialog = Dialogs.progress(this, getString(R.string.playlist_updating))
            dialog.show()
            TvBroApp.get().sources.refreshAllPlaylists({ done, total ->
                main.post { statusText.text = getString(R.string.updating_playlists, done, total) }
            }) { error ->
                dialog.dismiss()
                if (error != null) toast(error, long = true)
                reload()
            }
        }
    }

    // ------------------------------------------------------------ navigation

    /**
     * A touch screen has no Right key to put the columns away again, so a single tap does it: any
     * tap at all, on a column item, on the channel column or on the programme grid, hides both of
     * them. The tap itself is not swallowed, so it still selects its channel, category or
     * programme. Only the button that opens the next column is left alone, and only touch is
     * looked at here: the keys keep their own handling, so the remote behaves exactly as before.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = ev.x
                tapDownY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                val tap = ev.x - tapDownX < slop && ev.x - tapDownX > -slop &&
                    ev.y - tapDownY < slop && ev.y - tapDownY > -slop
                val opensColumn = leftStage == STAGE_GROUPS && touchOnMenuButton(ev)
                if (tap && leftStage != STAGE_CONTENT && !opensColumn) closeLeftColumns()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True when the tap landed on the button that peels the menu strip open. */
    private fun touchOnMenuButton(ev: MotionEvent): Boolean {
        val location = IntArray(2)
        menuButton.getLocationInWindow(location)
        return ev.x >= location[0] && ev.x <= location[0] + menuButton.width &&
            ev.y >= location[1] && ev.y <= location[1] + menuButton.height
    }

    /**
     * Both columns go away in one step. The focus is left where it is: a touch never held it, and
     * moving it would take the focus away from wherever the remote had left the guide.
     */
    private fun closeLeftColumns() {
        if (leftStage == STAGE_CONTENT) return
        leftStage = STAGE_CONTENT
        menuStrip.visible(false)
        groupsColumn.visible(false)
        updateRevealButtons()
    }

    /**
     * Left arrow peels the window open one column at a time: first the channel groups,
     * then the menu strip that used to sit horizontally in the top bar. Right arrow and
     * Back put the columns away again, one per press.
     */
    private fun openLeftColumn() {
        when (leftStage) {
            STAGE_CONTENT -> {
                leftStage = STAGE_GROUPS
                groupsColumn.visible(true)
                focusList(groupsList, groupsAdapter.selectedIndex())
            }
            STAGE_GROUPS -> {
                leftStage = STAGE_MENU
                menuStrip.visible(true)
                focusFirst(menuStrip)
            }
        }
        updateRevealButtons()
    }

    private fun closeLeftColumn() {
        when (leftStage) {
            STAGE_MENU -> {
                leftStage = STAGE_GROUPS
                menuStrip.visible(false)
                focusList(groupsList, groupsAdapter.selectedIndex())
            }
            STAGE_GROUPS -> {
                leftStage = STAGE_CONTENT
                groupsColumn.visible(false)
                focusList(guideChannelsList)
            }
        }
        updateRevealButtons()
    }

    /** Only the last visible column has something left to open, so only it shows its button. */
    private fun updateRevealButtons() {
        groupsButton.visible(leftStage == STAGE_CONTENT)
        menuButton.visible(leftStage == STAGE_GROUPS)
    }

    private fun focusFirst(container: ViewGroup) {
        val child = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.isFocusable }
        if (child != null) {
            child.requestFocus()
            return
        }
        container.postDelayed({
            if (!container.hasFocus()) focusFirst(container)
        }, FOCUS_RETRY_MS)
    }

    private fun focusList(list: RecyclerView, position: Int = 0) {
        val target = position.coerceAtLeast(0)
        list.scrollToPosition(target)
        focusList(list, target, FOCUS_ATTEMPTS)
    }

    private fun focusList(list: RecyclerView, position: Int, attempts: Int) {
        if (attempts <= 0) return
        val child = list.findViewHolderForAdapterPosition(position)?.itemView
            ?: list.getChildAt(0)
        if (child != null) {
            child.requestFocus()
            if (child.hasFocus()) return
        }
        list.postDelayed({ focusList(list, position, attempts - 1) }, FOCUS_RETRY_MS)
    }

    /**
     * Where the remote lands when the guide opens and when the full screen player gives the screen
     * back: on the programme that is on air right now in the channel that was played last. The
     * crosshair therefore sits on the very transmission the user came from, and the info panel
     * describes that same cell without anyone touching anything.
     *
     * The row has to be on screen before the cell inside it can take the focus, so the grid is
     * scrolled to that channel first and the cell is looked for again after every layout pass.
     */
    private fun focusGuide() {
        if (currentChannels.isEmpty()) {
            focusList(guideChannelsList)
            return
        }
        prepareGuideFocus()
    }

    /** Scrolls the row of the last played channel into view and puts the crosshair on its programme. */
    private fun prepareGuideFocus() {
        val index = currentChannels.indexOfFirst { it.id == guideChannelsAdapter.currentId() }
            .takeIf { it >= 0 } ?: 0
        val channel = currentChannels.getOrNull(index)
        if (channel == null) {
            focusList(guideChannelsList, index)
            return
        }
        // The axis always starts at today, so "now" is on it. It stays the landing point even while the grid
        // is scrolled somewhere else, because that is where the guide opens.
        val time = System.currentTimeMillis()
        val program = programAt(channel, time)
        if (program == null) {
            focusList(guideChannelsList, index)
            return
        }
        focusTargetChannel = index
        focusTargetProgram = program
        // The row has to be on screen before the cell inside it can take the focus.
        programsList.scrollToPosition(index)
        applyPendingFocus(FOCUS_ATTEMPTS)
    }

    /**
     * Puts the crosshair on the cell it was aimed at and keeps asking until the cell reports that
     * it holds the focus. A single request is not enough: the rows are built during layout and the
     * window itself hands the focus to the first focusable view of the tree, which is the "Today"
     * button above the guide. The target is kept until it is really reached, so every later moment
     * that can take the focus away is followed by another attempt.
     */
    private fun applyPendingFocus(attempts: Int = FOCUS_ATTEMPTS) {
        val program = focusTargetProgram ?: return
        val position = focusTargetChannel
        if (guideRowsAdapter.holdsFocusAt(position, program)) {
            focusTargetProgram = null
            focusTargetChannel = RecyclerView.NO_POSITION
            return
        }
        // The user has taken the remote to the channel column on purpose. A retry that is left over
        // from the opening of the guide must not pull the focus back into the grid under the finger
        // that is about to press the centre key, or the press lands on a cell instead of the row.
        if (guideChannelsList.hasFocus()) {
            focusTargetProgram = null
            focusTargetChannel = RecyclerView.NO_POSITION
            return
        }
        guideRowsAdapter.requestFocusOnCell(position, program)
        if (attempts <= 0) return
        main.postDelayed({ applyPendingFocus(attempts - 1) }, FOCUS_RETRY_MS)
    }

    /**
     * One vertical step of the crosshair. True when the key is answered here, false when the step is
     * left to the ordinary focus search.
     *
     * The now line already points at what is on air, so stepping along it is the shorter move: while
     * the crosshair sits on a cell the line crosses, the row above or below is entered at its own
     * now cell instead of at the one nearest in pixels - a wide cell reaches far to both sides, and
     * the horizontal distance to its centre easily favours a programme that has already ended.
     *
     * Answering the key matters as much as the target: the focus search runs while the event is
     * still being dispatched, so a step that only moves the crosshair and then lets the search run
     * as well walks two rows and skips the one in between.
     */
    private fun stepCrosshair(step: Int): Boolean {
        val program = guideRowsAdapter.focusedProgram()
        if (program == null) {
            // The focus search can walk out of the grid when there is no cell left in that
            // direction, and a grid without a crosshair has no way back: the step puts it on the
            // nearest row that does carry a cell instead of doing nothing at all.
            return recoverCrosshair(step)
        }
        val now = System.currentTimeMillis()
        if (now < program.start || now >= program.stop) return false
        val row = guideRowsAdapter.nextChannelPosition(step)
        val onAir = guideRowsAdapter.programOnAirAt(row, now) ?: return false
        return focusGuideCell(row, onAir)
    }

    /** Puts a lost crosshair back on the nearest row above or below that carries a cell. */
    private fun recoverCrosshair(step: Int): Boolean {
        val first = (programsList.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (first == RecyclerView.NO_POSITION) return false
        val row = if (step < 0) {
            guideRowsAdapter.channelPositionIn(-1, first - 1)
        } else {
            guideRowsAdapter.channelPositionIn(1, first)
        }
        if (row == RecyclerView.NO_POSITION) return false
        val target = guideRowsAdapter.firstProgramAt(row) ?: return false
        return focusGuideCell(row, target)
    }

    /**
     * Scrolls the row of a step into view and keeps asking for the cell until it reports the focus.
     * The next row of a step is regularly still outside the viewport, and a row that is not attached
     * is not built either - asking once simply failed then, and the step was handed back to the focus
     * search, which walked on to whatever cell happened to be on screen. That is what made the
     * crosshair skip a channel on the way down, while the same step upwards usually landed right.
     */
    private fun focusGuideCell(channelPosition: Int, program: Program): Boolean {
        programsList.scrollToPosition(channelPosition)
        focusTargetChannel = channelPosition
        focusTargetProgram = program
        applyPendingFocus(FOCUS_ATTEMPTS)
        return true
    }

    private fun scrollTimeline(hours: Int) {
        guideRowsAdapter.setViewport(programsList.width)
        guideRowsAdapter.setOffset(
            guideRowsAdapter.currentOffset() + hours * guideRowsAdapter.hourWidthPx()
        )
        updateGuideInfo()
    }

    /**
     * Inside the grid the D-pad belongs to the crosshair, so the key is handed to the default focus
     * search. The time axis follows the cell that receives the focus - see [revealFocusedCell] -
     * because the crosshair only lands on the next cell while the key event is still being handled.
     * Going left also has to be able to leave the grid: when the focus does not move at all the data
     * ends in that direction, and the channel column on the left takes the remote back instead of
     * trapping the user in the grid.
     */
    private fun moveCrosshair(direction: Int) {
        if (direction >= 0) return
        val before = guideRowsAdapter.focusedProgram()
        main.post {
            if (guideRowsAdapter.focusedProgram() === before) {
                focusList(guideChannelsList, guideRowsAdapter.focusedChannelPosition())
            }
        }
    }

    /**
     * Brings the cell under the crosshair fully into the viewport, moving the time axis by as little
     * as it takes. Without it the remote walks into a cell that lies outside the grid and the user
     * is left with a focus nobody can see.
     */
    private fun revealFocusedCell() {
        guideRowsAdapter.setViewport(programsList.width)
        val shift = guideRowsAdapter.revealShiftForFocusedCell()
        if (shift == 0) return
        guideRowsAdapter.setOffset(guideRowsAdapter.currentOffset() + shift)
        updateGuideInfo()
    }

    /**
     * Moves the grid along the time axis: positive [pixels] goes towards later programmes, which
     * is the same direction the content travels when it is dragged to the left.
     */
    private fun advanceTimelineBy(pixels: Int) {
        // Keep the clamp based on the current width, otherwise a stale viewport lets the grid run
        // past its content and the rows scroll into empty space.
        guideRowsAdapter.setViewport(programsList.width)
        guideRowsAdapter.setOffset(guideRowsAdapter.currentOffset() + pixels)
    }

    /**
     * Pulls the grid back to the closest window that has something to show. Sparse EPG leaves long
     * empty stretches, and a fling used to be able to come to rest inside one of them.
     */
    private fun settleTimeline() {
        guideRowsAdapter.setViewport(programsList.width)
        // The resting position decides which moment the panel describes.
        updateGuideInfo()
        val from = guideRowsAdapter.currentOffset()
        val target = guideRowsAdapter.nearestPopulatedOffset() ?: return
        ValueAnimator.ofInt(from, target).apply {
            duration = (220L * kotlin.math.abs(target - from) / programsList.width).toLong()
                .coerceIn(140L, 320L)
            addUpdateListener { guideRowsAdapter.setOffset(it.animatedValue as Int) }
            start()
        }
    }

    private var flingVelocity = 0f
    private var flingStart = 0

    private fun flingTimeline(velocityX: Float) {
        flingVelocity = velocityX.coerceIn(-MAX_FLING_PX_PER_SEC, MAX_FLING_PX_PER_SEC)
        flingStart = guideRowsAdapter.currentOffset()
        if (kotlin.math.abs(flingVelocity) < MIN_FLING_PX_PER_SEC) {
            flingVelocity = 0f
            settleTimeline()
            return
        }
        Choreographer.getInstance().postFrameCallback(flingStep)
    }

    /**
     * A release of the finger glides on for a moment and then rests. The range it may travel is
     * limited to a fraction of a day: the axis carries several days now, and an unbounded glide
     * would throw the user from today into the middle of next week before they could see anything.
     * It also stops as soon as the grid cannot move any further, so the end of the loaded range is
     * never crossed by simply letting go.
     */
    private val flingStep = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (flingVelocity == 0f) return
            val before = guideRowsAdapter.currentOffset()
            advanceTimelineBy(flingVelocity.toInt())
            flingVelocity *= FLING_DECAY
            val stuck = guideRowsAdapter.currentOffset() == before
            val travelled = guideRowsAdapter.currentOffset() - flingStart
            if (stuck ||
                kotlin.math.abs(travelled) > guideRowsAdapter.hourWidthPx() * MAX_FLING_HOURS ||
                kotlin.math.abs(flingVelocity) < MIN_FLING_PX_PER_SEC
            ) {
                flingVelocity = 0f
                settleTimeline()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Turns a horizontal drag over the grid into a shared offset. Vertical drags are left to the
     * channel list, so a diagonal gesture still scrolls channels.
     */
    private inner class TimelineTouchListener :
        RecyclerView.OnItemTouchListener,
        View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null

        /**
         * Shared by both callbacks: depending on where the gesture is picked up the following
         * events are delivered to one or the other, and a repeated event is a no-op because
         * [lastX] has already moved past it.
         */
        private fun handle(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    lastX = e.x
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!dragging &&
                        kotlin.math.abs(dx) > slop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    ) {
                        dragging = true
                        // Hand the slop already eaten by the interception to the grid, so the
                        // content does not jump when the drag is picked up.
                        lastX = downX + if (dx < 0) -slop else slop
                        flingVelocity = 0f
                    }
                    if (dragging) {
                        val step = (e.x - lastX).toInt()
                        if (step != 0) {
                            lastX = e.x
                            advanceTimelineBy(-step)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    // The content follows the finger, so a leftwards fling runs time forwards.
                    if (dragging) flingTimeline(-(tracker?.xVelocity ?: 0f))
                    releaseTracker()
                }
                MotionEvent.ACTION_CANCEL -> releaseTracker()
            }
            return dragging
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean = handle(e)

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            handle(e)
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        /**
         * The scale has no interaction of its own, so it takes the whole gesture and feeds it to
         * the same state machine the grid uses.
         */
        override fun onTouch(view: View, e: MotionEvent): Boolean {
            handle(e)
            return true
        }

        private fun releaseTracker() {
            tracker?.recycle()
            tracker = null
            dragging = false
        }
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun openHistory() {
        val dialog = Dialogs.progress(this, getString(R.string.loading))
        dialog.show()
        executor.execute {
            val entries = repo.historyChannels(prefs.recentChannelCount)
            main.post {
                dialog.dismiss()
                if (entries.isEmpty()) {
                    toast(getString(R.string.no_history))
                    return@post
                }
                val items = entries.map { Dialogs.Item(it.name, it.playlistName) }
                Dialogs.show(this, getString(R.string.recent_channels), null, items) { which ->
                    play(entries[which])
                }
            }
        }
    }

    private fun openVod() {
        startActivity(Intent(this, VodActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openPlaylistWizard() {
        startActivity(Intent(this, PlaylistWizardActivity::class.java))
    }

    /**
     * The remote has to switch the channel on the first press, and the key can no longer be caught
     * in [onKeyDown]: a focused channel row or grid cell consumes the confirm key itself and turns
     * it into `performClick()`, which in the grid only marks the programme - the two steps a finger
     * needs, where the first touch is used to read the channel before it is played. The key is
     * taken here instead, before the focused view ever sees it, so the tap keeps its two steps and
     * the remote gets a single press.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isConfirmKey(event)) {
            if (event.action == KeyEvent.ACTION_UP) {
                // The release of a press that was answered already must not reach the row: the row
                // turns it into a click and the channel would be picked a second time.
                if (event.downTime == answeredConfirm) {
                    answeredConfirm = 0L
                    return true
                }
            } else if (event.repeatCount == 0 && switchChannelFromRemote()) {
                answeredConfirm = event.downTime
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isConfirmKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        return when (event.keyCode) {
            // BUTTON_A is what a lot of TV box remotes send for the centre key.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> true
            else -> false
        }
    }

    /**
     * Plays the channel the remote stands on. Inside the open guide the centre key always means
     * "watch this", wherever the focus happens to sit: the focused row of the channel column or the
     * row of the crosshair. Waiting for the focus to reach the row is what made the first press look
     * like a focus move.
     * Outside the guide the key is left alone, so the categories and the menu keep their meaning.
     */
    private fun switchChannelFromRemote(): Boolean {
        if (leftStage != STAGE_CONTENT) return false
        val grid = programsList.hasFocus()
        if (!grid && !guideChannelsList.hasFocus()) return false
        val focusedRow = focusedChannelRow()
        val position = when {
            grid -> guideRowsAdapter.focusedChannelPosition()
            focusedRow != RecyclerView.NO_POSITION -> focusedRow
            else -> guideRowsAdapter.focusedChannelPosition()
        }
        if (position == RecyclerView.NO_POSITION) return false
        onGuideChannelClick(position)
        return true
    }

    /**
     * Row of the channel column that really holds the focus, asked of the view tree instead of the
     * cache of the adapter: a row that is focused while it gets rebound never reports a focus
     * change, and the cache then points at a row the remote has already left.
     */
    private fun focusedChannelRow(): Int {
        val position = guideChannelsList.getChildAdapterPosition(guideChannelsList.findFocus())
        return if (position != RecyclerView.NO_POSITION) position else guideChannelsAdapter.focusedPosition()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                Dialogs.show(
                    this,
                    getString(R.string.app_name),
                    null,
                    listOf(
                        Dialogs.Item(getString(R.string.add_playlist)),
                        Dialogs.Item(getString(R.string.update_all_playlists)),
                        Dialogs.Item(getString(R.string.settings)),
                    )
                ) { which ->
                    when (which) {
                        0 -> openPlaylistWizard()
                        1 -> maybeAutoUpdate()
                        2 -> openSettings()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                openSearch()
                return true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                leftStage = STAGE_CONTENT
                menuStrip.visible(false)
                groupsColumn.visible(false)
                updateRevealButtons()
                focusGuide()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (leftStage == STAGE_MENU) return true
                if (leftStage == STAGE_CONTENT && programsList.hasFocus()) {
                    moveCrosshair(-1)
                    return false
                }
                openLeftColumn()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (leftStage != STAGE_CONTENT) {
                    closeLeftColumn()
                    return true
                }
                // From the channel column the right key belongs to the grid, so the crosshair can
                // enter it; an open column keeps the timeline shortcut instead.
                if (guideChannelsList.hasFocus()) return false
                if (programsList.hasFocus()) {
                    moveCrosshair(1)
                    return false
                }
                scrollTimeline(TIMELINE_STEP_HOURS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (programsList.hasFocus()) {
                    // The grid scrolls like the channel column next to it: the crosshair walks on
                    // and both lists move together. Only the real end of the list stops it, so the
                    // focus can never wander off into the bars around the guide.
                    if (guideRowsAdapter.hasCrosshair() && !guideRowsAdapter.hasCellAbove()) return true
                    return !stepCrosshair(-1)
                }
                if (leftStage == STAGE_CONTENT) {
                    scrollTimeline(-TIMELINE_STEP_HOURS)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (programsList.hasFocus()) {
                    if (guideRowsAdapter.hasCrosshair() && !guideRowsAdapter.hasCellBelow()) return true
                    if (stepCrosshair(1)) return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (leftStage != STAGE_CONTENT) {
                    closeLeftColumn()
                    return true
                }
                if (prefs.confirmExit) {
                    val now = System.currentTimeMillis()
                    if (now - lastExitPress < 2000) {
                        finish()
                    } else {
                        lastExitPress = now
                        toast(getString(R.string.press_again_to_exit))
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (leftStage != STAGE_CONTENT) {
            closeLeftColumn()
            return
        }
        // The picture in the strip is the only thing that can still be running here, and it is not
        // focusable on purpose, so back is the way to get rid of it.
        if (Playback.inGuide()) {
            Playback.stop()
            // The slot goes back to the sign of a television, the stream is really over now.
            attachMiniPlayer()
            return
        }
        if (prefs.confirmExit) {
            val now = System.currentTimeMillis()
            if (now - lastExitPress < 2000) {
                finish()
            } else {
                lastExitPress = now
                toast(getString(R.string.press_again_to_exit))
            }
        } else {
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ADD_PLAYLIST) reload()
    }

    companion object {
        const val REQUEST_ADD_PLAYLIST = 501
        private const val GUIDE_CACHE_MAX = 6
        private const val STAGE_CONTENT = 0
        private const val STAGE_GROUPS = 1
        private const val STAGE_MENU = 2
        private const val TIMELINE_STEP_HOURS = 2
            private const val NOW_LINE_MARGIN = 0.12f
        private const val MIN_FLING_PX_PER_SEC = 350f
        /** Top speed of a fling: a brisk swipe must not turn into a jump over several days. */
        private const val MAX_FLING_PX_PER_SEC = 3200f
        /** How far a single fling may travel, in hours of the axis. */
        private const val MAX_FLING_HOURS = 6

        private const val FLING_DECAY = 0.95f
    /** A slow box can need a few layout passes before the row and the window agree on the focus. */
    private const val FOCUS_ATTEMPTS = 10
    private const val FOCUS_RETRY_MS = 60L

    }
}
