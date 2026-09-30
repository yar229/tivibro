package com.tvibro.ui.main.guide

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R
import com.tvibro.base.Fmt
import java.util.Locale

class GuideDaysAdapter(
    private val daysCount: Int,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<GuideDaysAdapter.Holder>() {

    private var days: List<Long> = emptyList()
    private var selected = 0
    private val locale = Locale.getDefault()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.day_name)
        val date: TextView = view.findViewById(R.id.day_date)
    }

    fun submit(startOfToday: Long) {
        days = (0 until daysCount).map { startOfToday + it * DAY_MS }
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index == selected || index !in days.indices) return
        val previous = selected
        selected = index
        notifyItemChanged(previous)
        notifyItemChanged(selected)
    }

    fun selectedIndex(): Int = selected

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_day, parent, false))

    override fun getItemCount(): Int = days.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val time = days[position]
        holder.name.setText(
            when (position) {
                0 -> R.string.today
                1 -> R.string.tomorrow
                else -> R.string.yesterday
            }
        )
        if (position > 1) holder.name.text = Fmt.dayName(time, locale)
        holder.date.text = Fmt.date(time, locale)
        holder.itemView.isActivated = position == selected
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val HOURS_IN_DAY = 24
    }
}
