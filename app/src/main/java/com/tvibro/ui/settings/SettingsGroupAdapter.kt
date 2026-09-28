package com.tvibro.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvibro.R

class SettingsGroupAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SettingsGroupAdapter.Holder>() {

    private var items: List<String> = emptyList()
    private var selectedPosition = 0

    fun submit(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        if (position == selectedPosition) return
        val old = selectedPosition
        selectedPosition = position
        notifyItemChanged(old)
        notifyItemChanged(selectedPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_settings_group, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val title = items[position]
        holder.title.text = title
        val isSelected = position == selectedPosition
        val color = if (isSelected) R.color.accent else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
        holder.itemView.isSelected = isSelected
        holder.itemView.setOnClickListener {
            setSelected(holder.bindingAdapterPosition)
            onClick(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.group_title)
    }
}
