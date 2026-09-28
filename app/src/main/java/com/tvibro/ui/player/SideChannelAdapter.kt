package com.tvibro.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tvibro.R
import com.tvibro.base.visible
import com.tvibro.data.model.Channel

class SideChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onFocus: ((Channel) -> Unit)? = null,
) : RecyclerView.Adapter<SideChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var programs: Map<Long, ProgramInfo> = emptyMap()

    fun submit(newItems: List<Channel>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun updatePrograms(newPrograms: Map<Long, ProgramInfo>) {
        programs = newPrograms
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_side_channel, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.name.text = channel.name
        holder.number.text = channel.number
        holder.number.visible(channel.number.isNotEmpty())
        val program = programs[channel.id]
        holder.program.text = program?.title.orEmpty()
        holder.program.visible(!program?.title.isNullOrBlank())
        if (program != null) {
            holder.progressBar.progress = program.progress
            holder.progressBar.visible(true)
        } else {
            holder.progressBar.visible(false)
        }
        if (channel.logoUrl.isNotBlank()) {
            holder.logo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_logo_channel)
                error(R.drawable.ic_logo_channel)
            }
        } else {
            holder.logo.setImageResource(R.drawable.ic_logo_channel)
        }
        holder.itemView.setOnClickListener { onClick(channel) }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocus?.invoke(channel)
        }
    }

    override fun getItemCount(): Int = items.size

    fun getChannel(position: Int): Channel? = items.getOrNull(position)

    fun indexOf(channelId: Long): Int = items.indexOfFirst { it.id == channelId }

    fun currentProgram(channelId: Long): ProgramInfo? = programs[channelId]

    data class ProgramInfo(
        val title: String,
        val progress: Int,
    )

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.side_channel_logo)
        val name: TextView = view.findViewById(R.id.side_channel_name)
        val number: TextView = view.findViewById(R.id.side_channel_number)
        val program: TextView = view.findViewById(R.id.side_channel_program)
        val progressBar: ProgressBar = view.findViewById(R.id.side_channel_progress)
    }
}
