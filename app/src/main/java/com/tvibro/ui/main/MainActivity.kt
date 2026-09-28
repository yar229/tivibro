package com.tvibro.ui.main

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
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
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.guide.TvGuideActivity
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
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var groupsList: RecyclerView
    private lateinit var channelsList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
    private lateinit var clockView: TextView
    private lateinit var clockDateView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-main").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private var categories: List<Category> = emptyList()
    private var currentChannels: List<Channel> = emptyList()
    private var lastProgressLoad = 0L
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
        buildNavButtons()
        startClock()

        groupsAdapter = GroupsAdapter(
            onClick = { index -> onCategorySelected(index) },
            onLongClick = { index -> onCategoryLongClick(index) },
        )
        groupsList.layoutManager = LinearLayoutManager(this)
        groupsList.adapter = groupsAdapter

        channelAdapter = ChannelAdapter(
            context = this,
            onClick = { index -> onChannelClick(index) },
            onLongClick = { index -> onChannelLongClick(index) },
        )
        channelsList.layoutManager = GridLayoutManager(this, spanCount())
        channelsList.adapter = channelAdapter

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
        reload(autoPlay = firstResume && prefs.turnOnLastChannel)
        firstResume = false
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        playerLaunched = false
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        groupsList = findViewById(R.id.groups_list)
        channelsList = findViewById(R.id.channels_list)
        emptyView = findViewById(R.id.empty_view)
        statusText = findViewById(R.id.status_text)
        clockView = findViewById(R.id.clock)
        clockDateView = findViewById(R.id.clock_date)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
    }

    private fun buildNavButtons() {
        val container = findViewById<LinearLayout>(R.id.nav_buttons)
        container.removeAllViews()
        val buttons = buildList {
            if (prefs.showGuideButton) add(NavButton(R.drawable.ic_guide, R.string.nav_guide) { openGuide() })
            add(NavButton(R.drawable.ic_search, R.string.search) { openSearch() })
            add(NavButton(R.drawable.ic_star, R.string.nav_favorites) { selectCategoryByFilter(ChannelFilter.FAVORITES) })
            if (prefs.showHistoryButton) add(NavButton(R.drawable.ic_history, R.string.nav_history) { openHistory() })
            add(NavButton(R.drawable.ic_movie, R.string.nav_movies) { openVod() })
            add(NavButton(R.drawable.ic_settings, R.string.nav_settings) { openSettings() })
        }
        buttons.forEach { button -> container.addView(createNavView(button)) }
    }

    private data class NavButton(val icon: Int, val label: Int, val action: () -> Unit)

    private fun createNavView(button: NavButton): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_nav, findViewById(R.id.nav_buttons), false)
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
        channelAdapter.setNow(now)
        updateProgress()
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
                channelAdapter.setLastPlayed(lastPlayed)
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
            val programs = if (prefs.showCurrentPrograms && channels.isNotEmpty()) {
                repo.programsMapFor(channels.map { it.id }, System.currentTimeMillis())
            } else {
                emptyMap()
            }
            main.post {
                currentChannels = channels
                channelAdapter.setLocale(Locale.getDefault())
                channelAdapter.submit(channels)
                channelAdapter.setPrograms(programs)
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
            }
        }
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

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val card = resources.getDimensionPixelSize(R.dimen.card_size) + 8
        return (width / card).coerceAtLeast(3)
    }

    private fun updateProgress() {
        val visibleIds = currentChannels.take(60).map { it.id }
        if (visibleIds.isEmpty() || !prefs.showCurrentPrograms) return
        // EPG does not change every second, a DB round trip per tick is wasteful
        val now = System.currentTimeMillis()
        if (now - lastProgressLoad < 15_000L) return
        lastProgressLoad = now
        executor.execute {
            val programs = repo.programsMapFor(visibleIds, now)
            main.post {
                if (prefs.showCurrentPrograms) {
                    channelAdapter.setPrograms(programs)
                }
            }
        }
    }

    // ---------------------------------------------------------------- events

    private fun onChannelClick(index: Int) {
        val channel = currentChannels.getOrNull(index)
        if (channel == null) return
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
                1 -> toggleFavorite(channel)
                2 -> repo.setChannelFlags(channel.id, hidden = true)
                3 -> repo.setChannelFlags(channel.id, blocked = !channel.blocked)
                4 -> showSorting()
                5 -> {
                    repo.removeHistory(channel.id)
                    toast(getString(R.string.remove_from_history))
                }
            }
            reload()
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

    private fun openGuide() {
        startActivity(Intent(this, TvGuideActivity::class.java))
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
                openGuide()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
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
    }
}
