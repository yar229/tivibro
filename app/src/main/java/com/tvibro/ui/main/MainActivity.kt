package com.tvibro.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
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
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.main.guide.GuideChannelsAdapter
import com.tvibro.ui.main.guide.GuideDaysAdapter
import com.tvibro.ui.main.guide.GuideRowsAdapter
import com.tvibro.ui.main.guide.TimeRulerView
import com.tvibro.ui.pin.PinActivity
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
    private lateinit var daysAdapter: GuideDaysAdapter
    private lateinit var groupsList: RecyclerView
    private lateinit var guideChannelsList: RecyclerView
    private lateinit var programsList: RecyclerView
    private lateinit var daysList: RecyclerView
    private lateinit var menuStrip: LinearLayout
    private lateinit var gridContainer: LinearLayout
    private lateinit var timeRuler: TimeRulerView
    private lateinit var nowLine: View
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
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
    private var dayStart = 0L
    private var dayIndex = 0
    private var leftStage = STAGE_CONTENT
    private var pendingFocus = true
    private var firstResume = true
    private var pendingAutoPlay = false
    private var playerLaunched = false
    private var lastExitPress = 0L
    private var clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        prefs.applyFontScale()
        setContentView(R.layout.activity_main)
        repo = TvBroApp.repo(this)

        bindViews()
        buildMenuStrip()
        startClock()

        groupsAdapter = GroupsAdapter(
            onClick = { index -> onCategorySelected(index) },
            onLongClick = { index -> onCategoryLongClick(index) },
        )
        groupsList.layoutManager = LinearLayoutManager(this)
        groupsList.adapter = groupsAdapter

        daysAdapter = GuideDaysAdapter(GUIDE_DAYS) { index -> selectDay(index) }
        daysList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        daysList.adapter = daysAdapter

        guideChannelsAdapter = GuideChannelsAdapter(
            onClick = { position -> onGuideChannelClick(position) },
            onLongClick = { position -> onChannelLongClick(position) },
        )
        guideChannelsList.layoutManager = LinearLayoutManager(this)
        guideChannelsList.adapter = guideChannelsAdapter

        guideRowsAdapter = GuideRowsAdapter(
            hourWidthPx = resources.getDimensionPixelSize(R.dimen.epg_hour_width),
            onProgramClick = { position -> onGuideChannelClick(position) },
            onOffsetChanged = {
                positionNowLine()
                timeRuler.setOffset(guideRowsAdapter.currentOffset())
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

        dayStart = Fmt.startOfDay(System.currentTimeMillis())
        timeRuler.setDayStart(dayStart)
        daysAdapter.submit(dayStart)
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
        reload(autoPlay = firstResume && prefs.turnOnLastChannel)
        firstResume = false
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        guideExecutor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        playerLaunched = false
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        groupsList = findViewById(R.id.groups_list)
        guideChannelsList = findViewById(R.id.guide_channels_list)
        programsList = findViewById(R.id.programs_rows)
        daysList = findViewById(R.id.days_list)
        menuStrip = findViewById(R.id.menu_strip)
        gridContainer = findViewById(R.id.grid_container)
        timeRuler = findViewById(R.id.time_ruler)
        nowLine = findViewById(R.id.now_line)
        emptyView = findViewById(R.id.empty_view)
        statusText = findViewById(R.id.status_text)
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
                if (pendingFocus) {
                    pendingFocus = false
                    focusGuide()
                }
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
        val key = "$categoryIndex:$dayIndex"
        guideCache[key]?.let { cached ->
            guidePrograms = cached
            applyGuideData(channels, cached)
            return
        }
        guideExecutor.execute {
            val from = dayStart
            val map = repo.programsForChannels(
                channels.map { it.id },
                from,
                from + GuideDaysAdapter.DAY_MS,
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
     * The rows of the grid are built during layout, so the timeline can only be positioned
     * once the rows exist - before that every horizontal scroll is a no-op.
     */
    private fun applyGuideData(channels: List<Channel>, programs: Map<Long, List<Program>>) {
        guideRowsAdapter.setData(channels, programs)
        programsList.doOnPreDraw { scrollTimelineToNow() }
    }

    private fun selectDay(index: Int) {
        val target = index.coerceIn(0, GUIDE_DAYS - 1)
        if (target == dayIndex) return
        dayIndex = target
        dayStart = Fmt.startOfDay(System.currentTimeMillis()) + target * GuideDaysAdapter.DAY_MS
        daysAdapter.select(target)
        guideRowsAdapter.setDayStart(dayStart)
        timeRuler.setDayStart(dayStart)
        loadGuidePrograms(groupsAdapter.selectedIndex())
    }

    private fun scrollTimelineToNow() {
        val now = System.currentTimeMillis()
        guideRowsAdapter.setViewport(programsList.width)
        if (now !in dayStart until dayStart + GuideDaysAdapter.DAY_MS) {
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
        if (now < dayStart || now > dayStart + GuideDaysAdapter.DAY_MS) {
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
    }

    // ---------------------------------------------------------------- events

    private fun onGuideChannelClick(position: Int) {
        val channel = currentChannels.getOrNull(position) ?: return
        openPlayerFor(channel, currentChannels)
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
     * Left arrow peels the window open one column at a time: first the channel groups,
     * then the menu strip that used to sit horizontally in the top bar. Right arrow and
     * Back put the columns away again, one per press.
     */
    private fun openLeftColumn() {
        when (leftStage) {
            STAGE_CONTENT -> {
                leftStage = STAGE_GROUPS
                groupsList.visible(true)
                focusList(groupsList, groupsAdapter.selectedIndex())
            }
            STAGE_GROUPS -> {
                leftStage = STAGE_MENU
                menuStrip.visible(true)
                focusFirst(menuStrip)
            }
        }
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
                groupsList.visible(false)
                focusList(guideChannelsList)
            }
        }
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

    private fun focusGuide() {
        if (currentChannels.isEmpty()) {
            focusFirst(daysList)
            return
        }
        val index = currentChannels.indexOfFirst { it.id == guideChannelsAdapter.currentId() }
        focusList(guideChannelsList, if (index >= 0) index else 0)
    }

    private fun scrollTimeline(hours: Int) {
        guideRowsAdapter.setViewport(programsList.width)
        guideRowsAdapter.setOffset(
            guideRowsAdapter.currentOffset() + hours * guideRowsAdapter.hourWidthPx()
        )
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

    private fun flingTimeline(velocityX: Float) {
        flingVelocity = velocityX
        if (kotlin.math.abs(flingVelocity) < MIN_FLING_PX_PER_SEC) {
            flingVelocity = 0f
            settleTimeline()
            return
        }
        Choreographer.getInstance().postFrameCallback(flingStep)
    }

    private val flingStep = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (flingVelocity == 0f) return
            val before = guideRowsAdapter.currentOffset()
            advanceTimelineBy(flingVelocity.toInt())
            flingVelocity *= FLING_DECAY
            val stuck = guideRowsAdapter.currentOffset() == before
            if (stuck || kotlin.math.abs(flingVelocity) < MIN_FLING_PX_PER_SEC) {
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
                groupsList.visible(false)
                focusGuide()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (daysList.hasFocus()) {
                    selectDay(daysAdapter.selectedIndex() - 1)
                    return true
                }
                if (leftStage == STAGE_MENU) return true
                openLeftColumn()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (daysList.hasFocus()) {
                    selectDay(daysAdapter.selectedIndex() + 1)
                    return true
                }
                if (leftStage != STAGE_CONTENT) {
                    closeLeftColumn()
                    return true
                }
                scrollTimeline(TIMELINE_STEP_HOURS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (leftStage == STAGE_CONTENT && !daysList.hasFocus()) {
                    scrollTimeline(-TIMELINE_STEP_HOURS)
                    return true
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
        private const val GUIDE_DAYS = 7
        private const val GUIDE_CACHE_MAX = 6
        private const val STAGE_CONTENT = 0
        private const val STAGE_GROUPS = 1
        private const val STAGE_MENU = 2
        private const val TIMELINE_STEP_HOURS = 2
        private const val NOW_LINE_MARGIN = 0.12f
        private const val MIN_FLING_PX_PER_SEC = 350f
        private const val FLING_DECAY = 0.95f
        private const val FOCUS_ATTEMPTS = 5
        private const val FOCUS_RETRY_MS = 60L
    }
}
