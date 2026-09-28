package com.tvibro.ui.main

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tvibro.R
import com.tvibro.base.Fmt
import com.tvibro.base.visible
import com.tvibro.data.Prefs
import com.tvibro.data.model.Channel
import com.tvibro.data.model.Program
import java.util.Locale

class ChannelAdapter(
    private val context: Context,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var programs: Map<Long, Program> = emptyMap()
    private var now: Long = System.currentTimeMillis()
    private var lastPlayedId: Long = 0
    private val attached = mutableSetOf<Holder>()
    private var locale: Locale = Locale.getDefault()

    private val prefs by lazy { Prefs.get(context) }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.channel_logo)
        val favorite: ImageView = view.findViewById(R.id.badge_favorite)
        val hd: ImageView = view.findViewById(R.id.badge_hd)
        val name: TextView = view.findViewById(R.id.channel_name)
        val number: TextView = view.findViewById(R.id.channel_number)
        val program: TextView = view.findViewById(R.id.program_title)
        val programTime: TextView = view.findViewById(R.id.program_time)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val catchupBadge: TextView = view.findViewById(R.id.badge_catchup)
        val vodBadge: TextView = view.findViewById(R.id.badge_vod)
    }

    fun submit(newItems: List<Channel>) {
        if (items == newItems) return
        items = newItems
        notifyDataSetChanged()
    }

    fun setPrograms(map: Map<Long, Program>) {
        // a full rebind every second steals focus and breaks clicks, so only
        // notify when the data really changed and then only for the payload part
        if (programs == map) return
        programs = map
        notifyItemRangeChanged(0, itemCount, PAYLOAD_PROGRAMS)
    }

    fun setLastPlayed(id: Long) {
        if (lastPlayedId == id) return
        lastPlayedId = id
        notifyDataSetChanged()
    }

    fun setLocale(locale: Locale) {
        this.locale = locale
    }

    fun setNow(time: Long) {
        now = time
        if (!prefs.showCurrentTimeIndicator || programs.isEmpty()) return
        // update only the attached holders: notifying the adapter every second
        // re-lays out the whole grid, steals focus and never lets the window idle
        for (holder in attached) {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) bindProgress(holder, items[pos])
        }
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        attached += holder
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        attached -= holder
    }

    fun itemAt(index: Int): Channel? = items.getOrNull(index)

    fun items(): List<Channel> = items

    fun indexOf(channelId: Long): Int = items.indexOfFirst { it.id == channelId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        val p = prefs

        holder.name.visible(p.showChannelNames)
        holder.name.text = channel.name
        holder.name.maxLines = if (p.twoLineChannelNames) 2 else 1

        holder.number.visible(p.showChannelNumbers && channel.number.isNotEmpty())
        holder.number.text = channel.number

        holder.logo.loadLogo(channel)

        holder.favorite.visible(channel.favorite)
        holder.hd.visible(isHd(channel.name))

        bindProgram(holder, channel)

        val hasCatchup = channel.catchupSource.isNotEmpty() && channel.catchupDays > 0
        holder.catchupBadge.visible(hasCatchup && p.showCatchupIcon)
        holder.vodBadge.visible(channel.isVod)

        val isCurrent = channel.id == lastPlayedId
        if (isCurrent && p.highlightCurrentChannel) {
            holder.itemView.setBackgroundResource(R.drawable.bg_card_current)
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_card_channel)
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(pos)
            true
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val channel = items.getOrNull(position) ?: return
        if (payloads.contains(PAYLOAD_PROGRAMS)) bindProgram(holder, channel)
        if (payloads.contains(PAYLOAD_TICK)) bindProgress(holder, channel)
    }

    private fun bindProgram(holder: Holder, channel: Channel) {
        val p = prefs
        val program = programs[channel.id]
        val showProgram = p.showCurrentPrograms && program != null
        holder.program.visible(showProgram)
        holder.programTime.visible(showProgram)
        if (program != null) {
            holder.program.text = program.title
            holder.programTime.text = Fmt.timeRange(program.start, program.stop, locale)
            val color = if (p.highlightCurrentPrograms && p.highlightCurrentProgramsInColor) {
                ContextCompat.getColor(context, R.color.accent)
            } else if (p.highlightCurrentPrograms) {
                Color.WHITE
            } else {
                ContextCompat.getColor(context, R.color.text_secondary)
            }
            holder.program.setTextColor(color)
        }
        bindProgress(holder, channel)
    }

    private fun bindProgress(holder: Holder, channel: Channel) {
        val program = programs[channel.id]
        val showProgress = program != null && prefs.showCurrentTimeIndicator
        holder.progress.visible(showProgress)
        if (showProgress && program != null) {
            holder.progress.progress = Fmt.percent(program.start, program.stop, now)
        }
    }

    private fun isHd(name: String): Boolean {
        val upper = name.uppercase(Locale.US)
        return upper.contains("HD") || upper.contains("FHD") || upper.contains("4K") || upper.contains("HEVC")
    }

    private fun ImageView.loadLogo(channel: Channel) {
        val url = channel.logoUrl
        if (url.isBlank()) {
            setImageResource(R.drawable.ic_logo_channel)
            return
        }
        load(url) {
            placeholder(R.drawable.ic_logo_channel)
            error(R.drawable.ic_logo_channel)
            crossfade(true)
        }
    }

    private companion object {
        const val PAYLOAD_PROGRAMS = "programs"
        const val PAYLOAD_TICK = "tick"
    }
}
