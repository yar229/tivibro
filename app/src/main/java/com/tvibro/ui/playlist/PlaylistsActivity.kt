package com.tvibro.ui.playlist

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.base.visible
import com.tvibro.data.model.Playlist
import com.tvibro.ui.common.Dialogs
import java.util.concurrent.Executors

/**
 * The playlists of this installation as a table: name, type and link, with the two actions a row
 * owns. Adding reuses the wizard untouched, editing hands the stored values to the same form, so a
 * playlist is only ever described in one place.
 */
class PlaylistsActivity : AppCompatActivity() {

    private lateinit var adapter: PlaylistAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var statusView: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-playlists").apply { isDaemon = true } }

    /** Row the focus has to be put on after the list changed, -1 when nothing is pending. */
    private var pendingFocus = -1
    private var firstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)

        emptyView = findViewById(R.id.empty_view)
        statusView = findViewById(R.id.playlists_status)
        adapter = PlaylistAdapter(
            onEdit = { edit(it) },
            onDelete = { confirmDelete(it) },
        )
        list = findViewById(R.id.playlists_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.add_playlist).setOnClickListener { PlaylistWizardActivity.start(this) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    /** The wizard adds and edits these same rows, so coming back here is the moment to read again. */
    private fun reload() {
        executor.execute {
            val playlists = runCatching { TvBroApp.repo(this).playlists() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(playlists)
                emptyView.visible(playlists.isEmpty())
                statusView.text = getString(R.string.playlists_count, playlists.size)
                focusAfterChange()
            }
        }
    }

    private fun edit(playlist: Playlist) {
        PlaylistWizardActivity.startEdit(this, playlist.id)
    }

    private fun confirmDelete(playlist: Playlist) {
        Dialogs.confirm(
            this,
            getString(R.string.delete),
            getString(R.string.confirm_delete_playlist, playlist.name),
            getString(R.string.delete),
        ) {
            val repo = TvBroApp.repo(this)
            val position = indexOf(playlist)
            pendingFocus = position
            executor.execute {
                runCatching { repo.deletePlaylist(playlist.id) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    reload()
                }
            }
        }
    }

    private fun indexOf(playlist: Playlist): Int {
        for (i in 0 until adapter.itemCount) {
            if (adapter.itemAt(i)?.id == playlist.id) return i
        }
        return 0
    }

    /**
     * Rows come and go under the remote, so after a change the focus has to end on a row: the one
     * the deleted playlist left behind, or the first one of the list. When the focus is still where
     * the user put it, nothing is touched.
     */
    private fun focusAfterChange() {
        if (adapter.itemCount == 0) {
            pendingFocus = -1
            return
        }
        val forced = pendingFocus
        pendingFocus = -1
        if (forced < 0 && !firstLoad && list.findFocus() != null) return
        val wanted = forced.coerceIn(0, adapter.itemCount - 1)
        list.post {
            val manager = list.layoutManager as? LinearLayoutManager ?: return@post
            val row = manager.findViewByPosition(wanted) ?: manager.findViewByPosition(0)
            row?.requestFocus()
        }
        firstLoad = false
    }
}
