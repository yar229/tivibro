package com.tvibro.ui.playlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.data.model.Playlist
import com.tvibro.data.model.PlaylistType

/**
 * One playlist per row, laid out as a table: the name, the type and the link sit in the same
 * columns as the header, and the two actions stay at the end of the row where the remote reaches
 * them with a right press.
 */
class PlaylistAdapter(
    private val onEdit: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private val items = ArrayList<Playlist>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlist_name)
        val type: TextView = view.findViewById(R.id.playlist_type)
        val url: TextView = view.findViewById(R.id.playlist_url)
        val edit: TextView = view.findViewById(R.id.playlist_edit)
        val delete: TextView = view.findViewById(R.id.playlist_delete)
    }

    fun submit(playlists: List<Playlist>) {
        items.clear()
        items += playlists
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Playlist? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = items[position]
        holder.name.text = playlist.name
        holder.type.setText(typeLabel(playlist.type))
        holder.url.text = playlist.url
        // The row itself carries the edit, so a plain OK on the row does the same as the button and
        // a tap straight on the name works on a touch screen.
        holder.itemView.setOnClickListener { onEdit(playlist) }
        holder.edit.setOnClickListener { onEdit(playlist) }
        holder.delete.setOnClickListener { onDelete(playlist) }
    }

    private fun typeLabel(type: PlaylistType): Int = when (type) {
        PlaylistType.REMOTE_M3U -> R.string.m3u_playlist
        PlaylistType.XTREAM -> R.string.xtream_codes
        PlaylistType.STALKER -> R.string.stalker_portal
        PlaylistType.FILE -> R.string.local_file
    }
}
