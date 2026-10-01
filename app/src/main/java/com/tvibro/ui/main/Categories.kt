package com.tvibro.ui.main

import android.content.Context
import com.tvibro.R
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.model.ChannelFilter
import com.tvibro.data.model.Playlist

data class Category(
    val name: String,
    val playlistIds: List<Long>,
    val group: String = TvBroRepository.ALL_GROUPS,
    val filter: ChannelFilter = ChannelFilter.ALL,
    val icon: Int = R.drawable.ic_list,
    val playlistName: String = "",
)

object Categories {

    fun build(context: Context, repo: TvBroRepository, playlists: List<Playlist>): List<Category> {
        val prefs = Prefs.get(context)
        val out = ArrayList<Category>()
        val enabled = playlists.filter { it.enabled }

        if (prefs.showAllChannelsCategory) {
            out += Category(
                name = context.getString(R.string.all_channels),
                playlistIds = enabled.map { it.id },
                icon = R.drawable.ic_list,
            )
        }
        if (prefs.showFavoritesCategory) {
            out += Category(
                name = context.getString(R.string.favorites),
                playlistIds = enabled.map { it.id },
                filter = ChannelFilter.FAVORITES,
                icon = R.drawable.ic_star,
            )
        }
        if (prefs.showAllPlaylistsCategory && enabled.size > 1) {
            enabled.sortedBy { it.orderIndex }.forEach { playlist ->
                out += Category(
                    name = context.getString(R.string.all_playlists) + " · " + playlist.name,
                    playlistIds = listOf(playlist.id),
                    playlistName = playlist.name,
                    icon = R.drawable.ic_folder,
                )
            }
        }
        // Every playlist is listed before any of the groups, so the column reads top down: first the
        // service entries, then the playlists, and only then the groups they are split into.
        val split = enabled.sortedBy { it.orderIndex }.map { it to repo.groupsOf(it.id) }
        split.forEach { (playlist, groups) ->
            if (groups.isNotEmpty()) {
                out += Category(
                    name = playlist.name,
                    playlistIds = listOf(playlist.id),
                    playlistName = playlist.name,
                    icon = R.drawable.ic_folder,
                )
            }
        }
        split.forEach { (playlist, groups) ->
            groups.forEach { group ->
                out += Category(
                    name = group,
                    playlistIds = listOf(playlist.id),
                    group = group,
                    playlistName = playlist.name,
                    icon = R.drawable.ic_list,
                )
            }
        }
        return out
    }
}
