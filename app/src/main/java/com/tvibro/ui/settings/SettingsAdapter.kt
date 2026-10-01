package com.tvibro.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.visible

sealed class SettingItem {
    abstract val title: String

    data class Header(override val title: String) : SettingItem()
    data class Switch(
        override val title: String,
        val summary: String = "",
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
    ) : SettingItem()

    data class Choice(
        override val title: String,
        val summary: String = "",
        val entries: List<String>,
        val values: List<String>,
        val get: () -> String,
        val set: (String) -> Unit,
    ) : SettingItem()

    data class Number(
        override val title: String,
        val summary: String = "",
        val min: Int = 0,
        val max: Int = 999,
        val get: () -> Int,
        val set: (Int) -> Unit,
    ) : SettingItem()

    data class Action(
        override val title: String,
        val summary: String = "",
        val onClick: () -> Unit,
    ) : SettingItem()

    data class Value(override val title: String, val summary: String = "") : SettingItem()

    /** Column names of the playlist table; carries no value of its own. */
    data object PlaylistHeader : SettingItem() {
        override val title: String = ""
    }

    /**
     * A playlist in the playlists and channels group: the name, the type and the link in the
     * columns of the table, with the editor behind the row itself and the delete behind its button.
     */
    data class PlaylistRow(
        override val title: String,
        val type: String,
        val url: String,
        val onEdit: () -> Unit,
        val onDelete: () -> Unit,
    ) : SettingItem()
}

class SettingsAdapter(
    private val onAction: (SettingItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<SettingItem> = emptyList()

    fun submit(newItems: List<SettingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun notifyChanged(index: Int) {
        notifyItemChanged(index)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingItem.Header -> TYPE_HEADER
        is SettingItem.PlaylistHeader -> TYPE_TABLE_HEADER
        is SettingItem.PlaylistRow -> TYPE_PLAYLIST
        else -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_setting_category, parent, false))
            TYPE_TABLE_HEADER -> TableHeaderHolder(inflater.inflate(R.layout.item_playlist_header, parent, false))
            TYPE_PLAYLIST -> PlaylistHolder(inflater.inflate(R.layout.item_playlist, parent, false))
            else -> ItemHolder(inflater.inflate(R.layout.item_setting, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderHolder) {
            holder.title.text = (item as SettingItem.Header).title
            return
        }
        if (holder is PlaylistHolder) {
            val row = item as SettingItem.PlaylistRow
            holder.name.text = row.title
            holder.type.text = row.type
            holder.url.text = row.url
            holder.itemView.setOnClickListener { row.onEdit() }
            holder.delete.setOnClickListener { row.onDelete() }
            return
        }
        // The column names of the playlist table carry no value, so there is nothing to fill in.
        if (holder is TableHeaderHolder) return
        holder as ItemHolder
        holder.title.text = item.title
        holder.summary.visible(item.summarySafe().isNotEmpty())
        holder.summary.text = item.summarySafe()
        holder.switch.visible(item is SettingItem.Switch)
        holder.value.visible(false)
        holder.chevron.visible(true)

        when (item) {
            is SettingItem.Switch -> {
                holder.switch.isChecked = item.get()
                holder.itemView.setOnClickListener {
                    val newValue = !item.get()
                    item.set(newValue)
                    holder.switch.isChecked = newValue
                    onAction(item)
                }
            }
            is SettingItem.Choice -> {
                val value = item.get()
                holder.value.visible(true)
                holder.value.text = item.entries.getOrNull(item.values.indexOf(value)).orEmpty()
                holder.itemView.setOnClickListener { onAction(item) }
            }
            is SettingItem.Number -> {
                holder.value.visible(true)
                holder.value.text = item.get().toString()
                holder.itemView.setOnClickListener { onAction(item) }
            }
            is SettingItem.Action -> {
                holder.itemView.setOnClickListener { onAction(item) }
            }
            is SettingItem.Value -> {
                holder.itemView.setOnClickListener { }
            }
            is SettingItem.Header -> Unit
            is SettingItem.PlaylistHeader -> Unit
            is SettingItem.PlaylistRow -> Unit
        }
        holder.chevron.visible(item !is SettingItem.Switch)
    }

    private fun SettingItem.summarySafe(): String = when (this) {
        is SettingItem.Switch -> summary
        is SettingItem.Choice -> summary
        is SettingItem.Number -> summary
        is SettingItem.Action -> summary
        is SettingItem.Value -> summary
        is SettingItem.Header -> ""
        is SettingItem.PlaylistHeader -> ""
        is SettingItem.PlaylistRow -> ""
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.category_title)
    }

    class TableHeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    class PlaylistHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlist_name)
        val type: TextView = view.findViewById(R.id.playlist_type)
        val url: TextView = view.findViewById(R.id.playlist_url)
        val delete: TextView = view.findViewById(R.id.playlist_delete)
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.setting_title)
        val summary: TextView = view.findViewById(R.id.setting_summary)
        val value: TextView = view.findViewById(R.id.setting_value)
        val switch: SwitchCompat = view.findViewById(R.id.setting_switch)
        val chevron: ImageView = view.findViewById(R.id.setting_chevron)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_TABLE_HEADER = 2
        private const val TYPE_PLAYLIST = 3
    }
}
