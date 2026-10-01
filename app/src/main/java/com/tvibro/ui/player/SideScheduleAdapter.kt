package com.tvibro.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.Fmt
import com.tvibro.base.pxPerSp
import com.tvibro.data.model.Program

class SideScheduleAdapter(
    private var fontScale: Float = 1f,
) : RecyclerView.Adapter<SideScheduleAdapter.Holder>() {

    private var items: List<Program> = emptyList()
    private var liveIds: Set<Long> = emptySet()
    private val timeBaseSp = R.dimen.text_xxs
    private val titleBaseSp = R.dimen.text_xs

    fun setFontScale(scale: Float) {
        if (fontScale == scale) return
        fontScale = scale
        notifyDataSetChanged()
    }

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

    private fun applyScale(view: TextView, dimen: Int) {
        if (fontScale == 1f) return
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, view.context.resources.getDimension(dimen) / view.context.pxPerSp() * fontScale)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val program = items[position]
        applyScale(holder.time, timeBaseSp)
        applyScale(holder.title, titleBaseSp)
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
