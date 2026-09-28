package com.tvibro.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.Fmt
import com.tvibro.data.model.Program

class SideScheduleAdapter : RecyclerView.Adapter<SideScheduleAdapter.Holder>() {

    private var items: List<Program> = emptyList()
    private var liveIds: Set<Long> = emptySet()

    fun submit(newItems: List<Program>) {
        if (newItems == items) return
        items = newItems
        liveIds = livePrograms(items)
        notifyDataSetChanged()
    }

    fun refreshNow() {
        if (items.isEmpty()) return
        val next = livePrograms(items)
        if (next == liveIds) return
        items.forEachIndexed { index, program ->
            if ((program.id in next) != (program.id in liveIds)) notifyItemChanged(index)
        }
        liveIds = next
    }

    private fun livePrograms(source: List<Program>): Set<Long> =
        source.asSequence().filter { it.isLive }.map { it.id }.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_side_schedule, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val program = items[position]
        holder.time.text = "${Fmt.time(program.start)} — ${Fmt.time(program.stop)}"
        holder.title.text = program.title
        holder.title.paint.isFakeBoldText = program.isLive
        holder.itemView.isActivated = program.isLive
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.side_schedule_time)
        val title: TextView = view.findViewById(R.id.side_schedule_title)
    }
}
