package com.tvibro.ui.vod

import android.content.Intent
import android.os.Bundle
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
import com.tvibro.base.toast
import com.tvibro.base.visible
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.Channel
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.main.Categories
import com.tvibro.ui.main.Category
import com.tvibro.ui.player.PlayerActivity
import com.tvibro.ui.search.SearchActivity
import com.tvibro.ui.settings.SettingsActivity
import java.util.concurrent.Executors

class VodActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: TvBroRepository
    private lateinit var adapter: VodAdapter
    private lateinit var groupsAdapter: com.tvibro.ui.main.GroupsAdapter
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-vod").apply { isDaemon = true } }
    private var categories: List<Category> = emptyList()
    private var isSeries = false
    private var myListMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        repo = TvBroApp.repo(this)
        setContentView(R.layout.activity_vod)

        statusView = findViewById(R.id.status_text)
        titleView = findViewById(R.id.title)
        findViewById<TextView>(R.id.subtitle).visible(false)

        buildNavButtons()

        groupsAdapter = com.tvibro.ui.main.GroupsAdapter(
            onClick = { index -> onCategorySelected(index) },
            onLongClick = { },
        )
        findViewById<RecyclerView>(R.id.groups_list).apply {
            layoutManager = LinearLayoutManager(this@VodActivity)
            adapter = groupsAdapter
        }

        adapter = VodAdapter(
            context = this,
            onClick = { index -> onItemClick(index) },
            onLongClick = { index -> onItemLongClick(index) },
        )
        findViewById<RecyclerView>(R.id.channels_list).apply {
            layoutManager = GridLayoutManager(this@VodActivity, spanCount())
            adapter = this@VodActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun buildNavButtons() {
        val container = findViewById<LinearLayout>(R.id.nav_buttons)
        container.removeAllViews()
        val buttons = listOf(
            Triple(R.drawable.ic_movie, R.string.movies, 0),
            Triple(R.drawable.ic_tv, R.string.shows, 1),
            Triple(R.drawable.ic_star, R.string.my_list, 2),
            Triple(R.drawable.ic_search, R.string.search, 3),
            Triple(R.drawable.ic_settings, R.string.settings, 4),
        )
        buttons.forEach { (icon, label, action) ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_nav, container, false)
            view.findViewById<android.widget.ImageView>(R.id.nav_icon).setImageResource(icon)
            view.findViewById<TextView>(R.id.nav_label).setText(label)
            view.setOnClickListener {
                when (action) {
                    0 -> switchMode(false, false)
                    1 -> switchMode(true, false)
                    2 -> {
                        myListMode = !myListMode
                        reload()
                    }
                    3 -> startActivity(Intent(this, SearchActivity::class.java))
                    4 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            container.addView(view)
        }
    }

    private fun switchMode(series: Boolean, myList: Boolean) {
        isSeries = series
        myListMode = myList
        reload()
    }

    private fun reload() {
        executor.execute {
            val playlists = repo.playlists(onlyEnabled = true)
            val ids = playlists.map { it.id }
            val categoriesList = buildVodCategories(ids)
            val items = if (myListMode) {
                repo.myList(ids)
            } else if (isSeries) {
                repo.vod(ids, series = true)
            } else {
                repo.vod(ids, series = false)
            }
            runOnUiThread {
                categories = categoriesList
                groupsAdapter.submit(categoriesList, 0)
                adapter.submit(items)
                titleView.setText(if (myListMode) R.string.my_list else if (isSeries) R.string.shows else R.string.movies)
                statusView.text = getString(R.string.movies_count, items.size)
                findViewById<TextView>(R.id.empty_view).apply {
                    visible(items.isEmpty())
                    text = getString(if (isSeries) R.string.no_shows else R.string.no_movies)
                }
            }
        }
    }

    private fun buildVodCategories(playlistIds: List<Long>): List<Category> {
        val out = ArrayList<Category>()
        out += Category(
            name = getString(if (isSeries) R.string.all_shows else R.string.all_movies),
            playlistIds = playlistIds,
            icon = R.drawable.ic_list,
        )
        if (prefs.groupMoviesByCategories || prefs.groupShowsByCategories) {
            repo.vodCategoriesOfItems(playlistIds, isSeries).forEach { category ->
                out += Category(
                    name = category,
                    playlistIds = playlistIds,
                    icon = R.drawable.ic_folder,
                )
            }
        }
        return out
    }

    private fun onCategorySelected(index: Int) {
        val category = categories.getOrNull(index) ?: return
        groupsAdapter.select(index)
        executor.execute {
            val playlists = repo.playlists(onlyEnabled = true)
            val items = when {
                myListMode -> repo.myList(category.playlistIds)
                index == 0 -> repo.vod(category.playlistIds, isSeries)
                else -> repo.vod(category.playlistIds, isSeries, category.name)
            }
            runOnUiThread {
                adapter.submit(items)
                statusView.text = getString(R.string.movies_count, items.size)
                findViewById<TextView>(R.id.empty_view).visible(items.isEmpty())
            }
        }
    }

    private fun onItemClick(index: Int) {
        val item = adapter.itemAt(index) ?: return
        if (item.isSeries) {
            startActivity(
                Intent(this, VodDetailsActivity::class.java)
                    .putExtra(VodDetailsActivity.EXTRA_CHANNEL_ID, item.id)
            )
        } else {
            play(item)
        }
    }

    private fun play(item: Channel) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, item.id)
        )
    }

    private fun onItemLongClick(index: Int) {
        val item = adapter.itemAt(index) ?: return
        val options = listOf(
            Dialogs.Item(getString(R.string.add_to_my_list)),
            Dialogs.Item(getString(R.string.remove_from_my_list)),
            Dialogs.Item(getString(R.string.delete_program)),
        )
        Dialogs.show(this, item.name, null, options) { which ->
            executor.execute {
                when (which) {
                    0 -> repo.setChannelFlags(item.id, inMyList = true)
                    1 -> repo.setChannelFlags(item.id, inMyList = false)
                    2 -> repo.deleteChannel(item.id)
                }
                runOnUiThread {
                    toast(getString(R.string.settings_saved))
                    reload()
                }
            }
        }
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val card = resources.getDimensionPixelSize(R.dimen.card_size) + 8
        return (width / card).coerceAtLeast(3)
    }
}
