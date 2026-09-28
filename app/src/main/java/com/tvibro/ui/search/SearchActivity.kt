package com.tvibro.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import com.tvibro.data.model.ChannelFilter
import com.tvibro.ui.common.Dialogs
import com.tvibro.ui.main.ChannelAdapter
import com.tvibro.ui.player.PlayerActivity
import java.util.concurrent.Executors

class SearchActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: TvBroRepository
    private lateinit var adapter: ChannelAdapter
    private lateinit var field: EditText
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var includeVodView: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-search").apply { isDaemon = true } }
    private var query = ""
    private var includeVod = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        repo = TvBroApp.repo(this)
        setContentView(R.layout.activity_search)
        includeVod = prefs.includeVodInSearch

        field = findViewById(R.id.search_field)
        statusView = findViewById(R.id.search_status)
        emptyView = findViewById(R.id.empty_view)
        includeVodView = findViewById(R.id.include_vod)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.clear_button).setOnClickListener {
            field.setText("")
            showHistory()
        }
        includeVodView.setOnClickListener {
            includeVod = !includeVod
            prefs.includeVodInSearch = includeVod
            updateIncludeVodLabel()
            runSearch()
        }
        updateIncludeVodLabel()

        adapter = ChannelAdapter(
            context = this,
            onClick = { index -> play(index) },
            onLongClick = { index -> showMenu(index) },
        )
        findViewById<RecyclerView>(R.id.results_list).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, spanCount())
            adapter = this@SearchActivity.adapter
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                runSearch()
            }
        })
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = field.text.toString()
                if (text.isNotEmpty()) {
                    saveQuery(text)
                    runSearch()
                }
                true
            } else {
                false
            }
        }
        field.setOnClickListener { }
        showHistory()
        field.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun updateIncludeVodLabel() {
        includeVodView.setText(if (includeVod) R.string.on else R.string.off)
    }

    private fun showHistory() {
        val history = prefs.stringSet(KEY_SEARCH_HISTORY).toList()
        adapter.submit(history.map { it.toChannel() })
        statusView.text = if (history.isEmpty()) {
            getString(R.string.no_history)
        } else {
            getString(R.string.search_history)
        }
        emptyView.visible(history.isEmpty())
    }

    private fun saveQuery(text: String) {
        val set = LinkedHashSet(prefs.stringSet(KEY_SEARCH_HISTORY))
        set.add(text)
        val trimmed = set.toMutableSet()
        if (trimmed.size > 30) trimmed.remove(trimmed.first())
        prefs.putStringSet(KEY_SEARCH_HISTORY, trimmed)
    }

    private fun runSearch() {
        if (query.isBlank()) {
            showHistory()
            return
        }
        executor.execute {
            val playlists = repo.playlists(onlyEnabled = true).map { it.id }
            val channels = repo.channels(playlists, "", ChannelFilter.ALL, "name", search = query)
            val vod = if (includeVod) {
                repo.vod(playlists, series = false, search = query) + repo.vod(playlists, series = true, search = query)
            } else {
                emptyList()
            }
            val all = channels + vod
            runOnUiThread {
                adapter.submit(all)
                emptyView.visible(all.isEmpty())
                statusView.text = getString(R.string.channels_count, all.size)
            }
        }
    }

    private fun play(index: Int) {
        val channel = adapter.itemAt(index) ?: return
        if (channel.id == 0L) {
            field.setText(channel.name)
            field.setSelection(channel.name.length)
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        )
    }

    private fun showMenu(index: Int) {
        val channel = adapter.itemAt(index) ?: return
        val options = listOf(
            Dialogs.Item(getString(R.string.watch_channel)),
            Dialogs.Item(getString(R.string.add_to_favorites)),
            Dialogs.Item(getString(R.string.remove_from_history)),
        )
        Dialogs.show(this, channel.name, null, options) { which ->
            when (which) {
                0 -> play(index)
                1 -> {
                    executor.execute {
                        repo.setChannelFlags(channel.id, favorite = !channel.favorite)
                        runOnUiThread { toast(getString(R.string.added_to_favorites)) }
                    }
                }
                2 -> {
                    executor.execute { repo.removeHistory(channel.id) }
                    toast(getString(R.string.remove_from_history))
                }
            }
        }
    }

    private fun String.toChannel() = com.tvibro.data.model.Channel(
        id = 0L,
        name = this,
    )

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val card = resources.getDimensionPixelSize(R.dimen.card_size) + 8
        return (width / card).coerceAtLeast(3)
    }

    companion object {
        const val KEY_SEARCH_HISTORY = "search_history"
    }
}
