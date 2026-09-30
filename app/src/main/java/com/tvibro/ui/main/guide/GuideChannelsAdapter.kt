package com.tvibro.ui.main.guide

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tvibro.R
import com.tvibro.base.visible
import com.tvibro.data.model.Channel

class GuideChannelsAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onFocus: (Int) -> Unit,
) : RecyclerView.Adapter<GuideChannelsAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var currentId: Long = -1L
    private var highlightCurrent: Boolean = true
    private var focused = RecyclerView.NO_POSITION

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.channel_name)
        val number: TextView = view.findViewById(R.id.channel_number)
    }

    fun submit(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setCurrent(channelId: Long) {
        if (currentId == channelId) return
        val previous = items.indexOfFirst { it.id == currentId }
        currentId = channelId
        val current = items.indexOfFirst { it.id == channelId }
        if (previous >= 0) notifyItemChanged(previous)
        if (current >= 0) notifyItemChanged(current)
    }

    fun currentId(): Long = currentId

    /** Row that currently holds the remote focus, or NO_POSITION when the list has none. */
    fun focusedPosition(): Int = focused

    fun setHighlightCurrent(enabled: Boolean) {
        if (highlightCurrent == enabled) return
        highlightCurrent = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_channel, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.name.text = channel.name
        holder.number.visible(channel.number.isNotEmpty())
        holder.number.text = channel.number
        if (channel.logoUrl.isNotBlank()) {
            holder.logo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_logo_channel)
                error(R.drawable.ic_logo_channel)
            }
        } else {
            holder.logo.setImageResource(R.drawable.ic_logo_channel)
        }
        holder.itemView.isActivated = highlightCurrent && channel.id == currentId
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
        // Moving through the rows with a remote is what tells the info panel which channel to
        // describe, so focus has to be reported instead of only being drawn.
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            val focusedRow = holder.bindingAdapterPosition
            if (focusedRow == RecyclerView.NO_POSITION) return@setOnFocusChangeListener
            if (hasFocus) {
                focused = focusedRow
                onFocus(focusedRow)
            } else if (focused == focusedRow) {
                focused = RecyclerView.NO_POSITION
            }
        }
        // A row that is bound while it already holds the focus never gets the callback above, so the
        // cache would keep pointing at the row the remote has left and the centre key would answer
        // for the wrong channel.
        if (holder.itemView.hasFocus()) {
            focused = holder.bindingAdapterPosition
            onFocus(focused)
        }
    }
}
